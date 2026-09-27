package com.paymentledger.account.service;

import com.paymentledger.account.domain.AccountEntity;
import com.paymentledger.account.domain.AccountStatus;
import com.paymentledger.account.domain.AccountType;
import com.paymentledger.account.exception.AccountDomainException;
import com.paymentledger.account.exception.AccountNotFoundException;
import com.paymentledger.account.repository.AccountRepository;
import com.paymentledger.messaging.config.TopicNames;
import com.paymentledger.messaging.event.AccountFrozenEventPayload;
import com.paymentledger.messaging.event.AccountUnfrozenEventPayload;
import com.paymentledger.messaging.event.EventEnvelope;
import com.paymentledger.messaging.producer.EventPublisher;
import com.paymentledger.outbox.service.OutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;
    private final EventPublisher eventPublisher;
    private final OutboxService outboxService;
    private final com.paymentledger.shared.redis.AccountReadCacheService accountReadCacheService;
    private final com.paymentledger.admin.service.AdminAuditService adminAuditService;
    private static final AtomicLong accountCounter = new AtomicLong(System.currentTimeMillis());

    public AccountService(AccountRepository accountRepository) {
        this(accountRepository, null, null, null, null);
    }

    public AccountService(AccountRepository accountRepository, EventPublisher eventPublisher) {
        this(accountRepository, eventPublisher, null, null, null);
    }

    public AccountService(AccountRepository accountRepository,
                          EventPublisher eventPublisher,
                          OutboxService outboxService) {
        this(accountRepository, eventPublisher, outboxService, null, null);
    }

    public AccountService(AccountRepository accountRepository,
                          EventPublisher eventPublisher,
                          OutboxService outboxService,
                          com.paymentledger.shared.redis.AccountReadCacheService accountReadCacheService) {
        this(accountRepository, eventPublisher, outboxService, accountReadCacheService, null);
    }

    @Autowired
    public AccountService(AccountRepository accountRepository,
                          @Autowired(required = false) EventPublisher eventPublisher,
                          @Autowired(required = false) OutboxService outboxService,
                          @Autowired(required = false) com.paymentledger.shared.redis.AccountReadCacheService accountReadCacheService,
                          @Autowired(required = false) com.paymentledger.admin.service.AdminAuditService adminAuditService) {
        this.accountRepository = accountRepository;
        this.eventPublisher = eventPublisher;
        this.outboxService = outboxService;
        this.accountReadCacheService = accountReadCacheService;
        this.adminAuditService = adminAuditService;
    }

    @Transactional
    public AccountEntity createAccount(UUID ownerId, AccountType type, String currency, String userRole) {
        // Validate user role vs requested account type
        if ("CUSTOMER".equals(userRole) && type != AccountType.CUSTOMER) {
            throw new AccountDomainException("Customers can only create CUSTOMER accounts");
        }
        if ("MERCHANT".equals(userRole) && type != AccountType.MERCHANT) {
            throw new AccountDomainException("Merchants can only create MERCHANT accounts");
        }
        
        // System and Fees accounts can only be created by SYSTEM/ADMIN
        if (type == AccountType.FEES || type == AccountType.INTERNAL_SETTLEMENT || type == AccountType.ESCROW) {
            if (!"ADMIN".equals(userRole) && !"SYSTEM".equals(userRole)) {
                throw new AccountDomainException("Platform-controlled accounts cannot be created by ordinary users");
            }
        }

        // Generate a pseudo-random unique account number (in real system, this would follow a strict format)
        String accountNumber = "ACC-" + currency.toUpperCase() + "-" + (accountCounter.incrementAndGet() % 1000000);
        
        AccountStatus initialStatus = AccountStatus.ACTIVE;
        if (type == AccountType.MERCHANT) {
            initialStatus = AccountStatus.PENDING_VERIFICATION; // Common for merchants
        }

        AccountEntity account = new AccountEntity(accountNumber, ownerId, type, currency.toUpperCase(), initialStatus);
        return accountRepository.save(account);
    }

    @Transactional(readOnly = true)
    public AccountEntity getAccount(UUID accountId, UUID ownerId, String userRole) {
        if (accountReadCacheService != null) {
            java.util.Optional<com.paymentledger.shared.redis.CachedAccountDto> cached = accountReadCacheService.get(accountId);
            if (cached.isPresent()) {
                com.paymentledger.shared.redis.CachedAccountDto dto = cached.get();
                if (!"ADMIN".equals(userRole) && !"SYSTEM".equals(userRole)) {
                    if (!dto.getOwnerId().equals(ownerId)) {
                        throw new AccountNotFoundException("Account not found or access denied");
                    }
                }
                return AccountEntity.fromCached(
                        dto.getId(),
                        dto.getAccountNumber(),
                        dto.getOwnerId(),
                        dto.getAccountType(),
                        dto.getCurrency(),
                        dto.getStatus(),
                        dto.getMaterializedBalanceMinor(),
                        dto.getVersion(),
                        dto.getCreatedAt(),
                        dto.getUpdatedAt()
                );
            }
        }

        AccountEntity account;
        if ("ADMIN".equals(userRole) || "SYSTEM".equals(userRole)) {
            account = accountRepository.findById(accountId)
                    .orElseThrow(() -> new AccountNotFoundException("Account not found"));
        } else {
            account = accountRepository.findByIdAndOwnerId(accountId, ownerId)
                    .orElseThrow(() -> new AccountNotFoundException("Account not found or access denied"));
        }

        if (accountReadCacheService != null) {
            accountReadCacheService.put(account);
        }

        return account;
    }

    @Transactional(readOnly = true)
    public Page<AccountEntity> getAccounts(UUID ownerId, Pageable pageable) {
        return accountRepository.findByOwnerId(ownerId, pageable);
    }

    @Transactional
    public void freezeAccount(UUID accountId, String reason) {
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found"));
        account.freeze();
        accountRepository.save(account);

        if (accountReadCacheService != null) {
            accountReadCacheService.evict(accountId);
        }

        publishAccountEvent(EventEnvelope.create(
                "AccountFrozen",
                "ACCOUNT",
                accountId.toString(),
                UUID.randomUUID(),
                "cmd_freeze",
                new AccountFrozenEventPayload(accountId, account.getOwnerId(), reason, Instant.now())
        ), accountId);
    }

    @Transactional
    public void unfreezeAccount(UUID accountId, String reason) {
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found"));
        account.unfreeze();
        accountRepository.save(account);

        if (accountReadCacheService != null) {
            accountReadCacheService.evict(accountId);
        }

        publishAccountEvent(EventEnvelope.create(
                "AccountUnfrozen",
                "ACCOUNT",
                accountId.toString(),
                UUID.randomUUID(),
                "cmd_unfreeze",
                new AccountUnfrozenEventPayload(accountId, account.getOwnerId(), reason, Instant.now())
        ), accountId);
    }

    @Transactional
    public AccountEntity adminFreezeAccount(UUID accountId, String reason, UUID actorId, String actorRole, String correlationId, String requestId) {
        AccountEntity account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));

        String beforeState = account.getStatus().name();
        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new AccountDomainException("Cannot freeze a closed account");
        }

        if (account.getStatus() != AccountStatus.FROZEN) {
            account.freeze();
            accountRepository.save(account);

            if (accountReadCacheService != null) {
                accountReadCacheService.evict(accountId);
            }

            UUID corrUuid = safeParseUuid(correlationId);
            publishAccountEvent(EventEnvelope.create(
                    "AccountFrozen",
                    "ACCOUNT",
                    accountId.toString(),
                    corrUuid,
                    "cmd_admin_freeze",
                    new AccountFrozenEventPayload(accountId, account.getOwnerId(), reason, Instant.now())
            ), accountId);
        }

        if (adminAuditService != null) {
            adminAuditService.recordAudit(
                    actorId,
                    actorRole,
                    "ACCOUNT_FREEZE",
                    "ACCOUNT",
                    accountId.toString(),
                    reason,
                    correlationId,
                    requestId,
                    beforeState,
                    account.getStatus().name(),
                    null
            );
        }

        return account;
    }

    @Transactional
    public AccountEntity adminUnfreezeAccount(UUID accountId, String reason, UUID actorId, String actorRole, String correlationId, String requestId) {
        AccountEntity account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));

        String beforeState = account.getStatus().name();
        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new AccountDomainException("Cannot unfreeze a closed account");
        }

        if (account.getStatus() != AccountStatus.ACTIVE) {
            if (account.getStatus() != AccountStatus.FROZEN) {
                throw new AccountDomainException("Account is not frozen; current status: " + account.getStatus());
            }
            account.unfreeze();
            accountRepository.save(account);

            if (accountReadCacheService != null) {
                accountReadCacheService.evict(accountId);
            }

            UUID corrUuid = safeParseUuid(correlationId);
            publishAccountEvent(EventEnvelope.create(
                    "AccountUnfrozen",
                    "ACCOUNT",
                    accountId.toString(),
                    corrUuid,
                    "cmd_admin_unfreeze",
                    new AccountUnfrozenEventPayload(accountId, account.getOwnerId(), reason, Instant.now())
            ), accountId);
        }

        if (adminAuditService != null) {
            adminAuditService.recordAudit(
                    actorId,
                    actorRole,
                    "ACCOUNT_UNFREEZE",
                    "ACCOUNT",
                    accountId.toString(),
                    reason,
                    correlationId,
                    requestId,
                    beforeState,
                    account.getStatus().name(),
                    null
            );
        }

        return account;
    }

    private UUID safeParseUuid(String input) {
        if (input == null || input.isBlank()) {
            return UUID.randomUUID();
        }
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private void publishAccountEvent(EventEnvelope<?> envelope, UUID accountId) {
        if (outboxService != null) {
            try {
                outboxService.saveEvent(
                        envelope.aggregateType(),
                        envelope.aggregateId(),
                        envelope.eventType(),
                        TopicNames.ACCOUNT_EVENTS,
                        accountId.toString(),
                        envelope.correlationId(),
                        envelope.causationId(),
                        envelope.payload()
                );
                return;
            } catch (Exception ex) {
                log.error("Failed to persist outbox event for account {} [{}]: {}", accountId, envelope.eventType(), ex.getMessage(), ex);
                throw ex;
            }
        }

        if (eventPublisher == null) {
            return;
        }
        try {
            eventPublisher.publish(TopicNames.ACCOUNT_EVENTS, accountId.toString(), envelope);
        } catch (Exception ex) {
            log.warn("Direct Kafka publication failed for account event {} [{}] on topic {}: {}. DB state remains authoritative.",
                    envelope.eventId(), envelope.eventType(), TopicNames.ACCOUNT_EVENTS, ex.getMessage());
        }
    }
}
