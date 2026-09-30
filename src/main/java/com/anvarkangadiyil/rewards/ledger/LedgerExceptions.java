package com.anvarkangadiyil.rewards.ledger;

public final class LedgerExceptions {

    private LedgerExceptions() {
    }

    public static class AccountNotFound extends RuntimeException {
        public AccountNotFound(String customerId) {
            super("no rewards account for customer " + customerId);
        }
    }

    public static class InsufficientPoints extends RuntimeException {
        private final long available;
        private final long requested;

        public InsufficientPoints(long available, long requested) {
            super("requested " + requested + " points but only " + available + " are spendable");
            this.available = available;
            this.requested = requested;
        }

        public long available() { return available; }
        public long requested() { return requested; }
    }

    /** The key was already used for a different request. Retrying can't fix this, so it isn't a replay. */
    public static class IdempotencyConflict extends RuntimeException {
        public IdempotencyConflict(String key) {
            super("idempotency key " + key + " was already used with a different request");
        }
    }
}
