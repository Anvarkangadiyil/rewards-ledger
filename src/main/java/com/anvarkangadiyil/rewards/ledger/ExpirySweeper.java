package com.anvarkangadiyil.rewards.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Moves lapsed points out of balances on a schedule. Correctness doesn't depend on it, because redeem
 * expires lazily and reads skip expired lots. Each customer is expired in its own transaction under the
 * account lock, so running this on several instances at once can't expire the same points twice.
 */
@Component
class ExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(ExpirySweeper.class);

    private final LedgerService ledger;

    ExpirySweeper(LedgerService ledger) {
        this.ledger = ledger;
    }

    @Scheduled(cron = "${rewards.expiry-cron}")
    int sweep() {
        int expired = 0;
        for (String customerId : ledger.customersWithExpiredPoints()) {
            try {
                if (ledger.expire(customerId).isPresent()) {
                    expired++;
                }
            } catch (RuntimeException e) {
                // One bad account shouldn't stop the rest; the next run retries it.
                log.warn("expiry failed for customer {}", customerId, e);
            }
        }
        if (expired > 0) {
            log.info("expired points for {} customers", expired);
        }
        return expired;
    }
}
