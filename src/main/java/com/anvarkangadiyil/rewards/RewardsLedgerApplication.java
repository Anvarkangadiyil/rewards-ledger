package com.anvarkangadiyil.rewards;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RewardsLedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(RewardsLedgerApplication.class, args);
    }

    // Injected everywhere "now" matters, so tests can move time forward to exercise expiry.
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
