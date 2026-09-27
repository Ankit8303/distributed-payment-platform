package com.paymentledger.admin.api;

import com.paymentledger.admin.api.dto.PageUtils;
import com.paymentledger.admin.api.dto.UserAdminResponse;
import com.paymentledger.auth.domain.Role;
import com.paymentledger.auth.domain.UserEntity;
import com.paymentledger.auth.domain.UserStatus;
import com.paymentledger.auth.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM')")
public class AdminUserController {

    private final UserRepository userRepository;

    public AdminUserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping
    public ResponseEntity<Page<UserAdminResponse>> listUsers(
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) String email,
            Pageable pageable) {

        Pageable clamped = PageUtils.clamp(pageable);
        Page<UserEntity> page;

        if (email != null && !email.isBlank()) {
            page = userRepository.findByEmailContainingIgnoreCase(email.trim(), clamped);
        } else if (role != null) {
            page = userRepository.findByRole(role, clamped);
        } else if (status != null) {
            page = userRepository.findByStatus(status, clamped);
        } else {
            page = userRepository.findAll(clamped);
        }

        return ResponseEntity.ok(page.map(UserAdminResponse::fromEntity));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<UserAdminResponse> getUser(@PathVariable UUID userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + userId));
        return ResponseEntity.ok(UserAdminResponse.fromEntity(user));
    }
}
