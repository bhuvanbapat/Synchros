package com.synchros.inventory;

import com.synchros.cache.CatalogCache;
import com.synchros.catalog.CatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Inventory read endpoints. Availability is served through the cache-aside
 * wrapper with a short TTL — stale values are display hints only; the
 * reservation conditional UPDATE is the authority (docs/CONCURRENCY.md).
 * Endpoints address pools by PUBLIC UUID (no sequential DB ids leak).
 */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryPoolRepository poolRepository;
    private final CatalogCache cache;
    private final CatalogService catalogService;

    public InventoryController(InventoryPoolRepository poolRepository,
                               CatalogCache cache,
                               CatalogService catalogService) {
        this.poolRepository = poolRepository;
        this.cache = cache;
        this.catalogService = catalogService;
    }

    @GetMapping
    public List<Map<String, Object>> byEvent(@RequestParam("eventId") UUID eventPublicId) {
        Long eventId = catalogService.getEvent(eventPublicId).getId();
        return poolRepository.findByEventId(eventId).stream()
                .map(this::view).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> byId(@PathVariable UUID id) {
        return poolRepository.findByPublicId(id)
                .map(this::view)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Map<String, Object> view(InventoryPool p) {
        Map<String, Object> v = new HashMap<>();
        v.put("id", p.getPublicId().toString());
        v.put("section", p.getSection());
        v.put("total", p.getTotal());
        v.put("available", cachedAvailable(p));
        return v;
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
