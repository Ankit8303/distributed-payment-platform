package com.paymentledger.admin.api;

import com.paymentledger.admin.api.dto.AdminAuditLogResponse;
import com.paymentledger.admin.api.dto.PageUtils;
import com.paymentledger.admin.domain.AdminAuditLogEntity;
import com.paymentledger.admin.service.AdminAuditService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")
public class AdminAuditController {

    private final AdminAuditService adminAuditService;

    public AdminAuditController(AdminAuditService adminAuditService) {
        this.adminAuditService = adminAuditService;
    }

    @GetMapping
    public ResponseEntity<Page<AdminAuditLogResponse>> listAuditLogs(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            Pageable pageable) {

        Pageable clamped = PageUtils.clamp(pageable);
        Page<AdminAuditLogEntity> page = adminAuditService.getAuditLogs(action, resourceType, resourceId, clamped);
        return ResponseEntity.ok(page.map(AdminAuditLogResponse::fromEntity));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AdminAuditLogResponse> getAuditLog(@PathVariable UUID id) {
        AdminAuditLogEntity logEntity = adminAuditService.getAuditLogById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Audit log not found: " + id));
        return ResponseEntity.ok(AdminAuditLogResponse.fromEntity(logEntity));
    }
}
