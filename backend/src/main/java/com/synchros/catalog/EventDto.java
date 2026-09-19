package com.Synchros.catalog;

import java.time.Instant;
import java.util.UUID;

public record EventDto(
        UUID id,
        String name,
        String description,
        Instant startsAt,
        String state) {

    public static EventDto from(Event e) {
        return new EventDto(e.getPublicId(), e.getName(), e.getDescription(),
                e.getStartsAt(), e.getState());
    }
}
