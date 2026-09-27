package com.paymentledger.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.auth.dto.LoginRequest;
import com.paymentledger.auth.dto.RegisterRequest;
import com.paymentledger.auth.dto.RegisterResponse;
import com.paymentledger.auth.exception.EmailAlreadyExistsException;
import com.paymentledger.auth.exception.InvalidCredentialsException;
import com.paymentledger.auth.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paymentledger.infrastructure.AbstractIntegrationTest;

@AutoConfigureMockMvc
class AuthControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @Test
    @DisplayName("POST /api/v1/auth/register should return 201 on success")
    void register_success() throws Exception {
        RegisterRequest request = new RegisterRequest("test@example.com", "password1234", "CUSTOMER");
        RegisterResponse response = new RegisterResponse(UUID.randomUUID(), "test@example.com", "CUSTOMER", Instant.now());
        
        when(authService.register(any(RegisterRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("test@example.com"))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.userId").exists());
    }

    @Test
    @DisplayName("POST /api/v1/auth/register should return 400 for invalid email")
    void register_invalidEmail_returns400() throws Exception {
        RegisterRequest request = new RegisterRequest("not-an-email", "password1234", "CUSTOMER");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.invalidParameters[0].field").value("email"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/register should return 409 for duplicate email")
    void register_duplicateEmail_returns409() throws Exception {
        RegisterRequest request = new RegisterRequest("test@example.com", "password1234", "CUSTOMER");
        
        when(authService.register(any(RegisterRequest.class)))
                .thenThrow(new EmailAlreadyExistsException("test@example.com"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("POST /api/v1/auth/login should return 401 on invalid credentials")
    void login_invalidCredentials_returns401() throws Exception {
        LoginRequest request = new LoginRequest("test@example.com", "wrong-pwd");
        
        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }
}
