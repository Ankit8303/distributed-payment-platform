package com.paymentledger.auth.service;

import com.paymentledger.auth.config.JwtProperties;
import com.paymentledger.auth.domain.UserEntity;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * Service for issuing and validating JWT access tokens.
 * Uses HMAC-SHA256 per frozen security specification.
 */
@Service
public class JwtService {

    private final JwtProperties jwtProperties;
    private final SecretKey secretKey;

    public JwtService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        
        // Ensure secret meets HS256 entropy requirements
        String secret = jwtProperties.getSecret();
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("JWT secret must be at least 32 characters for HMAC-SHA256.");
        }
        
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Generates a short-lived access token.
     * Contains claims: sub (userId), role, iat, exp.
     */
    public String generateAccessToken(UserEntity user) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtProperties.getAccessTokenExpirationMs());

        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("role", user.getRole().name())
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Extracts and validates claims from a token.
     * Throws JwtException if the token is tampered, expired, or malformed.
     */
    public Claims extractAndValidateClaims(String token) throws JwtException {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Extracts the user ID (subject) from a validated token.
     */
    public UUID extractUserId(String token) throws JwtException {
        String subject = extractAndValidateClaims(token).getSubject();
        return UUID.fromString(subject);
    }
}
