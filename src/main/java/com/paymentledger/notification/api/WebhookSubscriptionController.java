package com.paymentledger.notification.api;

import com.paymentledger.auth.domain.UserEntity;
import com.paymentledger.notification.domain.WebhookSubscriptionEntity;
import com.paymentledger.notification.repository.WebhookSubscriptionRepository;
import com.paymentledger.notification.security.WebhookSecurityValidator;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/webhooks/subscriptions")
public class WebhookSubscriptionController {

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookSecurityValidator securityValidator;

    public record CreateSubscriptionRequest(String targetUrl, String eventType, String secret) {}

    public WebhookSubscriptionController(WebhookSubscriptionRepository subscriptionRepository,
                                       WebhookSecurityValidator securityValidator) {
        this.subscriptionRepository = subscriptionRepository;
        this.securityValidator = securityValidator;
    }

    @PostMapping
    public ResponseEntity<WebhookSubscriptionEntity> createSubscription(
            @RequestBody CreateSubscriptionRequest request,
            @AuthenticationPrincipal Object principal) {

        UUID userId = resolveUserId(principal);
        // Validate SSRF on target URL
        securityValidator.validateUrl(request.targetUrl());

        String eventType = (request.eventType() != null && !request.eventType().isBlank()) ? request.eventType() : "*";
        WebhookSubscriptionEntity subscription = new WebhookSubscriptionEntity(
                userId, request.targetUrl(), eventType, request.secret()
        );
        WebhookSubscriptionEntity saved = subscriptionRepository.save(subscription);
        return ResponseEntity.ok(saved);
    }

    @GetMapping
    public ResponseEntity<List<WebhookSubscriptionEntity>> listSubscriptions(
            @AuthenticationPrincipal Object principal) {
        UUID userId = resolveUserId(principal);
        return ResponseEntity.ok(subscriptionRepository.findByUserId(userId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSubscription(
            @PathVariable UUID id,
            @AuthenticationPrincipal Object principal) {
        UUID userId = resolveUserId(principal);
        WebhookSubscriptionEntity sub = subscriptionRepository.findById(id).orElse(null);
        if (sub == null || !sub.getUserId().equals(userId)) {
            return ResponseEntity.notFound().build();
        }
        subscriptionRepository.delete(sub);
        return ResponseEntity.noContent().build();
    }

    private UUID resolveUserId(Object principal) {
        if (principal instanceof UserEntity user) {
            return user.getId();
        }
        if (principal instanceof org.springframework.security.core.userdetails.UserDetails userDetails) {
            return UUID.fromString(userDetails.getUsername());
        }
        if (principal instanceof String str) {
            return UUID.fromString(str);
        }
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getName() != null) {
            return UUID.fromString(auth.getName());
        }
        throw new IllegalArgumentException("Unable to resolve authenticated user ID");
    }
}
