package com.Synchros.reconciliation;

import com.Synchros.metrics.SynchrosMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled consistency sweep. Reconciliation used to be on-demand (admin
 * endpoint) only — a smoke detector nobody watched. Now it runs every
 * 5 minutes by default, and every finding increments
 * Synchros_reconciliation_findings plus flips
 * Synchros_reconciliation_consistent to 0 — alertable from Prometheus
 * without any human pressing the button.
 */
@Component
@ConditionalOnProperty(name = "Synchros.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final ReconciliationService reconciliationService;
    private final SynchrosMetrics metrics;

    public ReconciliationJob(ReconciliationService reconciliationService,
                              SynchrosMetrics metrics) {
        this.reconciliationService = reconciliationService;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${Synchros.reconciliation.interval-ms:300000}")
    public void run() {
        try {
            var report = reconciliationService.reconcile();
            metrics.reconciliationOutcome(report.findings().size());
            if (!report.consistent()) {
                log.warn("scheduled reconciliation found {} finding(s) — see /api/admin/reconciliation for detail",
                        report.findings().size());
            }
        } catch (Exception e) {
            // Never kill the scheduler loop; next run retries.
            log.error("scheduled reconciliation failed: {}", e.getMessage());
        }
    }
}
