package com.flashreserve.catalog;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final CatalogService catalogService;

    public EventController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    public List<EventDto> listEvents() {
        return catalogService.listEvents().stream().map(EventDto::from).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<EventDto> getEvent(@PathVariable UUID id) {
        return ResponseEntity.ok(EventDto.from(catalogService.getEvent(id)));
    }
}
