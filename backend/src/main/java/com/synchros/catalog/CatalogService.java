package com.synchros.catalog;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class CatalogService {

    private final EventRepository eventRepository;

    public CatalogService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    public List<Event> listEvents() {
        return eventRepository.findAllByOrderByStartsAtAsc();
    }

    public Event getEvent(UUID publicId) {
        return eventRepository.findByPublicId(publicId)
                .orElseThrow(() -> new com.synchros.common.NotFoundException(
                        "Event not found: " + publicId));
    }

    public java.util.Optional<Event> findEventByDbId(Long dbId) {
        return eventRepository.findById(dbId);
    }
}
