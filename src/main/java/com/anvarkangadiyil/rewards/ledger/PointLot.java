package com.anvarkangadiyil.rewards.ledger;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Points earned in one go that share an expiry date. Spends draw down {@code remainingPoints}. */
@Entity
@Table(name = "point_lot")
public class PointLot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "earn_entry_id", nullable = false)
    private Long earnEntryId;

    @Column(name = "original_points", nullable = false)
    private long originalPoints;

    @Column(name = "remaining_points", nullable = false)
    private long remainingPoints;

    @Column(name = "earned_at", nullable = false)
    private Instant earnedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected PointLot() {
    }

    public PointLot(String customerId, Long earnEntryId, long points, Instant earnedAt, Instant expiresAt) {
        this.customerId = customerId;
        this.earnEntryId = earnEntryId;
        this.originalPoints = points;
        this.remainingPoints = points;
        this.earnedAt = earnedAt;
        this.expiresAt = expiresAt;
    }

    void consume(long points) {
        if (points <= 0 || points > remainingPoints) {
            throw new IllegalArgumentException(
                    "cannot take " + points + " from lot " + id + " with " + remainingPoints + " left");
        }
        remainingPoints -= points;
    }

    public Long getId() { return id; }
    public long getOriginalPoints() { return originalPoints; }
    public long getRemainingPoints() { return remainingPoints; }
    public Instant getExpiresAt() { return expiresAt; }
}
