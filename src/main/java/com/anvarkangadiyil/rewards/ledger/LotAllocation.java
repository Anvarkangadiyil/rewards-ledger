package com.anvarkangadiyil.rewards.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Records that a REDEEM or EXPIRE entry took {@code points} out of one lot. */
@Entity
@Table(name = "lot_allocation")
public class LotAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entry_id", nullable = false)
    private Long entryId;

    @Column(name = "lot_id", nullable = false)
    private Long lotId;

    @Column(nullable = false)
    private long points;

    protected LotAllocation() {
    }

    public LotAllocation(Long entryId, Long lotId, long points) {
        this.entryId = entryId;
        this.lotId = lotId;
        this.points = points;
    }

    public Long getLotId() { return lotId; }
    public long getPoints() { return points; }
}
