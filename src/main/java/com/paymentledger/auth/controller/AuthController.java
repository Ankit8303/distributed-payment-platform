package com.paymentledger.auth.controller;

import com.paymentledger.auth.dto.*;
import com.paymentledger.auth.service.AuthService;
import com.paymentledger.shared.redis.RateLimitExceededException;
import com.paymentledger.shared.redis.RateLimitPolicy;
import com.paymentledger.shared.redis.RateLimitResult;
import com.paymentledger.shared.redis.RedisKeyNamespaces;
import com.paymentledger.shared.redis.RedisRateLimiter;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public authentication and registration endpoints.
 * All responses automatically echo the X-Correlation-ID header via the CorrelationIdFilter.
 *
 * @see docs/product/07-api-contract.md §3.1
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final RedisRateLimiter redisRateLimiter;
    private final long loginRateLimit;
    private final long loginWindowSeconds;

    public AuthController(AuthService authService) {
        this(authService, null, 5L, 60L);
    }

    @Autowired
    public AuthController(AuthService authService,
                          @Autowired(required = false) RedisRateLimiter redisRateLimiter,
                          @Value("${app.ratelimit.auth.login-limit:5}") long loginRateLimit,
                          @Value("${app.ratelimit.auth.login-window-seconds:60}") long loginWindowSeconds) {
        this.authService = authService;
        this.redisRateLimiter = redisRateLimiter;
        this.loginRateLimit = loginRateLimit;
        this.loginWindowSeconds = loginWindowSeconds;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        RegisterResponse response = authService.register(request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        if (redisRateLimiter != null) {
            String key = RedisKeyNamespaces.authRateLimitKey(request.getEmail());
            RateLimitResult result = redisRateLimiter.checkLimit(key, loginRateLimit, loginWindowSeconds, RateLimitPolicy.FAIL_CLOSED);
            if (!result.allowed()) {
                throw new RateLimitExceededException(
                        "Too many login attempts. Please retry later.",
                        result.resetSeconds(),
                        result.limit()
                );
            }
        }
        LoginResponse response = authService.login(request);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        LoginResponse response = authService.refreshToken(request);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }
}
