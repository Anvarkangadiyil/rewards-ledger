package com.anvarkangadiyil.rewards.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.anvarkangadiyil.rewards.MutableClock;
import com.anvarkangadiyil.rewards.ledger.LedgerExceptions.InsufficientPoints;

/** Races many threads against one account. These are the bugs that only show up in production. */
@SpringBootTest
@Import(MutableClock.Config.class)
class LedgerConcurrencyTest {

    private static final int THREADS = 40;

    @Autowired LedgerService ledger;
    @Autowired MutableClock clock;

    private String customer;

    @BeforeEach
    void setUp() {
        clock.reset();
        customer = "c-" + UUID.randomUUID();
    }

    @Test
    void concurrentRedemptionsNeverOverdraw() throws Exception {
        ledger.earn(customer, 1_000, null, null, UUID.randomUUID().toString());

        List<Object> outcomes = race(i -> () -> ledger.redeem(customer, 30, "r" + i, UUID.randomUUID().toString()));

        long succeeded = outcomes.stream().filter(o -> o instanceof LedgerViews.Entry).count();
        long rejected = outcomes.stream().filter(o -> o instanceof InsufficientPoints).count();
        // 1,000 / 30 = 33 redemptions fit. Without the row lock, several threads read the same balance and all succeed.
        assertThat(succeeded).isEqualTo(33);
        assertThat(rejected).isEqualTo(THREADS - 33);
        assertThat(ledger.balance(customer).spendable()).isEqualTo(10);
    }

    @Test
    void concurrentRetriesOfOneRequestApplyOnce() throws Exception {
        ledger.earn(customer, 500, null, null, UUID.randomUUID().toString());
        String sameKey = UUID.randomUUID().toString();

        List<Object> outcomes = race(i -> () -> ledger.redeem(customer, 100, "checkout", sameKey));

        assertThat(outcomes).allMatch(o -> o instanceof LedgerViews.Entry);
        assertThat(outcomes).map(o -> ((LedgerViews.Entry) o).id()).containsOnly(((LedgerViews.Entry) outcomes.get(0)).id());
        assertThat(outcomes).filteredOn(o -> !((LedgerViews.Entry) o).replayed()).hasSize(1);
        assertThat(ledger.balance(customer).spendable()).isEqualTo(400);
    }

    @Test
    void concurrentFirstEarnsForANewCustomerAllLand() throws Exception {
        List<Object> outcomes = race(i -> () -> ledger.earn(customer, 10, null, null, UUID.randomUUID().toString()));

        assertThat(outcomes).allMatch(o -> o instanceof LedgerViews.Entry);
        assertThat(ledger.balance(customer).spendable()).isEqualTo(10L * THREADS);
    }

    interface TaskFactory {
        Callable<Object> task(int i);
    }

    /** Starts every task at the same instant and returns each result or the exception it threw. */
    private static List<Object> race(TaskFactory factory) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                Callable<Object> task = factory.task(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return task.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
