package com.adria.bank.aiassistant.repository;

import java.util.Optional;
import java.util.UUID;
import com.adria.bank.aiassistant.domain.Confirmation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ConfirmationRepository extends JpaRepository<Confirmation, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select confirmation from Confirmation confirmation where confirmation.id = :id")
    Optional<Confirmation> lock(@Param("id") UUID id);
}
