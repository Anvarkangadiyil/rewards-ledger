package com.anvarkangadiyil.rewards.web;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.anvarkangadiyil.rewards.ledger.LedgerService;
import com.anvarkangadiyil.rewards.ledger.LedgerViews.Balance;
import com.anvarkangadiyil.rewards.ledger.LedgerViews.Entry;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/accounts/{customerId}")
@Validated
class LedgerController {

    private static final String CUSTOMER_ID = "[A-Za-z0-9_-]{1,64}";

    record EarnRequest(@Positive @Max(1_000_000_000) long points,
                       @Min(1) @Max(3650) Integer ttlDays,
                       @Size(max = 128) String reference) {
    }

    record RedeemRequest(@Positive @Max(1_000_000_000) long points,
                         @Size(max = 128) String reference) {
    }

    private final LedgerService ledger;

    LedgerController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @PostMapping("/earn")
    ResponseEntity<Entry> earn(@PathVariable @Pattern(regexp = CUSTOMER_ID) String customerId,
                               @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
                               @Valid @RequestBody EarnRequest body) {
        return respond(ledger.earn(customerId, body.points(), body.ttlDays(), body.reference(), key));
    }

    @PostMapping("/redeem")
    ResponseEntity<Entry> redeem(@PathVariable @Pattern(regexp = CUSTOMER_ID) String customerId,
                                 @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
                                 @Valid @RequestBody RedeemRequest body) {
        return respond(ledger.redeem(customerId, body.points(), body.reference(), key));
    }

    @GetMapping("/balance")
    Balance balance(@PathVariable @Pattern(regexp = CUSTOMER_ID) String customerId) {
        return ledger.balance(customerId);
    }

    @GetMapping("/entries")
    List<Entry> entries(@PathVariable @Pattern(regexp = CUSTOMER_ID) String customerId,
                        @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        return ledger.history(customerId, limit);
    }

    /** 201 the first time, 200 on a replay, so a client can tell whether its retry did anything. */
    private static ResponseEntity<Entry> respond(Entry entry) {
        return ResponseEntity.status(entry.replayed() ? 200 : 201)
                .header("Idempotent-Replayed", String.valueOf(entry.replayed()))
                .body(entry);
    }
}
