package com.paymentledger.auth.service;

import com.paymentledger.auth.config.JwtProperties;
import com.paymentledger.auth.domain.Role;
import com.paymentledger.auth.domain.UserEntity;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;
import java.security.SecureRandom;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private JwtService jwtService;
    private JwtProperties jwtProperties;
    private UserEntity user;
    private UUID userId;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setSecret(HexFormat.of().formatHex(new SecureRandom().generateSeed(32)));
        jwtProperties.setAccessTokenExpirationMs(900000); // 15 min

        jwtService = new JwtService(jwtProperties);

        user = new UserEntity("test@example.com", "hash", Role.CUSTOMER);
        userId = UUID.randomUUID();
        ReflectionTestUtils.setField(user, "id", userId);
    }

    @Test
    @DisplayName("generateAccessToken should issue a valid token with required claims")
    void generateAccessToken_validClaims() {
        String token = jwtService.generateAccessToken(user);
        assertThat(token).isNotBlank();

        Claims claims = jwtService.extractAndValidateClaims(token);

        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("role")).isEqualTo("CUSTOMER");
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isNotNull();
    }

    @Test
    @DisplayName("extractAndValidateClaims should throw JwtException for tampered token")
    void extractAndValidateClaims_tamperedToken_throwsJwtException() {
        String token = jwtService.generateAccessToken(user);
        String tamperedToken = token.substring(0, token.length() - 5) + "abcde";

        assertThatThrownBy(() -> jwtService.extractAndValidateClaims(tamperedToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("extractAndValidateClaims should throw JwtException for expired token")
    void extractAndValidateClaims_expiredToken_throwsJwtException() {
        jwtProperties.setAccessTokenExpirationMs(-1000); // Expired 1 second ago
        JwtService expiredJwtService = new JwtService(jwtProperties);

        String expiredToken = expiredJwtService.generateAccessToken(user);

        assertThatThrownBy(() -> expiredJwtService.extractAndValidateClaims(expiredToken))
                .isInstanceOf(JwtException.class);
    }
}
