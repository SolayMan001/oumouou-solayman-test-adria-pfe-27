package com.adria.bank.aiassistant.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name = "assistant_confirmations")
public class Confirmation {
    public enum State { PENDING, IN_PROGRESS, SUCCEEDED, UNKNOWN }
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false, updatable = false, unique = true)
    private UUID idempotencyKey;
    @Column(nullable = false, updatable = false)
    private String tool;
    @Lob @Column(nullable = false, updatable = false)
    private String arguments;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private State state;
    @Lob
    private String result;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    protected Confirmation() { }
    public Confirmation(UUID idempotencyKey, String tool, String arguments) {
        this.idempotencyKey = idempotencyKey; this.tool = tool; this.arguments = arguments;
        state = State.PENDING; createdAt = Instant.now();
    }
    public void start() { state = State.IN_PROGRESS; }
    public void succeed(String result) { this.result = result; state = State.SUCCEEDED; }
    public void unknown() { state = State.UNKNOWN; }
    public UUID getId() { return id; }
    public UUID getIdempotencyKey() { return idempotencyKey; }
    public String getTool() { return tool; }
    public String getArguments() { return arguments; }
    public State getState() { return state; }
    public String getResult() { return result; }
}
