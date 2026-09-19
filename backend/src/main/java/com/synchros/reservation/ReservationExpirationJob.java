package com.synchros.reservation;

import com.synchros.config.SynchrosProperties;
import com.synchros.order.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Reservation TTL expiration job.
 *
 * Race with confirm(): expireOne() flips state with a conditional UPDATE
 * (expireIfHeld); confirm() takes the row lock and flips via the state
 * machine. Exactly one wins:
 *  - confirm commits first => expireIfHeld affects 0 rows => no release.
 *  - expire commits first => confirm's transitionTo(HELD->CONFIRMED)
 *    throws => confirm transaction rolls back => no confirmation of a
 *    released hold. The payment flow surfaces this as RESERVATION_EXPIRED.
 */
@Component
@ConditionalOnProperty(name = "synchros.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class ReservationExpirationJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpirationJob.class);

    private final ReservationService reservationService;
    private final OrderService orderService;
    private final SynchrosProperties props;

    public ReservationExpirationJob(ReservationService reservationService,
                                   OrderService orderService,
                                   SynchrosProperties props) {
        this.reservationService = reservationService;
        this.orderService = orderService;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${synchros.expiration.scan-interval-ms:1000}")
    public void expireStaleHolds() {
        Instant cutoff = Instant.now();
        List<Reservation> stale = reservationService.findExpiredHeld(
                cutoff, props.getExpiration().getBatchSize());
        for (Reservation r : stale) {
            try {
                boolean expired = reservationService.expireOne(r);
                if (expired) {
                    // Also mark any open order on this reservation EXPIRED.
                    orderService.expireIfPending(r.getId());
                }
            } catch (Exception e) {
                // One bad row must not kill the batch; retry next scan.
                log.warn("failed to expire reservation {}: {}", r.getPublicId(), e.getMessage());
            }
        }
        if (!stale.isEmpty()) {
            log.info("expiration scan: {} stale holds found", stale.size());
        }
    }
}
