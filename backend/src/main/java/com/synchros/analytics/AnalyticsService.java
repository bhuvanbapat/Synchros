package com.synchros.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    private final AnalyticsRepository analyticsRepository;

    public AnalyticsService(AnalyticsRepository analyticsRepository) {
        this.analyticsRepository = analyticsRepository;
    }

    @Transactional
    public void recordReservationEvent(String eventType, String payload) {
        analyticsRepository.save(new AnalyticsEvent("RESERVATION", eventType, payload));
    }

    @Transactional
    public void recordOrderEvent(String eventType, String payload) {
        analyticsRepository.save(new AnalyticsEvent("ORDER", eventType, payload));
    }

    @Transactional
    public void recordPaymentEvent(String eventType, String payload) {
        analyticsRepository.save(new AnalyticsEvent("PAYMENT", eventType, payload));
    }
}
