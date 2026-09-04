package com.flashreserve.audit;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Append-only audit trail. Uses REQUIRES_NEW so a failed business mutation
 * can still be recorded when called explicitly with FAILURE, while SUCCESS
 * entries written inside the caller's transaction commit with it (default
 * path — audit of committed mutations is what auditors want).
 */
@Service
public class AuditService {

    private final AuditRepository auditRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditRepository auditRepository, ObjectMapper objectMapper) {
        this.auditRepository = auditRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String actor, String operation, String entityType,
                       String entityId, Map<String, Object> details) {
        record(actor, operation, entityType, entityId, "SUCCESS", details);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordNew(String actor, String operation, String entityType,
                          String entityId, String result, Map<String, Object> details) {
        record(actor, operation, entityType, entityId, result, details);
    }

    void record(String actor, String operation, String entityType,
                String entityId, String result, Map<String, Object> details) {
        String detailsJson;
        try {
            detailsJson = details == null || details.isEmpty()
                    ? null : objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            detailsJson = "{}";
        }
        auditRepository.save(new AuditEvent(actor, operation, entityType, entityId,
                result, MDC.get("requestId"), detailsJson));
    }
}
