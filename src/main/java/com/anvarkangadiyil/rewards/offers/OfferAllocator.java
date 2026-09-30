package com.anvarkangadiyil.rewards.offers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Chooses at most one offer per customer to maximise total expected value under a shared budget.
 *
 * <p>The problem is: maximise sum(v[i][j] * x[i][j]) subject to sum(c[i][j] * x[i][j]) <= B,
 * sum_j x[i][j] <= 1 for each customer i, and x in {0, 1}. That is a multiple-choice knapsack, which is
 * NP-hard, so this solves it approximately in three steps:
 *
 * <ol>
 *   <li><b>Lagrangian relaxation.</b> Move the budget into the objective with a price lambda per unit of
 *       cost. For a fixed lambda the problem splits by customer: each one takes the option maximising
 *       {@code v - lambda * c}, or nothing if that's negative. Total cost can only fall as lambda rises,
 *       so bisection finds the smallest lambda whose choices fit the budget.</li>
 *   <li><b>Greedy repair.</b> The solution at that lambda usually leaves some budget unspent. Upgrades
 *       (a customer moving to a better option, or getting one) are applied in order of extra value per
 *       extra cost while they still fit.</li>
 *   <li><b>Single-offer fallback.</b> Compare with giving only the one most valuable offer that fits.
 *       The LP optimum is at most the greedy value plus one offer's value, so the better of the two is
 *       always at least half of optimal. That matters on small inputs; at scale greedy wins by far.</li>
 *   <li><b>Certified bound.</b> By weak duality, {@code L(lambda) = lambda * B + sum_i max(0, max_j(v - lambda * c))}
 *       is at least the true optimum for every lambda >= 0. Returning it lets a caller see how far from
 *       optimal the answer can be, without ever solving the exact problem.</li>
 * </ol>
 *
 * <p>Cost is O(n * k * log(1/eps)) for n customers with k options each, plus O(n * k * log(n * k)) for repair.
 */
@Component
public class OfferAllocator {

    private static final int BISECTION_STEPS = 100;
    private static final int NONE = -1;

    public record Option(String offerId, double value, double cost) {
    }

    public record Candidate(String customerId, List<Option> options) {
    }

    public record Assignment(String customerId, String offerId, double value, double cost) {
    }

    /**
     * {@code upperBound} is a proven ceiling on the best possible total value. {@code gap} is how far
     * this answer could be below the optimum, as a fraction of the bound (0 means proven optimal).
     */
    public record Result(List<Assignment> assignments, double totalValue, double totalCost, double budget,
                         double lambda, double upperBound, double gap) {
    }

    public Result allocate(List<Candidate> candidates, double budget) {
        validate(candidates, budget);

        int[] choice = choose(candidates, 0.0);
        double lambda = 0.0;
        double bound;

        if (cost(candidates, choice) <= budget) {
            // Everyone's favourite option already fits, so this is exactly optimal.
            bound = value(candidates, choice);
        } else {
            double lo = 0.0;
            double hi = maxRatio(candidates);
            for (int step = 0; step < BISECTION_STEPS; step++) {
                double mid = (lo + hi) / 2;
                if (cost(candidates, choose(candidates, mid)) <= budget) {
                    hi = mid;
                } else {
                    lo = mid;
                }
            }
            lambda = hi;
            choice = choose(candidates, hi);
            bound = Math.min(dual(candidates, budget, lo), dual(candidates, budget, hi));
            repair(candidates, choice, budget - cost(candidates, choice));
            choice = betterOf(choice, bestSingle(candidates, budget), candidates);
        }

        List<Assignment> assignments = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (choice[i] != NONE) {
                Candidate c = candidates.get(i);
                Option o = c.options().get(choice[i]);
                assignments.add(new Assignment(c.customerId(), o.offerId(), o.value(), o.cost()));
            }
        }
        double total = value(candidates, choice);
        double gap = bound <= 0 ? 0 : Math.max(0, (bound - total) / bound);
        return new Result(assignments, total, cost(candidates, choice), budget, lambda, bound, gap);
    }

    /** Each customer's best option at price lambda. Ties go to the cheaper option, which helps feasibility. */
    private static int[] choose(List<Candidate> candidates, double lambda) {
        int[] choice = new int[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            List<Option> options = candidates.get(i).options();
            int best = NONE;
            double bestScore = 0;
            for (int j = 0; j < options.size(); j++) {
                Option o = options.get(j);
                double score = o.value() - lambda * o.cost();
                if (score > bestScore || (score == bestScore && best != NONE && o.cost() < options.get(best).cost())) {
                    best = j;
                    bestScore = score;
                }
            }
            choice[i] = best;
        }
        return choice;
    }

    private static double dual(List<Candidate> candidates, double budget, double lambda) {
        double total = lambda * budget;
        for (Candidate c : candidates) {
            double best = 0;
            for (Option o : c.options()) {
                best = Math.max(best, o.value() - lambda * o.cost());
            }
            total += best;
        }
        return total;
    }

    private record Upgrade(int customer, int from, int to, double extraValue, double extraCost) {
    }

    /** Spends leftover budget on the upgrades that buy the most value per unit of extra cost. */
    private static void repair(List<Candidate> candidates, int[] choice, double leftover) {
        List<Upgrade> upgrades = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            List<Option> options = candidates.get(i).options();
            double curValue = choice[i] == NONE ? 0 : options.get(choice[i]).value();
            double curCost = choice[i] == NONE ? 0 : options.get(choice[i]).cost();
            for (int j = 0; j < options.size(); j++) {
                double dv = options.get(j).value() - curValue;
                double dc = options.get(j).cost() - curCost;
                if (j != choice[i] && dv > 0 && dc <= leftover) {
                    upgrades.add(new Upgrade(i, choice[i], j, dv, dc));
                }
            }
        }
        upgrades.sort(Comparator.comparingDouble((Upgrade u) -> u.extraValue() / Math.max(u.extraCost(), 1e-12)).reversed());
        for (Upgrade u : upgrades) {
            // An earlier upgrade may already have moved this customer, making this one stale.
            if (choice[u.customer()] == u.from() && u.extraCost() <= leftover) {
                choice[u.customer()] = u.to();
                leftover -= u.extraCost();
            }
        }
    }

    private static int[] bestSingle(List<Candidate> candidates, double budget) {
        int[] choice = new int[candidates.size()];
        Arrays.fill(choice, NONE);
        double bestValue = 0;
        int bestCustomer = NONE;
        int bestOption = NONE;
        for (int i = 0; i < candidates.size(); i++) {
            List<Option> options = candidates.get(i).options();
            for (int j = 0; j < options.size(); j++) {
                Option o = options.get(j);
                if (o.cost() <= budget && o.value() > bestValue) {
                    bestValue = o.value();
                    bestCustomer = i;
                    bestOption = j;
                }
            }
        }
        if (bestCustomer != NONE) {
            choice[bestCustomer] = bestOption;
        }
        return choice;
    }

    private static int[] betterOf(int[] a, int[] b, List<Candidate> candidates) {
        return value(candidates, b) > value(candidates, a) ? b : a;
    }

    private static double maxRatio(List<Candidate> candidates) {
        double max = 0;
        for (Candidate c : candidates) {
            for (Option o : c.options()) {
                if (o.cost() > 0) {
                    max = Math.max(max, o.value() / o.cost());
                }
            }
        }
        // Above this price no option with a cost is worth taking, so only free options remain, and they always fit.
        return max * 1.000001 + 1e-9;
    }

    private static double value(List<Candidate> candidates, int[] choice) {
        double total = 0;
        for (int i = 0; i < choice.length; i++) {
            if (choice[i] != NONE) {
                total += candidates.get(i).options().get(choice[i]).value();
            }
        }
        return total;
    }

    private static double cost(List<Candidate> candidates, int[] choice) {
        double total = 0;
        for (int i = 0; i < choice.length; i++) {
            if (choice[i] != NONE) {
                total += candidates.get(i).options().get(choice[i]).cost();
            }
        }
        return total;
    }

    private static void validate(List<Candidate> candidates, double budget) {
        if (!(budget >= 0) || Double.isInfinite(budget)) {
            throw new IllegalArgumentException("budget must be a finite number >= 0");
        }
        for (Candidate c : candidates) {
            if (c == null || c.customerId() == null || c.options() == null) {
                throw new IllegalArgumentException("every candidate needs a customerId and a list of options");
            }
            for (Option o : c.options()) {
                if (o == null) {
                    throw new IllegalArgumentException("null option for " + c.customerId());
                }
                if (!(o.cost() >= 0) || !Double.isFinite(o.cost()) || !Double.isFinite(o.value())) {
                    throw new IllegalArgumentException(
                            "offer " + o.offerId() + " for " + c.customerId() + " needs a finite value and a cost >= 0");
                }
            }
        }
    }
}
