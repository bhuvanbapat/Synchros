package com.Synchros.common;

/**
 * Structured domain error carrying a stable machine-readable code
 * that maps to an HTTP status via the global exception handler.
 */
public class DomainException extends RuntimeException {

    private final ErrorCode code;

    public DomainException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }

    public enum ErrorCode {
        INVENTORY_UNAVAILABLE(409, "Requested inventory is no longer available"),
        EVENT_NOT_RESERVABLE(409, "Event is not open for reservations"),
        RESERVATION_EXPIRED(409, "The reservation hold has expired"),
        RESERVATION_ALREADY_CONFIRMED(409, "The reservation is already confirmed"),
        INVALID_STATE_TRANSITION(409, "Illegal state transition requested"),
        IDEMPOTENCY_CONFLICT(409, "Idempotency key was already used with a different request"),
        PAYMENT_FAILED(402, "Payment was declined"),
        PAYMENT_TIMEOUT(504, "Payment provider timed out"),
        RATE_LIMIT_EXCEEDED(429, "Too many requests; slow down"),
        UNAUTHORIZED(401, "Authentication required"),
        FORBIDDEN(403, "You do not have access to this resource"),
        WEBHOOK_SIGNATURE_INVALID(401, "Invalid or missing webhook signature"),
        INVALID_REQUEST(400, "Request validation failed"),
        NOT_FOUND(404, "Resource not found");

        private final int httpStatus;
        private final String defaultMessage;

        ErrorCode(int httpStatus, String defaultMessage) {
            this.httpStatus = httpStatus;
            this.defaultMessage = defaultMessage;
        }

        public int httpStatus() {
            return httpStatus;
        }

        public String defaultMessage() {
            return defaultMessage;
        }
    }
}
