package com.pulse.boundary;

import com.pulse.boundary.dto.LoginRequest;
import com.pulse.boundary.dto.LoginResponse;
import com.pulse.boundary.dto.RegisterRequest;
import com.pulse.control.EngineerAccountRepository;
import com.pulse.control.TokenService;
import com.pulse.entity.EngineRole;
import com.pulse.entity.EngineerAccount;
import com.pulse.entity.PasswordUtil;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthResourceTest {

    @Mock
    private EngineerAccountRepository accountRepository;

    private TokenService tokenService;
    private AuthResource authResource;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService("unit-test-secret-key-at-least-32-characters-long");
        authResource = new AuthResource(accountRepository, tokenService, null);
    }

    @Test
    @DisplayName("POST /login with valid credentials should return 200 and LoginResponse with token")
    void testLoginSuccess() {
        String username = "engineer_alice";
        String rawPassword = "password123!";
        String passwordHash = PasswordUtil.hash(rawPassword);
        EngineerAccount account = new EngineerAccount(username, passwordHash, EngineRole.ENGINEER);

        when(accountRepository.findByUsername(username)).thenReturn(Optional.of(account));

        Response response = authResource.login(new LoginRequest(username, rawPassword));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getEntity()).isInstanceOf(LoginResponse.class);

        LoginResponse loginResponse = (LoginResponse) response.getEntity();
        assertThat(loginResponse.token()).isNotNull().isNotBlank();
        assertThat(loginResponse.role()).isEqualTo(EngineRole.ENGINEER);
        assertThat(loginResponse.expiresIn()).isEqualTo(3600L);
    }

    @Test
    @DisplayName("POST /login with wrong password should return 401 Unauthorized")
    void testLoginWrongPassword() {
        String username = "engineer_alice";
        String passwordHash = PasswordUtil.hash("correctPassword");
        EngineerAccount account = new EngineerAccount(username, passwordHash, EngineRole.ENGINEER);

        when(accountRepository.findByUsername(username)).thenReturn(Optional.of(account));

        Response response = authResource.login(new LoginRequest(username, "wrongPassword"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getMediaType().toString()).isEqualTo("application/problem+json");
    }

    @Test
    @DisplayName("POST /login with unknown username should return 401 Unauthorized")
    void testLoginUnknownUsername() {
        when(accountRepository.findByUsername("unknown_user")).thenReturn(Optional.empty());

        Response response = authResource.login(new LoginRequest("unknown_user", "somePassword"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getMediaType().toString()).isEqualTo("application/problem+json");
    }

    @Test
    @DisplayName("POST /register with a new username should return 201 Created and save account")
    void testRegisterSuccess() {
        String username = "new_engineer";
        String password = "newPassword123";

        when(accountRepository.existsByUsername(username)).thenReturn(false);

        Response response = authResource.register(new RegisterRequest(username, password));

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getEntity()).isEqualTo(Map.of("username", username));
        verify(accountRepository).save(any(EngineerAccount.class));
    }

    @Test
    @DisplayName("POST /register with duplicate username should return 409 Conflict")
    void testRegisterDuplicateUsername() {
        String username = "existing_user";

        when(accountRepository.existsByUsername(username)).thenReturn(true);

        Response response = authResource.register(new RegisterRequest(username, "password"));

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getMediaType().toString()).isEqualTo("application/problem+json");
        verify(accountRepository, never()).save(any());
    }
}
