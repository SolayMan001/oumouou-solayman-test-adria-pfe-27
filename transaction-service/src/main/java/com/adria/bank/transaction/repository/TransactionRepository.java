package com.adria.bank.transaction.repository;

import java.util.List;
import java.util.UUID;

import com.adria.bank.transaction.domain.BankTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<BankTransaction, UUID> {

    List<BankTransaction> findByAccountId(UUID accountId);
}
