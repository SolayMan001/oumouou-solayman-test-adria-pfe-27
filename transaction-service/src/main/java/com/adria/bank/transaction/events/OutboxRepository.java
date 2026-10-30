package com.adria.bank.transaction.events;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {
    @Query("select event.eventId from OutboxEvent event where event.publishedAt is null order by event.createdAt, event.eventId")
    List<UUID> pending(Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select event from OutboxEvent event where event.eventId = :id")
    Optional<OutboxEvent> lock(@Param("id") UUID id);
}
