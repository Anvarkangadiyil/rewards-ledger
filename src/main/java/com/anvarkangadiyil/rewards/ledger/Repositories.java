package com.anvarkangadiyil.rewards.ledger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface AccountRepository extends JpaRepository<Account, String> {

    /** SELECT ... FOR UPDATE on the account row, held until the surrounding transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.customerId = :customerId")
    Optional<Account> lock(@Param("customerId") String customerId);
}

interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    Optional<LedgerEntry> findByIdempotencyKey(String idempotencyKey);

    List<LedgerEntry> findByCustomerIdOrderByIdDesc(String customerId, Pageable page);
}

interface PointLotRepository extends JpaRepository<PointLot, Long> {

    /** Soonest-expiring first, with ties going to the older lot. This ordering is the whole spend policy. */
    @Query("""
            select l from PointLot l
            where l.customerId = :customerId and l.remainingPoints > 0 and l.expiresAt > :now
            order by l.expiresAt asc, l.id asc
            """)
    List<PointLot> spendable(@Param("customerId") String customerId, @Param("now") Instant now);

    @Query("""
            select l from PointLot l
            where l.customerId = :customerId and l.remainingPoints > 0 and l.expiresAt <= :now
            order by l.expiresAt asc, l.id asc
            """)
    List<PointLot> expired(@Param("customerId") String customerId, @Param("now") Instant now);

    @Query("""
            select coalesce(sum(l.remainingPoints), 0) from PointLot l
            where l.customerId = :customerId and l.remainingPoints > 0 and l.expiresAt > :now
            """)
    long spendableTotal(@Param("customerId") String customerId, @Param("now") Instant now);

    @Query("select distinct l.customerId from PointLot l where l.remainingPoints > 0 and l.expiresAt <= :now")
    List<String> customersWithExpiredPoints(@Param("now") Instant now);
}

interface LotAllocationRepository extends JpaRepository<LotAllocation, Long> {

    List<LotAllocation> findByEntryIdOrderById(Long entryId);
}
