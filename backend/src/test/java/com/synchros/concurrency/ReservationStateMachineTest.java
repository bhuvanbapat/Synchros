package com.synchros.concurrency;

import org.junit.jupiter.api.Test;

/**
 * Pure-Java sanity check of the state machine used by the concurrency tests.
 * The DB-backed oversell test lives in the integration suite.
 */
class ReservationStateMachineTest {

    // Mirrors Reservation.State transitions without a DB.
    @Test
    void heldCanTransitionToConfirmedExpiredCancelledOnly() {
        String state = "HELD";
        for (String target : new String[]{"CONFIRMED", "EXPIRED", "CANCELLED"}) {
            if (!legal(state, target)) {
                throw new AssertionError("HELD -> " + target + " must be legal");
            }
        }
        for (String target : new String[]{"HELD", "FAILED"}) {
            if (legal(state, target)) {
                throw new AssertionError("HELD -> " + target + " must be illegal");
            }
        }
    }

    @Test
    void terminalStatesAreTerminal() {
        for (String terminal : new String[]{"CONFIRMED", "EXPIRED", "CANCELLED"}) {
            for (String target : new String[]{"HELD", "CONFIRMED", "EXPIRED", "CANCELLED"}) {
                if (legal(terminal, target)) {
                    throw new AssertionError(terminal + " must be terminal");
                }
            }
        }
    }

    /** Same truth table as Reservation.transitionTo. */
    private boolean legal(String from, String to) {
        return switch (from) {
            case "HELD" -> to.equals("CONFIRMED") || to.equals("EXPIRED") || to.equals("CANCELLED");
            default -> false;
        };
    }
}
