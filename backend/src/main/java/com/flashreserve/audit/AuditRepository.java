package com.flashreserve.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface AuditRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findTop200ByOrderByCreatedAtDesc();

    List<AuditEvent> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType,
                                                                     String entityId);
}
