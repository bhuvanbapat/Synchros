package com.synchros.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findTop200ByOrderByCreatedAtDesc();
}
