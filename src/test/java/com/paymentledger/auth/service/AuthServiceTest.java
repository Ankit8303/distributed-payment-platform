package com.paymentledger.auth.service;

import com.paymentledger.auth.config.JwtProperties;
import com.paymentledger.auth.domain.Role;
import com.paymentledger.auth.domain.UserEntity;
import com.paymentledger.auth.dto.LoginRequest;
import com.paymentledger.auth.dto.LoginResponse;
import com.paymentledger.auth.dto.RegisterRequest;
import com.paymentledger.auth.dto.RegisterResponse;
import com.paymentledger.auth.exception.EmailAlreadyExistsException;
import com.paymentledger.auth.exception.InvalidCredentialsException;
import com.paymentledger.auth.repository.RefreshTokenRepository;
import com.paymentledger.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setAccessTokenExpirationMs(900000);
        jwtProperties.setRefreshTokenExpirationMs(604800000);

        authService = new AuthService(userRepository, refreshTokenRepository, passwordEncoder, jwtService, jwtProperties);
    }

    @Test
    @DisplayName("register should create CUSTOMER and hash password")
    void register_validCustomer_success() {
        RegisterRequest request = new RegisterRequest("test@example.com", "password1234", "CUSTOMER");
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-pwd");

        UserEntity savedUser = new UserEntity("test@example.com", "hashed-pwd", Role.CUSTOMER);
        ReflectionTestUtils.setField(savedUser, "id", UUID.randomUUID());
        when(userRepository.save(any(UserEntity.class))).thenReturn(savedUser);

        RegisterResponse response = authService.register(request);

        assertThat(response.getEmail()).isEqualTo("test@example.com");
        assertThat(response.getRole()).isEqualTo("CUSTOMER");
        
        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("hashed-pwd");
    }

    @Test
    @DisplayName("register should reject ADMIN role explicitly")
    void register_adminRole_throwsIllegalArgumentException() {
        RegisterRequest request = new RegisterRequest("test@example.com", "password1234", "ADMIN");

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("restricted roles");
    }

    @Test
    @DisplayName("register should reject duplicate email")
    void register_duplicateEmail_throwsException() {
        RegisterRequest request = new RegisterRequest("test@example.com", "password1234", "CUSTOMER");
        when(userRepository.existsByEmail("test@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    @DisplayName("login should issue tokens on valid credentials")
    void login_validCredentials_success() {
        LoginRequest request = new LoginRequest("test@example.com", "password1234");
        UserEntity user = new UserEntity("test@example.com", "hashed-pwd", Role.CUSTOMER);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password1234", "hashed-pwd")).thenReturn(true);
        when(jwtService.generateAccessToken(user)).thenReturn("mock-access-token");

        LoginResponse response = authService.login(request);

        assertThat(response.getAccessToken()).isEqualTo("mock-access-token");
        assertThat(response.getRefreshToken()).isNotBlank();
        
        verify(refreshTokenRepository).save(any());
    }

    @Test
    @DisplayName("login should reject invalid password")
    void login_invalidPassword_throwsException() {
        LoginRequest request = new LoginRequest("test@example.com", "wrong-pwd");
        UserEntity user = new UserEntity("test@example.com", "hashed-pwd", Role.CUSTOMER);

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-pwd", "hashed-pwd")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(InvalidCredentialsException.class);
    }
}
