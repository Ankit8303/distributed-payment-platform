package com.paymentledger.notification.api;

import com.paymentledger.notification.domain.NotificationDeliveryEntity;
import com.paymentledger.notification.domain.NotificationEntity;
import com.paymentledger.notification.domain.NotificationStatus;
import com.paymentledger.notification.repository.NotificationDeliveryRepository;
import com.paymentledger.notification.repository.NotificationRepository;
import com.paymentledger.notification.service.NotificationService;
import com.paymentledger.notification.worker.NotificationWorker;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/notifications")
public class AdminNotificationController {

    private final NotificationRepository notificationRepository;
    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationService notificationService;
    private final NotificationWorker notificationWorker;
    private final com.paymentledger.admin.service.AdminAuditService adminAuditService;

    public record NotificationDetailResponse(NotificationEntity notification, List<NotificationDeliveryEntity> deliveries) {}

    public AdminNotificationController(NotificationRepository notificationRepository,
                                       NotificationDeliveryRepository deliveryRepository,
                                       NotificationService notificationService,
                                       NotificationWorker notificationWorker,
                                       @org.springframework.beans.factory.annotation.Autowired(required = false) com.paymentledger.admin.service.AdminAuditService adminAuditService) {
        this.notificationRepository = notificationRepository;
        this.deliveryRepository = deliveryRepository;
        this.notificationService = notificationService;
        this.notificationWorker = notificationWorker;
        this.adminAuditService = adminAuditService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")
    public ResponseEntity<Page<NotificationEntity>> listNotifications(
            @RequestParam(required = false) NotificationStatus status,
            Pageable pageable) {
        Pageable clamped = com.paymentledger.admin.api.dto.PageUtils.clamp(pageable);
        if (status != null) {
            return ResponseEntity.ok(notificationRepository.findByStatus(status, clamped));
        }
        return ResponseEntity.ok(notificationRepository.findAll(clamped));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")
    public ResponseEntity<NotificationDetailResponse> getNotificationDetail(@PathVariable UUID id) {
        return notificationRepository.findById(id)
                .map(notif -> {
                    List<NotificationDeliveryEntity> deliveries = deliveryRepository.findByNotificationIdOrderByAttemptNumberAsc(id);
                    return ResponseEntity.ok(new NotificationDetailResponse(notif, deliveries));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/retry")
    @PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")
    public ResponseEntity<NotificationEntity> retryNotification(
            @PathVariable UUID id,
            org.springframework.security.core.Authentication authentication,
            jakarta.servlet.http.HttpServletRequest httpRequest) {

        NotificationEntity notif = notificationRepository.findById(id).orElse(null);
        String beforeState = notif != null ? notif.getStatus().name() : "UNKNOWN";

        NotificationEntity retried = notificationService.adminRetry(id, notificationWorker.getWorkerId());

        if (adminAuditService != null && authentication != null) {
            UUID actorId;
            try {
                actorId = UUID.fromString(authentication.getName());
            } catch (Exception e) {
                actorId = UUID.nameUUIDFromBytes(authentication.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            String actorRole = authentication.getAuthorities().stream()
                    .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                    .findFirst().orElse("ROLE_ADMIN");
            String correlationId = org.slf4j.MDC.get(com.paymentledger.shared.logging.CorrelationIdFilter.MDC_KEY);
            String requestId = httpRequest != null ? httpRequest.getHeader("X-Request-ID") : null;

            adminAuditService.recordAudit(
                    actorId,
                    actorRole,
                    "NOTIFICATION_RETRY",
                    "NOTIFICATION",
                    id.toString(),
                    "Administrative notification retry",
                    correlationId,
                    requestId,
                    beforeState,
                    retried.getStatus().name(),
                    null
            );
        }

        return ResponseEntity.ok(retried);
    }

    @PostMapping("/run-worker")
    @PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")
    public ResponseEntity<Integer> runWorkerBatch(@RequestParam(defaultValue = "25") int limit) {
        int processed = notificationWorker.processBatch(limit);
        return ResponseEntity.ok(processed);
    }
}
