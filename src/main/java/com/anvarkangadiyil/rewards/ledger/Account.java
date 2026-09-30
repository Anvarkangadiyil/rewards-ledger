package com.anvarkangadiyil.rewards.ledger;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Exists mainly as the row that gets locked, so one customer's balance changes run one at a time. */
@Entity
@Table(name = "account")
public class Account {

    @Id
    @Column(name = "customer_id")
    private String customerId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Account() {
    }

    public Account(String customerId, Instant createdAt) {
        this.customerId = customerId;
        this.createdAt = createdAt;
    }

    public String getCustomerId() {
        return customerId;
    }
}
