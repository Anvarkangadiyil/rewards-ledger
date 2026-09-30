package com.anvarkangadiyil.rewards.ledger;

import java.time.Clock;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates an account row in its own transaction. Two first-ever earns for the same customer can race
 * here, and the loser hits the primary key. That failure has to happen in a separate transaction:
 * inside the caller's transaction it would mark the whole earn rollback-only.
 */
@Component
class AccountProvisioner {

    private final AccountRepository accounts;
    private final Clock clock;

    AccountProvisioner(AccountRepository accounts, Clock clock) {
        this.accounts = accounts;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createIfMissing(String customerId) {
        if (!accounts.existsById(customerId)) {
            accounts.saveAndFlush(new Account(customerId, clock.instant()));
        }
    }
}
