package com.paymentledger.admin.repository;

import com.paymentledger.admin.domain.AdminAuditLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLogEntity, UUID> {

    Page<AdminAuditLogEntity> findByActorUserId(UUID actorUserId, Pageable pageable);

    Page<AdminAuditLogEntity> findByAction(String action, Pageable pageable);

    Page<AdminAuditLogEntity> findByResourceTypeAndResourceId(String resourceType, String resourceId, Pageable pageable);
}
