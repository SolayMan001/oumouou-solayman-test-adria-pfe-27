#!/usr/bin/env python3
"""Run both packaged services on temporary ports/databases and verify restart-safe replay."""

import json
from pathlib import Path
import socket
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


def run():
    account_port, transaction_port = port(), port()
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
        except Exception:
            for logfile in directory.glob("*.log"):
                print(f"Last lines of {logfile.name}:")
                print("\n".join(logfile.read_text(errors="replace").splitlines()[-20:]))
            raise
        finally:
            for process in processes.values():
                stop(process)


if __name__ == "__main__":
    run()
