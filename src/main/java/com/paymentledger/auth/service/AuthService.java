package com.paymentledger.auth.service;

import com.paymentledger.auth.config.JwtProperties;
import com.paymentledger.auth.domain.RefreshTokenEntity;
import com.paymentledger.auth.domain.Role;
import com.paymentledger.auth.domain.UserEntity;
import com.paymentledger.auth.domain.UserStatus;
import com.paymentledger.auth.dto.*;
import com.paymentledger.auth.exception.EmailAlreadyExistsException;
import com.paymentledger.auth.exception.InvalidCredentialsException;
import com.paymentledger.auth.exception.InvalidRefreshTokenException;
import com.paymentledger.auth.repository.RefreshTokenRepository;
import com.paymentledger.auth.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Core business logic for authentication, registration, and refresh token rotation.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       JwtProperties jwtProperties) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
    }

    /**
     * Registers a new CUSTOMER or MERCHANT.
     * Prevents registration of ADMIN or SYSTEM roles.
     */
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        Role role;
        try {
            role = Role.valueOf(request.getRole().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid role specified");
        }

        // Phase 0 rule: Public registration cannot create ADMIN or SYSTEM
        if (role == Role.ADMIN || role == Role.SYSTEM) {
            throw new IllegalArgumentException("Cannot register with restricted roles");
        }

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new EmailAlreadyExistsException(request.getEmail());
        }

        String hashedPassword = passwordEncoder.encode(request.getPassword());
        UserEntity user = new UserEntity(request.getEmail(), hashedPassword, role);
        user = userRepository.save(user);

        return new RegisterResponse(user.getId(), user.getEmail(), user.getRole().name(), user.getCreatedAt());
    }

    /**
     * Authenticates a user and issues a JWT token pair.
     */
    @Transactional
    public LoginResponse login(LoginRequest request) {
        UserEntity user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new InvalidCredentialsException();
        }

        return issueTokenPair(user);
    }

    /**
     * Rotates a refresh token.
     * Requires atomic exactly-once consumption logic to prevent replay/concurrent abuse.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LoginResponse refreshToken(RefreshTokenRequest request) {
        String rawToken = request.getRefreshToken();
        // The token format is exactly the same as its hash in this context because
        // we use a cryptographically random UUID as the refresh token itself.
        // We hash it using SHA-256 for database storage.
        String tokenHash = hashRefreshToken(rawToken);

        RefreshTokenEntity refreshTokenEntity = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidRefreshTokenException("Refresh token is invalid"));

        if (refreshTokenEntity.isExpired()) {
            throw new InvalidRefreshTokenException("Refresh token has expired");
        }
        
        if (refreshTokenEntity.isRevoked()) {
            throw new InvalidRefreshTokenException("Refresh token has been revoked");
        }

        // Atomic exactly-once consumption check.
        // If two threads try to refresh concurrently, only one will successfully update 'revoked=false' to true.
        int updatedCount = refreshTokenRepository.revokeByTokenHashIfNotRevoked(tokenHash);
        if (updatedCount != 1) {
            throw new InvalidRefreshTokenException("Refresh token has already been consumed");
        }

        UserEntity user = userRepository.findById(refreshTokenEntity.getUserId())
                .orElseThrow(() -> new InvalidRefreshTokenException("User associated with token no longer exists"));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new InvalidRefreshTokenException("User is no longer active");
        }

        return issueTokenPair(user);
    }

    /**
     * Issues a new JWT access token and a new database-backed refresh token.
     */
    private LoginResponse issueTokenPair(UserEntity user) {
        String accessToken = jwtService.generateAccessToken(user);

        // Generate cryptographically random high-entropy UUID
        String rawRefreshToken = UUID.randomUUID().toString();
        String refreshTokenHash = hashRefreshToken(rawRefreshToken);

        Instant expiresAt = Instant.now().plusMillis(jwtProperties.getRefreshTokenExpirationMs());
        RefreshTokenEntity refreshTokenEntity = new RefreshTokenEntity(user.getId(), refreshTokenHash, expiresAt);
        refreshTokenRepository.save(refreshTokenEntity);

        return new LoginResponse(
                accessToken,
                rawRefreshToken, // Return raw token to client
                "Bearer",
                jwtProperties.getAccessTokenExpirationMs() / 1000
        );
    }

    /**
     * Hashes the raw refresh token for database storage using SHA-256.
     * This prevents leaked database backups from containing usable refresh tokens.
     */
    private String hashRefreshToken(String rawToken) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return bytesToHex(encodedhash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    private static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
