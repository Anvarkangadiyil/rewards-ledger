package com.anvarkangadiyil.rewards.ledger;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One immutable line in a customer's points history. Points are signed: earns are positive, spends negative. */
@Entity
@Table(name = "ledger_entry")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    private EntryType type;

    @Column(nullable = false)
    private long points;

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "request_hash")
    private String requestHash;

    private String reference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    public LedgerEntry(String customerId, EntryType type, long points, long balanceAfter,
                       String idempotencyKey, String requestHash, String reference, Instant createdAt) {
        this.customerId = customerId;
        this.type = type;
        this.points = points;
        this.balanceAfter = balanceAfter;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.reference = reference;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getCustomerId() { return customerId; }
    public EntryType getType() { return type; }
    public long getPoints() { return points; }
    public long getBalanceAfter() { return balanceAfter; }
    public String getRequestHash() { return requestHash; }
    public String getReference() { return reference; }
    public Instant getCreatedAt() { return createdAt; }
}
