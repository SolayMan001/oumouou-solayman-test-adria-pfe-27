package com.adria.bank.transaction.events;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name = "transaction_event_outbox", indexes = @Index(name = "idx_pending_events", columnList = "published_at,created_at"))
public class OutboxEvent {
    @Id
    private UUID eventId;
    @Version
    private Long version;
    @Column(nullable = false, unique = true, updatable = false)
    private UUID transactionId;
    @Column(nullable = false, updatable = false)
    private UUID accountId;
    @Lob @Column(nullable = false, updatable = false)
    private String payload;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "published_at")
    private Instant publishedAt;
    private int attempts;

    protected OutboxEvent() { }
    public OutboxEvent(TransactionCreatedEvent event, String payload) {
        eventId = event.eventId(); transactionId = event.transactionId(); accountId = event.accountId();
        this.payload = payload; createdAt = Instant.now();
    }
    public void attempted() { attempts++; }
    public void published() { publishedAt = Instant.now(); }
    public UUID getEventId() { return eventId; }
    public UUID getAccountId() { return accountId; }
    public String getPayload() { return payload; }
    public Instant getPublishedAt() { return publishedAt; }
    public int getAttempts() { return attempts; }
}
