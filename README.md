# rewards-ledger

A loyalty-points backend in Java 17 and Spring Boot 3: an expiring-points ledger that stays correct under retries and concurrent requests, plus an offer allocator that assigns promotions under a fixed budget.

It's small on purpose. The code is the part of a rewards system where the bugs cost real money: double credits on a retried request, a balance going negative when two checkouts race, customers losing points they should have spent first, and promotion budgets being spent badly.

```
POST /api/v1/accounts/{customerId}/earn      Idempotency-Key: <uuid>   {"points": 250, "ttlDays": 365, "reference": "order-42"}
POST /api/v1/accounts/{customerId}/redeem    Idempotency-Key: <uuid>   {"points": 100, "reference": "checkout-7"}
GET  /api/v1/accounts/{customerId}/balance   -> spendable points, plus lots expiring in the next 30 days
GET  /api/v1/accounts/{customerId}/entries   -> history, each entry with the lots it drew from
POST /api/v1/offers/allocate                 {"budget": 5000, "candidates": [{"customerId": "a", "options": [{"offerId": "cashback", "value": 4.2, "cost": 3}]}]}
```

## Run it

```bash
./gradlew test                                   # 16 tests, in-memory H2 in PostgreSQL mode
./gradlew bootRun                                # API on :8080, H2
docker compose up -d && ./gradlew bootRun --args='--spring.profiles.active=postgres'
```

## Design

### Points are lots, not a number

Every earn creates a **lot** with its own expiry date. A balance is the sum of unexpired lots, and redemptions draw lots down. A single `balance` column can't answer "which points expire first?", and it can't tell you where a redemption's points came from if you ever need to refund it.

The ledger is append-only (`ledger_entry`). `lot_allocation` records which lots every redeem or expire drew from, so any balance can be rebuilt and audited.

### Spend the soonest-expiring points first

A redemption consumes lots ordered by `expires_at`, then by age. This is optimal for the customer: by an exchange argument, any other order can be rearranged into this one without increasing the points that expire unused. It's one indexed query (`ix_lot_customer_expiry`), costing O(k) in the number of lots touched.

### Concurrency: one lock per customer

Every balance change takes `SELECT ... FOR UPDATE` on the customer's `account` row first. That serializes changes for **one** customer and leaves every other customer untouched. `LedgerConcurrencyTest` fires 40 redemptions of 30 points each at a 1,000-point balance at the same instant. Exactly 33 succeed, and the balance ends at 10. Without the lock, several threads read the same balance and the account overdraws.

A customer's first-ever earn has a race of its own: two requests both try to create the account row. The loser hits the primary key, which is caught and ignored. That insert runs in its own transaction **before** the earn transaction starts. Nesting the two would hold two pooled connections per request, and a burst of new customers could exhaust the pool.

### Idempotency: retries are applied once

Clients retry on timeouts, and some of those retries are for requests that already succeeded. Every write needs an `Idempotency-Key`:

- **Same key, same request:** returns the stored result. Status is `200` instead of `201`, and the response includes `Idempotent-Replayed: true`.
- **Same key, different request** (compared by a SHA-256 hash of the request): `409`, because retrying can't fix it.
- The lookup happens while the account lock is held, so concurrent retries can't both get past it. A unique constraint on the key is the backstop. The test fires 40 identical retries at once and gets 1 write plus 39 replays.

### Expiry: lazy and swept

Reads and redemptions ignore expired lots, so correctness never depends on a background job. A scheduled sweeper also writes `EXPIRE` entries so history and balances stay tidy. It processes one customer per transaction under the same lock, so it's safe to run on several instances at once, and a second run finds nothing to do.

### Offer allocation under a budget

Given customers, their eligible offers (expected value and expected cost for each), and one budget, choose at most one offer per customer to maximize total value. This is a **multiple-choice knapsack**, which is NP-hard. `OfferAllocator` solves it approximately:

1. **Lagrangian relaxation.** Put a price λ on each unit of budget. For a fixed λ the problem splits by customer: each one takes the option with the highest `value − λ·cost`, or nothing if that's negative. Total cost only falls as λ rises, so bisection finds the smallest λ whose choices fit the budget.
2. **Greedy repair.** Spend any leftover budget on upgrades, best extra value per extra cost first.
3. **Single-offer fallback.** Compare the result against giving out only the single most valuable offer that fits, and keep the better of the two. This guarantees at least 50% of optimal.
4. **Certified bound.** By weak duality, `L(λ) = λ·B + Σ max(0, maxⱼ(vᵢⱼ − λ·cᵢⱼ))` is at least the true optimum. The response includes it, so every answer comes with a proven worst-case gap.

Measured by `OfferAllocatorTest` on synthetic data:

| Instance | Result |
|---|---|
| 500 random small cases (≤ 8 customers), compared against brute force | exact on 403/500; worst case 20.4% below optimal; never below the 50% guarantee |
| 100,000 customers × 4 offers, budget = 30% of max spend | ~0.9 s on a laptop; certified gap < 0.001% |

On small inputs a single customer can dominate the answer, so an exact DP would do better there. At real campaign sizes, the Lagrangian solution is within a rounding error of optimal and you can prove it.

## What this doesn't do (yet)

- **Refunds:** `lot_allocation` has what's needed to return points to the lots they came from, but there's no endpoint.
- **Outbox / events:** balance changes aren't published anywhere. A production version would write an outbox row in the same transaction so other services see each change exactly once.
- **Hot accounts:** a per-customer lock is fine for consumers. For one very busy merchant account it would be the bottleneck, and you'd shard the balance.
- **Per-offer inventory caps** in the allocator: these add one constraint per offer. Lagrangian relaxation still handles them (one λ per offer, solved with subgradient steps), but they aren't implemented.
