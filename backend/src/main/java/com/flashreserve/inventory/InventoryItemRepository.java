package com.flashreserve.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, Long> {
    List<InventoryItem> findByEventIdAndSection(Long eventId, String section);
    List<InventoryItem> findByEventId(Long eventId);
    Optional<InventoryItem> findByPublicId(UUID publicId);
}
