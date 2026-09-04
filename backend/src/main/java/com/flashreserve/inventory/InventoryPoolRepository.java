package com.flashreserve.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InventoryPoolRepository extends JpaRepository<InventoryPool, Long> {

    List<InventoryPool> findByEventId(Long eventId);

    Optional<InventoryPool> findByEventIdAndSection(Long eventId, String section);

    /**
     * Atomic conditional decrement — the oversell-prevention primitive.
     * The WHERE clause makes decrement-and-check a single atomic statement:
     * under concurrent execution, PostgreSQL serializes the updates on the
     * row lock, and any transaction whose predicate is no longer satisfied
     * (available dropped below qty) updates 0 rows and fails.
     * Returns 1 on success, 0 when inventory is insufficient.
     */
    @Modifying
    @Query("UPDATE InventoryPool p SET p.available = p.available - :qty " +
           "WHERE p.id = :id AND p.available >= :qty")
    int decrementAvailable(@Param("id") Long id, @Param("qty") int qty);

    /** Atomic increment used by expiration/cancellation release paths. */
    @Modifying
    @Query("UPDATE InventoryPool p SET p.available = p.available + :qty " +
           "WHERE p.id = :id")
    int incrementAvailable(@Param("id") Long id, @Param("qty") int qty);
}
