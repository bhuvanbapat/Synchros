package com.synchros.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Event-state gate regression: CANCELLED / SOLD_OUT / CONCLUDED events
 * and pre-sales-window events must refuse reservations with a clean
 * EVENT_NOT_RESERVABLE 409 — previously the engine ignored event state
 * entirely (found in the honest-limitations audit).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class EventStateGateIT extends PostgresIntegrationBase {

    @Autowired com.synchros.reservation.ReservationService reservations;
    @Autowired com.synchros.catalog.CatalogService catalogService;

    private com.synchros.catalog.Event newEvent(String state, Instant onSaleAt) {
        com.synchros.catalog.Event e = new com.synchros.catalog.Event();
        set(e, "venueId", 1L);
        set(e, "name", "gate-test-" + UUID.randomUUID());
        set(e, "startsAt", Instant.now().plusSeconds(86_400));
        set(e, "onSaleAt", onSaleAt);
        set(e, "state", state);
        return e;
    }
    private static void set(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException ro) {
            throw new IllegalStateException(ro);
        }
    }

    @Test
    void cancelledEventRefusesReservations() {
        var ex = assertThrows(com.synchros.common.DomainException.class,
                () -> reservations.create(1L, newEvent("CANCELLED", Instant.now().minusSeconds(3600)),
                        "FLOOR", 1));
        assertEquals(com.synchros.common.DomainException.ErrorCode.EVENT_NOT_RESERVABLE, ex.getCode());
    }

    @Test
    void soldOutEventRefusesReservations() {
        assertThrows(com.synchros.common.DomainException.class,
                () -> reservations.create(1L, newEvent("SOLD_OUT", Instant.now().minusSeconds(3600)),
                        "FLOOR", 1));
    }

    @Test
    void concludedEventRefusesReservations() {
        assertThrows(com.synchros.common.DomainException.class,
                () -> reservations.create(1L, newEvent("CONCLUDED", Instant.now().minusSeconds(3600)),
                        "FLOOR", 1));
    }

    @Test
    void futureOnSaleAtRefusesReservations() {
        var ex = assertThrows(com.synchros.common.DomainException.class,
                () -> reservations.create(1L, newEvent("ON_SALE", Instant.now().plusSeconds(3600)),
                        "FLOOR", 1));
        assertEquals(com.synchros.common.DomainException.ErrorCode.EVENT_NOT_RESERVABLE, ex.getCode());
    }

    @Test
    void scheduledOnSaleEventStillReserves() {
        // Happy path must not regress: the V2-seeded event is ON_SALE with
        // its sales window open and a real FLOOR pool — it must reserve.
        com.synchros.catalog.Event seeded =
                catalogService.findEventByDbId(1L).orElseThrow();
        var reservation = reservations.create(2L, seeded, "FLOOR", 1);
        assertNotNull(reservation.getPublicId());
        assertEquals(com.synchros.reservation.Reservation.State.HELD,
                reservation.getState());
    }
}
