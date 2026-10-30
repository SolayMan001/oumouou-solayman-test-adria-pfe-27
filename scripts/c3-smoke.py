#!/usr/bin/env python3
"""Run both packaged services on temporary ports/databases and verify restart-safe replay."""

import json
from pathlib import Path
import socket
import sys
import subprocess
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
import uuid


ROOT = Path(__file__).resolve().parents[1]


def port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def request(base, path, method="GET", body=None, key=None):
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Idempotency-Key"] = key
    data = json.dumps(body).encode() if body is not None else None
    req = Request(base + path, data=data, headers=headers, method=method)
    try:
        with urlopen(req, timeout=5) as response:
            return response.status, json.load(response)
    except HTTPError as response:
        return response.code, json.load(response)


def stop(process):
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


class McpSession:
    """Minimal synchronous Streamable HTTP client used only for protocol smoke tests."""
    def __init__(self, base):
        self.url = base + "/mcp"
        self.session = None
        self.version = "2025-03-26"
        self.sequence = 0
        response = self.rpc("initialize", {"protocolVersion": self.version, "capabilities": {},
                                           "clientInfo": {"name": "adria-smoke", "version": "1.0"}})
        self.version = response["result"]["protocolVersion"]
        self.rpc("notifications/initialized", notification=True)

    def rpc(self, method, params=None, notification=False):
        self.sequence += 1
        body = {"jsonrpc": "2.0", "method": method}
        if not notification:
            body["id"] = self.sequence
        if params is not None:
            body["params"] = params
        headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream",
                   "MCP-Protocol-Version": self.version}
        if self.session:
            headers["Mcp-Session-Id"] = self.session
        req = Request(self.url, data=json.dumps(body).encode(), headers=headers, method="POST")
        with urlopen(req, timeout=10) as response:
            self.session = response.headers.get("Mcp-Session-Id", self.session)
            if response.status in (202, 204):
                return None
            if "text/event-stream" in response.headers.get("Content-Type", ""):
                for line in response:
                    if line.startswith(b"data:"):
                        message = json.loads(line[5:])
                        if message.get("id") == self.sequence:
                            assert "error" not in message, message
                            return message
                raise AssertionError("No MCP response event")
            message = json.load(response)
            assert "error" not in message, message
            return message

    def tool(self, name, arguments):
        result = self.rpc("tools/call", {"name": name, "arguments": arguments})["result"]
        assert not result.get("isError", False), result
        return json.loads(next(item["text"] for item in result["content"] if item["type"] == "text"))


def verify_interfaces(account_url, transaction_url):
    def graphql(base, query, variables=None):
        status, response = request(base, "/graphql", "POST", {"query": query, "variables": variables or {}})
        assert status == 200 and not response.get("errors"), response
        return response["data"]

    account_mcp, transaction_mcp = McpSession(account_url), McpSession(transaction_url)
    account_names = {tool["name"] for tool in account_mcp.rpc("tools/list")["result"]["tools"]}
    transaction_names = {tool["name"] for tool in transaction_mcp.rpc("tools/list")["result"]["tools"]}
    assert {"getAccount", "listAccounts", "createAccount", "updateAccountStatus", "transfer"} <= account_names
    assert {"getTransaction", "listTransactions", "getTransactionsByAccount", "createTransaction"} <= transaction_names
    source = graphql(account_url,
                     'mutation { createAccount(input:{customerReference:"graphql-smoke", currency:"MAD", balance:"500"}) { id } }')["createAccount"]["id"]
    destination = account_mcp.tool("createAccount", {"input": {"customerReference": "mcp-smoke", "currency": "MAD", "balance": "50"}})["id"]
    key = str(uuid.uuid4())
    transaction_input = {"accountId": source, "amount": "10", "currency": "MAD", "merchantCategory": "FOOD", "country": "MA"}
    mutation = "mutation($input:CreateTransactionInput!,$key:ID!) { createTransaction(input:$input,idempotencyKey:$key) { id } }"
    transaction = graphql(transaction_url, mutation, {"input": transaction_input, "key": key})["createTransaction"]
    replay = transaction_mcp.tool("createTransaction", {"input": transaction_input, "idempotencyKey": key})
    assert replay["id"] == transaction["id"]
    assert request(account_url, f"/api/accounts/{source}")[1]["balance"] == 490
    assert transaction_mcp.tool("getTransaction", {"id": replay["id"]})["id"] == replay["id"]
    assert len(transaction_mcp.tool("getTransactionsByAccount", {"accountId": source})) == 1
    print("Live GraphQL creation and MCP replay share one transaction and one debit; MCP tools/list and tools/call work.")

    transfer_key = str(uuid.uuid4())
    transfer_input = {"sourceAccountId": source, "destinationAccountId": destination, "amount": "25"}
    status, transfer = request(account_url, "/api/transfers", "POST", transfer_input, transfer_key)
    assert status == 201, transfer
    transfer_query = "mutation($input:TransferInput!,$key:ID!) { transfer(input:$input,idempotencyKey:$key) { id } }"
    assert graphql(account_url, transfer_query, {"input": transfer_input, "key": transfer_key})["transfer"]["id"] == transfer["id"]
    assert account_mcp.tool("transfer", {"input": transfer_input, "idempotencyKey": transfer_key})["id"] == transfer["id"]
    assert account_mcp.tool("getAccount", {"id": source})["balance"] == 465
    assert account_mcp.tool("getAccount", {"id": destination})["balance"] == 75
    print("Live REST/GraphQL/MCP transfer replays share one receipt; balances 490/50 -> 465/75 conserve total funds.")


def run(default_ports=False):
    account_port, transaction_port = (8081, 8082) if default_ports else (port(), port())
    while transaction_port == account_port:
        transaction_port = port()
    account_url = f"http://127.0.0.1:{account_port}"
    transaction_url = f"http://127.0.0.1:{transaction_port}"
    processes = {}

    with tempfile.TemporaryDirectory(prefix="adria-c3-") as temporary:
        directory = Path(temporary)

        def start(module, service_port, database):
            jar = ROOT / module / "target" / f"{module}-0.0.1-SNAPSHOT.jar"
            if not jar.exists():
                raise RuntimeError(f"Build first with mvn clean verify: missing {jar}")
            args = ["java", "-jar", str(jar), f"--server.port={service_port}",
                    f"--spring.datasource.url=jdbc:h2:file:{directory / database};DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0",
                    f"--account.service.url={account_url}"]
            with (directory / f"{module}.log").open("ab") as log:
                process = subprocess.Popen(args, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
            processes[module] = process
            base = account_url if module == "account-service" else transaction_url
            path = "/api/accounts" if module == "account-service" else "/api/transactions"
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError(f"{module} exited during startup")
                try:
                    if request(base, path)[0] == 200:
                        return
                except (URLError, TimeoutError, ConnectionError):
                    pass
                time.sleep(0.1)
            raise RuntimeError(f"{module} did not become ready")

        try:
            start("account-service", account_port, "accounts")
            start("transaction-service", transaction_port, "transactions")
            status, account = request(account_url, "/api/accounts", "POST",
                                      {"customerReference": "c3-smoke", "currency": "MAD", "balance": 1000})
            assert status == 201, account
            account_id = account["id"]
            key = str(uuid.uuid4())
            payload = {"accountId": account_id, "amount": 100, "currency": "MAD",
                       "merchantCategory": "GROCERIES", "country": "MA"}
            status, transaction = request(transaction_url, "/api/transactions", "POST", payload, key)
            assert status == 201, transaction
            transaction_id = transaction["id"]
            status, replay = request(transaction_url, "/api/transactions", "POST", payload, key)
            assert status == 201 and replay["id"] == transaction_id, replay
            assert request(account_url, f"/api/accounts/{account_id}")[1]["balance"] == 900
            assert request(transaction_url, "/api/transactions", "POST", {**payload, "amount": 101}, key)[0] == 409
            assert len(request(transaction_url, "/api/transactions")[1]) == 1
            print("Real services: creation 201; balance 1000 -> 900; replay returns same transaction; payload conflict 409.")

            stop(processes["transaction-service"])
            stop(processes["account-service"])
            start("account-service", account_port, "accounts")
            start("transaction-service", transaction_port, "transactions")
            status, replay = request(transaction_url, "/api/transactions", "POST", payload, key)
            assert status == 201 and replay["id"] == transaction_id, replay
            status, debit = request(account_url, f"/api/accounts/{account_id}/debit-operations/{key}",
                                    "PUT", {"amount": 100, "currency": "MAD"})
            assert status == 200 and debit["state"] == "DEBITED", debit
            assert request(account_url, f"/api/accounts/{account_id}")[1]["balance"] == 900
            assert len(request(transaction_url, "/api/transactions")[1]) == 1
            print("After both JVMs restart: transaction, balance, processing record and debit ledger survive; no second debit.")
            verify_interfaces(account_url, transaction_url)
        except Exception:
            for logfile in directory.glob("*.log"):
                print(f"Last lines of {logfile.name}:")
                print("\n".join(logfile.read_text(errors="replace").splitlines()[-20:]))
            raise
        finally:
            for process in processes.values():
                stop(process)


if __name__ == "__main__":
    run("--default-ports" in sys.argv)
