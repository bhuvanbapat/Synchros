package com.Synchros.reservation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public class ReservationDtos {

    public record CreateReservationRequest(
            @NotNull UUID eventId,
            @NotBlank String section,
            @Min(1) @Max(10) int quantity) {
    }

    public record ReservationResponse(
            UUID id,
            UUID eventId,
            String section,
            int quantity,
            String state,
            String createdAt,
            String holdExpiresAt) {

        public static ReservationResponse from(Reservation r, UUID eventPublicId) {
            return new ReservationResponse(r.getPublicId(), eventPublicId,
                    r.getSection(), r.getQuantity(), r.getState().name(),
                    r.getCreatedAt().toString(), r.getHoldExpiresAt().toString());
        }
    }
}
