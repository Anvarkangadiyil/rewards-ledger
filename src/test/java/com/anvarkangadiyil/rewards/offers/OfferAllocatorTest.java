package com.anvarkangadiyil.rewards.offers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.anvarkangadiyil.rewards.offers.OfferAllocator.Assignment;
import com.anvarkangadiyil.rewards.offers.OfferAllocator.Candidate;
import com.anvarkangadiyil.rewards.offers.OfferAllocator.Option;
import com.anvarkangadiyil.rewards.offers.OfferAllocator.Result;

class OfferAllocatorTest {

    private static final double EPS = 1e-6;

    private final OfferAllocator allocator = new OfferAllocator();

    @Test
    void generousBudgetGivesEveryoneTheirBestOffer() {
        var candidates = List.of(
                new Candidate("a", List.of(new Option("cashback", 5, 2), new Option("2x-points", 8, 6))),
                new Candidate("b", List.of(new Option("cashback", 3, 1))));

        Result r = allocator.allocate(candidates, 100);

        assertThat(r.assignments()).extracting(Assignment::offerId).containsExactly("2x-points", "cashback");
        assertThat(r.gap()).isZero();
    }

    @Test
    void zeroBudgetOnlyAllowsFreeOffers() {
        var candidates = List.of(
                new Candidate("a", List.of(new Option("badge", 1, 0), new Option("cashback", 9, 3))),
                new Candidate("b", List.of(new Option("cashback", 9, 3))));

        Result r = allocator.allocate(candidates, 0);

        assertThat(r.assignments()).extracting(Assignment::offerId).containsExactly("badge");
        assertThat(r.totalCost()).isZero();
    }

    @Test
    void rejectsNegativeCosts() {
        var candidates = List.of(new Candidate("a", List.of(new Option("x", 1, -1))));
        assertThatThrownBy(() -> allocator.allocate(candidates, 10)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Compares against exhaustive search on small random instances. Checks that the answer fits the budget,
     * that the bound really is above the true optimum, and that the answer is never below half of optimal
     * (the guarantee from taking the better of greedy and the single best offer).
     */
    @Test
    void matchesBruteForceOnSmallInstances() {
        Random rnd = new Random(42);
        int provedOptimal = 0;
        double worstRelativeLoss = 0;
        int trials = 500;

        for (int t = 0; t < trials; t++) {
            List<Candidate> candidates = randomInstance(rnd, 1 + rnd.nextInt(8), 1 + rnd.nextInt(3));
            double budget = rnd.nextDouble() * totalMaxCost(candidates);

            Result r = allocator.allocate(candidates, budget);
            double exact = bruteForce(candidates, budget);

            assertThat(r.totalCost()).isLessThanOrEqualTo(budget + EPS);
            assertThat(r.totalValue()).isLessThanOrEqualTo(exact + EPS);
            assertThat(r.upperBound()).isGreaterThanOrEqualTo(exact - EPS);
            assertThat(exact - r.totalValue()).isLessThanOrEqualTo(maxValue(candidates) + EPS);
            assertThat(r.totalValue()).isGreaterThanOrEqualTo(exact / 2 - EPS);

            if (exact - r.totalValue() < EPS) {
                provedOptimal++;
            } else {
                worstRelativeLoss = Math.max(worstRelativeLoss, (exact - r.totalValue()) / exact);
            }
        }
        System.out.printf("small instances: optimal in %d/%d, worst loss vs optimum %.1f%%%n",
                provedOptimal, trials, worstRelativeLoss * 100);
    }

    /** At realistic sizes one customer is a rounding error, so the certified gap should be tiny. */
    @Test
    void largeInstanceIsFastAndCertifiedNearOptimal() {
        Random rnd = new Random(7);
        List<Candidate> candidates = randomInstance(rnd, 100_000, 4);
        double budget = totalMaxCost(candidates) * 0.3;

        allocator.allocate(candidates, budget); // warm up the JIT
        long start = System.nanoTime();
        Result r = allocator.allocate(candidates, budget);
        long millis = (System.nanoTime() - start) / 1_000_000;

        System.out.printf("100k customers x 4 offers: %d ms, %d assigned, certified gap %.5f%%%n",
                millis, r.assignments().size(), r.gap() * 100);
        assertThat(r.totalCost()).isLessThanOrEqualTo(budget + EPS);
        assertThat(r.gap()).isLessThan(0.001);
    }

    private static List<Candidate> randomInstance(Random rnd, int customers, int offers) {
        List<Candidate> list = new ArrayList<>(customers);
        for (int i = 0; i < customers; i++) {
            List<Option> options = new ArrayList<>(offers);
            for (int j = 0; j < offers; j++) {
                double cost = 1 + rnd.nextDouble() * 20;
                double value = cost * (0.2 + rnd.nextDouble() * 2); // better offers tend to cost more
                options.add(new Option("offer-" + j, value, cost));
            }
            list.add(new Candidate("c" + i, options));
        }
        return list;
    }

    private static double bruteForce(List<Candidate> candidates, double budget) {
        return search(candidates, 0, budget, 0);
    }

    private static double search(List<Candidate> candidates, int i, double budgetLeft, double valueSoFar) {
        if (i == candidates.size()) {
            return valueSoFar;
        }
        double best = search(candidates, i + 1, budgetLeft, valueSoFar); // no offer for this customer
        for (Option o : candidates.get(i).options()) {
            if (o.cost() <= budgetLeft) {
                best = Math.max(best, search(candidates, i + 1, budgetLeft - o.cost(), valueSoFar + o.value()));
            }
        }
        return best;
    }

    private static double totalMaxCost(List<Candidate> candidates) {
        return candidates.stream()
                .mapToDouble(c -> c.options().stream().mapToDouble(Option::cost).max().orElse(0))
                .sum();
    }

    private static double maxValue(List<Candidate> candidates) {
        return candidates.stream().flatMap(c -> c.options().stream()).mapToDouble(Option::value).max().orElse(0);
    }
}
