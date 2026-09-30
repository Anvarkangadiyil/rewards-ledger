package com.anvarkangadiyil.rewards.ledger;

import java.time.Instant;
import java.util.List;

public final class LedgerViews {

    private LedgerViews() {
    }

    public record Allocation(long lotId, long points) {
    }

    /** {@code replayed} is true when this response is the stored result of an earlier request with the same key. */
    public record Entry(long id, String customerId, EntryType type, long points, long balanceAfter,
                        String reference, Instant createdAt, List<Allocation> allocations, boolean replayed) {
    }

    public record ExpiringLot(long lotId, long points, Instant expiresAt) {
    }

    public record Balance(String customerId, long spendable, List<ExpiringLot> expiringSoon) {
    }
}
