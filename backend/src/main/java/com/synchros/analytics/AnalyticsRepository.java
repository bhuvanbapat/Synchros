package com.synchros.analytics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AnalyticsRepository extends JpaRepository<AnalyticsEvent, Long> {

    @Query("SELECT a.eventType, COUNT(a) FROM AnalyticsEvent a " +
           "WHERE a.eventCategory = :category GROUP BY a.eventType")
    List<Object[]> countByCategoryGroupedByType(String category);
}
