package com.paymentledger.account.api;

import com.paymentledger.account.api.dto.AccountResponse;
import com.paymentledger.account.domain.AccountEntity;
import com.paymentledger.account.service.AccountService;
import com.paymentledger.auth.domain.UserEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/{id}")
    public AccountResponse getAccount(
            @PathVariable UUID id,
            @AuthenticationPrincipal Object principal) {
        
        UUID userId = resolveUserId(principal);
        String role = resolveUserRole();
        AccountEntity account = accountService.getAccount(id, userId, role);
        return new AccountResponse(account);
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

    private String resolveUserRole() {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getAuthorities() != null) {
            return auth.getAuthorities().stream()
                    .map(a -> a.getAuthority().replace("ROLE_", ""))
                    .findFirst()
                    .orElse("CUSTOMER");
        }
        return "CUSTOMER";
    }
}
