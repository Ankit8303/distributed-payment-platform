package com.paymentledger.notification.worker;

import com.paymentledger.notification.domain.NotificationEntity;
import com.paymentledger.notification.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Component
public class NotificationWorker {

    private static final Logger log = LoggerFactory.getLogger(NotificationWorker.class);

    private final NotificationService notificationService;
    private final String workerId;
    private static final Duration LEASE_DURATION = Duration.ofSeconds(60);
    private static final int BATCH_SIZE = 25;

    public NotificationWorker(NotificationService notificationService) {
        this.notificationService = notificationService;
        this.workerId = "worker-notif-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 5000)
    public void runScheduledCycle() {
        processBatch(BATCH_SIZE);
    }

    public int processBatch(int batchSize) {
        List<NotificationEntity> claimed = notificationService.claimNotifications(workerId, batchSize, LEASE_DURATION);
        if (claimed.isEmpty()) {
            return 0;
        }

        log.info("Worker {} claimed {} notifications for delivery", workerId, claimed.size());
        for (NotificationEntity entity : claimed) {
            try {
                notificationService.deliverNotification(entity.getId(), workerId);
            } catch (Exception e) {
                log.error("Worker {} encountered unexpected error delivering notification {}: {}",
                        workerId, entity.getId(), e.getMessage(), e);
            }
        }
        return claimed.size();
    }

    public String getWorkerId() {
        return workerId;
    }
}
