package com.adria.bank.account.repository;

import java.util.UUID;
import com.adria.bank.account.domain.DebitOperation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DebitOperationRepository extends JpaRepository<DebitOperation, UUID> { }
