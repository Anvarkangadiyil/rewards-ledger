package com.anvarkangadiyil.rewards.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.anvarkangadiyil.rewards.MutableClock;
import com.anvarkangadiyil.rewards.ledger.LedgerExceptions.IdempotencyConflict;
import com.anvarkangadiyil.rewards.ledger.LedgerExceptions.InsufficientPoints;
import com.anvarkangadiyil.rewards.ledger.LedgerViews.Allocation;
import com.anvarkangadiyil.rewards.ledger.LedgerViews.Entry;

@SpringBootTest
@Import(MutableClock.Config.class)
class LedgerServiceTest {

    @Autowired LedgerService ledger;
    @Autowired ExpirySweeper sweeper;
    @Autowired MutableClock clock;

    private String customer;

    @BeforeEach
    void setUp() {
        clock.reset();
        customer = "c-" + UUID.randomUUID();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    @Test
    void redeemSpendsSoonestExpiringPointsFirst() {
        ledger.earn(customer, 100, 365, "order-1", key());
        Entry shortLived = ledger.earn(customer, 50, 10, "promo", key());

        Entry redeem = ledger.redeem(customer, 70, "checkout", key());

        assertThat(redeem.balanceAfter()).isEqualTo(80);
        assertThat(redeem.allocations()).extracting(Allocation::points).containsExactly(50L, 20L);
        // The 10-day promo lot goes first even though it was earned second.
        assertThat(ledger.balance(customer).expiringSoon()).isEmpty();
        assertThat(shortLived.balanceAfter()).isEqualTo(150);
    }

    @Test
    void expiredPointsAreNotSpendableEvenBeforeTheSweeperRuns() {
        ledger.earn(customer, 100, 5, null, key());
        clock.advance(Duration.ofDays(6));

        assertThat(ledger.balance(customer).spendable()).isZero();
        assertThatThrownBy(() -> ledger.redeem(customer, 10, null, key()))
                .isInstanceOf(InsufficientPoints.class)
                .satisfies(e -> assertThat(((InsufficientPoints) e).available()).isZero());
    }

    @Test
    void sweeperExpiresOnlyWhatIsLeftInLapsedLots() {
        ledger.earn(customer, 100, 5, null, key());
        ledger.earn(customer, 30, 100, null, key());
        ledger.redeem(customer, 20, null, key());
        clock.advance(Duration.ofDays(6));

        sweeper.sweep();
        sweeper.sweep(); // a second run must find nothing left to expire

        var history = ledger.history(customer, 10);
        assertThat(history).filteredOn(e -> e.type() == EntryType.EXPIRE)
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.points()).isEqualTo(-80);
                    assertThat(e.balanceAfter()).isEqualTo(30);
                });
        assertThat(ledger.balance(customer).spendable()).isEqualTo(30);
    }

    @Test
    void retryWithSameKeyIsAppliedOnce() {
        String k = key();
        Entry first = ledger.earn(customer, 100, null, "order-9", k);
        Entry retry = ledger.earn(customer, 100, null, "order-9", k);

        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(first.replayed()).isFalse();
        assertThat(retry.replayed()).isTrue();
        assertThat(ledger.balance(customer).spendable()).isEqualTo(100);
    }

    @Test
    void sameKeyWithDifferentRequestIsRejected() {
        String k = key();
        ledger.earn(customer, 100, null, null, k);
        ledger.earn(customer, 5, null, null, key());

        assertThatThrownBy(() -> ledger.redeem(customer, 5, null, k)).isInstanceOf(IdempotencyConflict.class);
    }

    @Test
    void failedRedeemLeavesNoTrace() {
        ledger.earn(customer, 40, null, null, key());

        assertThatThrownBy(() -> ledger.redeem(customer, 41, null, key())).isInstanceOf(InsufficientPoints.class);

        assertThat(ledger.history(customer, 10)).hasSize(1);
        assertThat(ledger.balance(customer).spendable()).isEqualTo(40);
    }
}
