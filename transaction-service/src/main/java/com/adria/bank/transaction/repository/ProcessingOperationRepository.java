package com.adria.bank.transaction.repository;

import java.util.Optional;
import java.util.UUID;
import com.adria.bank.transaction.domain.ProcessingOperation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ProcessingOperationRepository extends JpaRepository<ProcessingOperation, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select op from ProcessingOperation op where op.id = :id")
    Optional<ProcessingOperation> lock(@Param("id") UUID id);
}
