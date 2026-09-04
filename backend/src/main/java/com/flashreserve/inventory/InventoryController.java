package com.flashreserve.inventory;

import com.flashreserve.cache.CatalogCache;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Inventory read endpoints. Availability is served through the cache-aside
 * wrapper with a short TTL — stale values are display hints only; the
 * reservation conditional UPDATE is the authority (docs/CONCURRENCY.md).
 */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryPoolRepository poolRepository;
    private final CatalogCache cache;

    public InventoryController(InventoryPoolRepository poolRepository, CatalogCache cache) {
        this.poolRepository = poolRepository;
        this.cache = cache;
    }

    @GetMapping
    public List<Map<String, Object>> byEvent(@RequestParam("eventId") Long eventId) {
        return poolRepository.findByEventId(eventId).stream()
                .map(this::view).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> byId(@PathVariable Long id) {
        return poolRepository.findById(id)
                .map(this::view)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Map<String, Object> view(InventoryPool p) {
        return Map.of(
                "id", p.getId(),
                "publicId", p.getPublicId().toString(),
                "eventId", p.getEventId(),
                "section", p.getSection(),
                "total", p.getTotal(),
                "available", cachedAvailable(p));
    }

    private int cachedAvailable(InventoryPool p) {
        return cache.getAvailability(p.getId())
                .map(s -> {
                    try {
                        return Integer.parseInt(s);
                    } catch (NumberFormatException e) {
                        return p.getAvailable();
                    }
                })
                .orElseGet(() -> {
                    cache.putAvailability(p.getId(), String.valueOf(p.getAvailable()));
                    return p.getAvailable();
                });
    }
}
