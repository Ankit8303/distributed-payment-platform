package com.paymentledger.admin.service;

import com.paymentledger.admin.domain.AdminAuditLogEntity;
import com.paymentledger.admin.repository.AdminAuditLogRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AdminAuditService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditService.class);

    private static final List<Pattern> SENSITIVE_PATTERNS = List.of(
            Pattern.compile("(?i)(password|secret|token|jwt|cvv|cardnumber|privatekey)\\s*[:=]\\s*[^,\\s}]+"),
            Pattern.compile("Bearer\\s+[A-Za-z0-9._~+/-]+=*")
    );

    private final AdminAuditLogRepository auditLogRepository;
    private final MeterRegistry meterRegistry;

    @Autowired
    public AdminAuditService(AdminAuditLogRepository auditLogRepository,
                             @Autowired(required = false) MeterRegistry meterRegistry) {
        this.auditLogRepository = auditLogRepository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Records an immutable administrative audit event.
     * Executes within the caller's transaction if present, or creates a new transaction.
     */
    @Transactional
    public AdminAuditLogEntity recordAudit(UUID actorUserId,
                                           String actorRole,
                                           String action,
                                           String resourceType,
                                           String resourceId,
                                           String reason,
                                           String correlationId,
                                           String requestId,
                                           String beforeState,
                                           String afterState,
                                           String metadata) {

        String sanitizedReason = sanitizeSensitiveData(reason);
        String sanitizedMetadata = sanitizeSensitiveData(metadata);

        AdminAuditLogEntity auditLog = new AdminAuditLogEntity(
                UUID.randomUUID(),
                actorUserId,
                actorRole != null ? actorRole : "ROLE_ADMIN",
                action,
                resourceType,
                resourceId,
                sanitizedReason,
                correlationId,
                requestId,
                beforeState,
                afterState,
                sanitizedMetadata
        );

        AdminAuditLogEntity saved = auditLogRepository.save(auditLog);

        if (meterRegistry != null) {
            meterRegistry.counter("admin.operation.count", "action", action, "resource", resourceType).increment();
        }

        log.info("Recorded admin audit log: action={} resource={}/{} actor={} correlationId={}",
                action, resourceType, resourceId, actorUserId, correlationId);

        return saved;
    }

    @Transactional(readOnly = true)
    public Page<AdminAuditLogEntity> getAuditLogs(String action, String resourceType, String resourceId, Pageable pageable) {
        if (action != null && !action.isBlank()) {
            return auditLogRepository.findByAction(action, pageable);
        }
        if (resourceType != null && resourceId != null) {
            return auditLogRepository.findByResourceTypeAndResourceId(resourceType, resourceId, pageable);
        }
        return auditLogRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Optional<AdminAuditLogEntity> getAuditLogById(UUID id) {
        return auditLogRepository.findById(id);
    }

    private String sanitizeSensitiveData(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        String sanitized = input;
        for (Pattern pattern : SENSITIVE_PATTERNS) {
            sanitized = pattern.matcher(sanitized).replaceAll("[REDACTED]");
        }
        return sanitized;
    }
}
