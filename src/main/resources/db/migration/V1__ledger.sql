-- One row per customer. Redemptions and expiry take a row lock here, which serializes
-- all balance-changing work for a single customer without blocking other customers.
CREATE TABLE account (
    customer_id VARCHAR(64) PRIMARY KEY,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Append-only. Every change to a balance is one entry; nothing is updated or deleted.
CREATE TABLE ledger_entry (
    id              BIGSERIAL PRIMARY KEY,
    customer_id     VARCHAR(64) NOT NULL REFERENCES account (customer_id),
    entry_type      VARCHAR(16) NOT NULL,
    points          BIGINT      NOT NULL,
    balance_after   BIGINT      NOT NULL,
    idempotency_key VARCHAR(128),
    request_hash    VARCHAR(64),
    reference       VARCHAR(128),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_ledger_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_entry_type CHECK (entry_type IN ('EARN', 'REDEEM', 'EXPIRE'))
);

CREATE INDEX ix_ledger_customer_created ON ledger_entry (customer_id, created_at DESC);

-- A lot is a batch of points earned together that share one expiry date.
CREATE TABLE point_lot (
    id               BIGSERIAL PRIMARY KEY,
    customer_id      VARCHAR(64) NOT NULL REFERENCES account (customer_id),
    earn_entry_id    BIGINT      NOT NULL REFERENCES ledger_entry (id),
    original_points  BIGINT      NOT NULL,
    remaining_points BIGINT      NOT NULL,
    earned_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_lot_remaining CHECK (remaining_points >= 0 AND remaining_points <= original_points)
);

-- Serves both "spend soonest-expiring first" and "find expired lots".
CREATE INDEX ix_lot_customer_expiry ON point_lot (customer_id, expires_at);
CREATE INDEX ix_lot_expiry ON point_lot (expires_at);

-- Which lots each redemption or expiry drew from. This is the audit trail, and it is
-- what a refund would need to put points back into the lots they came from.
CREATE TABLE lot_allocation (
    id         BIGSERIAL PRIMARY KEY,
    entry_id   BIGINT NOT NULL REFERENCES ledger_entry (id),
    lot_id     BIGINT NOT NULL REFERENCES point_lot (id),
    points     BIGINT NOT NULL CHECK (points > 0)
);

CREATE INDEX ix_allocation_entry ON lot_allocation (entry_id);
