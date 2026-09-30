package com.anvarkangadiyil.rewards.web;

import java.util.List;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.anvarkangadiyil.rewards.offers.OfferAllocator;
import com.anvarkangadiyil.rewards.offers.OfferAllocator.Candidate;
import com.anvarkangadiyil.rewards.offers.OfferAllocator.Result;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/offers")
class OfferController {

    record AllocateRequest(@NotNull @PositiveOrZero Double budget,
                           @NotEmpty @Size(max = 50_000) List<@Valid @NotNull Candidate> candidates) {
    }

    private final OfferAllocator allocator;

    OfferController(OfferAllocator allocator) {
        this.allocator = allocator;
    }

    @PostMapping("/allocate")
    Result allocate(@Valid @RequestBody AllocateRequest body) {
        return allocator.allocate(body.candidates(), body.budget());
    }
}
