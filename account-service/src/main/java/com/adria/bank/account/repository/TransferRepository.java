package com.adria.bank.account.repository;

import java.util.UUID;
import com.adria.bank.account.domain.AccountTransfer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRepository extends JpaRepository<AccountTransfer, UUID> { }
