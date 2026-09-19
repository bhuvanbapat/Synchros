package com.synchros.user;

import com.synchros.audit.AuditService;
import com.synchros.common.DomainException;
import com.synchros.common.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin account-state management. The account_state CHECK constraint and
 * per-request principal reload existed since V1, but no code path could
 * ever set SUSPENDED — the control existed, the feature didn't. Suspension
 * takes effect immediately: every request re-loads the principal from the
 * DB (JwtAuthFilter), so a suspended user's next call 401s even with a
 * live token.
 */
@Service
public class AccountAdminService {

    private final UserRepository userRepository;
    private final AuditService auditService;

    public AccountAdminService(UserRepository userRepository, AuditService auditService) {
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional
    public User setState(String userPublicId, String targetState, Long adminUserId) {
        if (!"SUSPENDED".equals(targetState) && !"ACTIVE".equals(targetState)) {
            throw new DomainException(DomainException.ErrorCode.INVALID_REQUEST,
                    "account_state must be ACTIVE or SUSPENDED");
        }
        User user = userRepository.findByPublicId(java.util.UUID.fromString(userPublicId))
                .orElseThrow(() -> new NotFoundException("User not found: " + userPublicId));

        if (user.getId().equals(adminUserId)) {
            throw new DomainException(DomainException.ErrorCode.INVALID_REQUEST,
                    "Admins cannot suspend or reactivate their own account");
        }

        // Package-private setters keep the entity closed to the rest of
        // the codebase; this service lives in the same package on purpose.
        user.setAccountState(targetState);

        auditService.record("admin:" + adminUserId,
                "SUSPENDED".equals(targetState) ? "USER_SUSPENDED" : "USER_ACTIVATED",
                "user", user.getPublicId().toString(), java.util.Map.of());
        return user;
    }
}
