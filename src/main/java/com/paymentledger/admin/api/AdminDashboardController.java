package com.paymentledger.admin.api;

import com.paymentledger.account.domain.AccountStatus;
import com.paymentledger.account.repository.AccountRepository;
import com.paymentledger.admin.api.dto.DashboardSummaryResponse;
import com.paymentledger.auth.repository.UserRepository;
import com.paymentledger.notification.repository.NotificationRepository;
import com.paymentledger.payment.domain.PaymentStatus;
import com.paymentledger.payment.repository.PaymentRepository;
import com.paymentledger.reconciliation.repository.ReconciliationCaseRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/dashboard")
@PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")
public class AdminDashboardController {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final PaymentRepository paymentRepository;
    private final ReconciliationCaseRepository reconciliationCaseRepository;
    private final NotificationRepository notificationRepository;

    public AdminDashboardController(UserRepository userRepository,
                                    AccountRepository accountRepository,
                                    PaymentRepository paymentRepository,
                                    ReconciliationCaseRepository reconciliationCaseRepository,
                                    NotificationRepository notificationRepository) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.paymentRepository = paymentRepository;
        this.reconciliationCaseRepository = reconciliationCaseRepository;
        this.notificationRepository = notificationRepository;
    }

    @GetMapping("/summary")
    public ResponseEntity<DashboardSummaryResponse> getSummary() {
        long totalUsers = userRepository.count();
        long totalAccounts = accountRepository.count();
        long activeAccounts = accountRepository.countByStatus(AccountStatus.ACTIVE);
        long frozenAccounts = accountRepository.countByStatus(AccountStatus.FROZEN);
        long totalPayments = paymentRepository.count();
        long settledPayments = paymentRepository.countByStatus(PaymentStatus.SETTLED);
        long failedPayments = paymentRepository.countByStatus(PaymentStatus.FAILED);
        long pendingReconPayments = paymentRepository.countByStatus(PaymentStatus.PENDING_RECONCILIATION);
        long openReconCases = reconciliationCaseRepository.count();
        long totalNotifications = notificationRepository.count();

        DashboardSummaryResponse summary = new DashboardSummaryResponse(
                totalUsers,
                totalAccounts,
                activeAccounts,
                frozenAccounts,
                totalPayments,
                settledPayments,
                failedPayments,
                pendingReconPayments,
                openReconCases,
                totalNotifications
        );

        return ResponseEntity.ok(summary);
    }
}
