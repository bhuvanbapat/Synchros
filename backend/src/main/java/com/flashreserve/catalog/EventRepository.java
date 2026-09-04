package com.flashreserve.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventRepository extends JpaRepository<Event, Long> {
    Optional<Event> findByPublicId(UUID publicId);

    List<Event> findAllByOrderByStartsAtAsc();
}
