package com.paymentledger.reconciliation.worker;

import com.paymentledger.reconciliation.domain.ReconciliationCaseEntity;
import com.paymentledger.reconciliation.service.ReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Component
public class ReconciliationWorker {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationWorker.class);

    private final ReconciliationService reconciliationService;
    private final String workerId;
    private final int batchSize;
    private final Duration leaseDuration;
    private final boolean scheduledEnabled;

    public ReconciliationWorker(ReconciliationService reconciliationService,
                                @Value("${app.reconciliation.batch-size:10}") int batchSize,
                                @Value("${app.reconciliation.lease-seconds:60}") int leaseSeconds,
                                @Value("${app.reconciliation.scheduled-enabled:false}") boolean scheduledEnabled) {
        this.reconciliationService = reconciliationService;
        this.workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
        this.batchSize = batchSize;
        this.leaseDuration = Duration.ofSeconds(leaseSeconds);
        this.scheduledEnabled = scheduledEnabled;
    }

    @Scheduled(fixedDelayString = "${app.reconciliation.poll-interval-ms:5000}")
    public void scheduledRun() {
        if (scheduledEnabled) {
            runReconciliationCycle();
        }
    }

    public int runReconciliationCycle() {
        try {
            // 1. Ingest candidates
            reconciliationService.scanAndEnrolCandidates();

            // 2. Claim batch of cases
            List<ReconciliationCaseEntity> claimed = reconciliationService.claimCases(workerId, batchSize, leaseDuration);
            if (claimed.isEmpty()) {
                return 0;
            }

            log.info("Reconciliation worker {} claimed {} cases", workerId, claimed.size());

            // 3. Process each claimed case
            for (ReconciliationCaseEntity reconCase : claimed) {
                try {
                    reconciliationService.reconcileCase(reconCase.getId(), workerId);
                } catch (Exception ex) {
                    log.error("Failed to process reconciliation case {}: {}", reconCase.getId(), ex.getMessage());
                }
            }

            return claimed.size();
        } catch (Exception ex) {
            log.error("Error running reconciliation cycle: {}", ex.getMessage());
            return 0;
        }
    }

    public String getWorkerId() { return workerId; }
}
