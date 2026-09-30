package com.anvarkangadiyil.rewards.ledger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.anvarkangadiyil.rewards.ledger.LedgerExceptions.AccountNotFound;
import com.anvarkangadiyil.rewards.ledger.LedgerExceptions.IdempotencyConflict;
import com.anvarkangadiyil.rewards.ledger.LedgerExceptions.InsufficientPoints;
import com.anvarkangadiyil.rewards.ledger.LedgerViews.Allocation;
import com.anvarkangadiyil.rewards.ledger.LedgerViews.Balance;
import com.anvarkangadiyil.rewards.ledger.LedgerViews.Entry;
import com.anvarkangadiyil.rewards.ledger.LedgerViews.ExpiringLot;

/**
 * Points ledger. The rules it guarantees:
 * <ul>
 *   <li>A balance never goes negative, even under concurrent redemptions (per-customer row lock).</li>
 *   <li>A retried request with the same idempotency key is applied once and returns the original result.</li>
 *   <li>Spends use the soonest-expiring points first, so the customer loses as little as possible to expiry.</li>
 *   <li>Expired points are never spendable, whether or not the sweeper has run yet.</li>
 * </ul>
 */
@Service
public class LedgerService {

    private static final Duration EXPIRING_SOON = Duration.ofDays(30);

    private final AccountRepository accounts;
    private final LedgerEntryRepository entries;
    private final PointLotRepository lots;
    private final LotAllocationRepository allocations;
    private final AccountProvisioner provisioner;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int defaultTtlDays;

    LedgerService(AccountRepository accounts, LedgerEntryRepository entries, PointLotRepository lots,
                  LotAllocationRepository allocations, AccountProvisioner provisioner,
                  PlatformTransactionManager txManager, Clock clock,
                  @Value("${rewards.default-ttl-days}") int defaultTtlDays) {
        this.accounts = accounts;
        this.entries = entries;
        this.lots = lots;
        this.allocations = allocations;
        this.provisioner = provisioner;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.defaultTtlDays = defaultTtlDays;
    }

    /**
     * Not {@code @Transactional} on purpose: the account is provisioned first in its own transaction, and
     * only then does the earn transaction start. Nesting them would hold two pooled connections per
     * request, and a burst of first-time earns could then exhaust the pool waiting on itself.
     */
    public Entry earn(String customerId, long points, Integer ttlDays, String reference, String idempotencyKey) {
        int ttl = ttlDays != null ? ttlDays : defaultTtlDays;
        String requestHash = hash(EntryType.EARN, customerId, points, ttl, reference);

        try {
            provisioner.createIfMissing(customerId);
        } catch (DataIntegrityViolationException createdByConcurrentRequest) {
            // The row we wanted exists now, which is all we need.
        }

        return tx.execute(status -> {
            lockAccount(customerId);

            Optional<Entry> replay = replay(idempotencyKey, requestHash);
            if (replay.isPresent()) {
                return replay.get();
            }

            Instant now = clock.instant();
            long balanceAfter = lots.spendableTotal(customerId, now) + points;
            LedgerEntry entry = entries.save(new LedgerEntry(
                    customerId, EntryType.EARN, points, balanceAfter, idempotencyKey, requestHash, reference, now));
            lots.save(new PointLot(customerId, entry.getId(), points, now, now.plus(Duration.ofDays(ttl))));
            return view(entry, List.of(), false);
        });
    }

    @Transactional
    public Entry redeem(String customerId, long points, String reference, String idempotencyKey) {
        String requestHash = hash(EntryType.REDEEM, customerId, points, 0, reference);
        lockAccount(customerId);

        Optional<Entry> replay = replay(idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        Instant now = clock.instant();
        expireLocked(customerId, now);

        List<PointLot> spendable = lots.spendable(customerId, now);
        long available = spendable.stream().mapToLong(PointLot::getRemainingPoints).sum();
        if (available < points) {
            throw new InsufficientPoints(available, points);
        }

        LedgerEntry entry = entries.save(new LedgerEntry(
                customerId, EntryType.REDEEM, -points, available - points, idempotencyKey, requestHash, reference, now));
        List<LotAllocation> taken = drawDown(entry, spendable, points);
        return view(entry, taken, false);
    }

    /** Expires one customer's lapsed points. Called by the sweeper, one transaction per customer. */
    @Transactional
    public Optional<Entry> expire(String customerId) {
        lockAccount(customerId);
        return expireLocked(customerId, clock.instant());
    }

    public List<String> customersWithExpiredPoints() {
        return lots.customersWithExpiredPoints(clock.instant());
    }

    @Transactional(readOnly = true)
    public Balance balance(String customerId) {
        if (!accounts.existsById(customerId)) {
            throw new AccountNotFound(customerId);
        }
        Instant now = clock.instant();
        Instant horizon = now.plus(EXPIRING_SOON);
        List<ExpiringLot> expiringSoon = new ArrayList<>();
        long spendable = 0;
        for (PointLot lot : lots.spendable(customerId, now)) {
            spendable += lot.getRemainingPoints();
            if (!lot.getExpiresAt().isAfter(horizon)) {
                expiringSoon.add(new ExpiringLot(lot.getId(), lot.getRemainingPoints(), lot.getExpiresAt()));
            }
        }
        return new Balance(customerId, spendable, expiringSoon);
    }

    @Transactional(readOnly = true)
    public List<Entry> history(String customerId, int limit) {
        if (!accounts.existsById(customerId)) {
            throw new AccountNotFound(customerId);
        }
        return entries.findByCustomerIdOrderByIdDesc(customerId, PageRequest.of(0, limit)).stream()
                .map(e -> view(e, allocations.findByEntryIdOrderById(e.getId()), false))
                .toList();
    }

    private Optional<Entry> expireLocked(String customerId, Instant now) {
        List<PointLot> lapsed = lots.expired(customerId, now);
        if (lapsed.isEmpty()) {
            return Optional.empty();
        }
        long total = lapsed.stream().mapToLong(PointLot::getRemainingPoints).sum();
        long balanceAfter = lots.spendableTotal(customerId, now);
        LedgerEntry entry = entries.save(new LedgerEntry(
                customerId, EntryType.EXPIRE, -total, balanceAfter, null, null, "expiry", now));
        return Optional.of(view(entry, drawDown(entry, lapsed, total), false));
    }

    /** Takes {@code points} from lots in the order given. The caller has already checked there are enough. */
    private List<LotAllocation> drawDown(LedgerEntry entry, List<PointLot> ordered, long points) {
        List<LotAllocation> taken = new ArrayList<>();
        long remaining = points;
        for (PointLot lot : ordered) {
            if (remaining == 0) {
                break;
            }
            long take = Math.min(remaining, lot.getRemainingPoints());
            lot.consume(take);
            taken.add(allocations.save(new LotAllocation(entry.getId(), lot.getId(), take)));
            remaining -= take;
        }
        return taken;
    }

    private void lockAccount(String customerId) {
        accounts.lock(customerId).orElseThrow(() -> new AccountNotFound(customerId));
    }

    /**
     * Called with the account lock held, so a concurrent request with the same key for the same customer
     * can't slip in between the lookup and the insert. The unique constraint on the key is the backstop
     * for the same key being reused across different customers.
     */
    private Optional<Entry> replay(String idempotencyKey, String requestHash) {
        return entries.findByIdempotencyKey(idempotencyKey).map(existing -> {
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new IdempotencyConflict(idempotencyKey);
            }
            return view(existing, allocations.findByEntryIdOrderById(existing.getId()), true);
        });
    }

    private static Entry view(LedgerEntry e, List<LotAllocation> taken, boolean replayed) {
        List<Allocation> parts = taken.stream().map(a -> new Allocation(a.getLotId(), a.getPoints())).toList();
        return new Entry(e.getId(), e.getCustomerId(), e.getType(), e.getPoints(), e.getBalanceAfter(),
                e.getReference(), e.getCreatedAt(), parts, replayed);
    }

    private static String hash(EntryType type, String customerId, long points, int ttlDays, String reference) {
        String canonical = type + "|" + customerId + "|" + points + "|" + ttlDays + "|" + (reference == null ? "" : reference);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available on the JVM", e);
        }
    }
}
