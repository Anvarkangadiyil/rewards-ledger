package com.anvarkangadiyil.rewards.ledger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.anvarkangadiyil.rewards.MutableClock;

@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClock.Config.class)
class LedgerApiTest {

    @Autowired MockMvc mvc;

    private final String customer = "api-" + UUID.randomUUID().toString().substring(0, 8);

    @Test
    void firstRequestIs201AndRetryIs200WithSameBody() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"points\": 250, \"reference\": \"order-42\"}";

        mvc.perform(post("/api/v1/accounts/{c}/earn", customer).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.balanceAfter").value(250));

        mvc.perform(post("/api/v1/accounts/{c}/earn", customer).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.balanceAfter").value(250));

        mvc.perform(get("/api/v1/accounts/{c}/balance", customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spendable").value(250));
    }

    @Test
    void errorsMapToProblemResponses() throws Exception {
        mvc.perform(post("/api/v1/accounts/{c}/earn", customer).header("Idempotency-Key", "k-" + customer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"points\": 10}"))
                .andExpect(status().isCreated());

        // Overdraw: 422 with the numbers a client needs to show a useful message.
        mvc.perform(post("/api/v1/accounts/{c}/redeem", customer).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"points\": 11}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.available").value(10));

        // Key reused for a different operation.
        mvc.perform(post("/api/v1/accounts/{c}/redeem", customer).header("Idempotency-Key", "k-" + customer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"points\": 1}"))
                .andExpect(status().isConflict());

        // Missing key.
        mvc.perform(post("/api/v1/accounts/{c}/redeem", customer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"points\": 1}"))
                .andExpect(status().isBadRequest());

        // Invalid amount.
        mvc.perform(post("/api/v1/accounts/{c}/earn", customer).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"points\": -5}"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/v1/accounts/{c}/balance", "nobody"))
                .andExpect(status().isNotFound());
    }
}
