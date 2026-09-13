package com.pulse.boundary;

import com.pulse.boundary.dto.LoginRequest;
import com.pulse.boundary.dto.LoginResponse;
import com.pulse.boundary.dto.RegisterRequest;
import com.pulse.control.EngineerAccountRepository;
import com.pulse.control.TokenService;
import com.pulse.entity.EngineRole;
import com.pulse.entity.EngineerAccount;
import com.pulse.entity.PasswordUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.security.enterprise.identitystore.Pbkdf2PasswordHash;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * Public authentication boundary exposing login and registration endpoints.
 */
@Path("/auth")
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Inject
    private EngineerAccountRepository accountRepository;

    @Inject
    private TokenService tokenService;

    @Inject
    private Pbkdf2PasswordHash passwordHasher;

    public AuthResource() {
    }

    public AuthResource(EngineerAccountRepository accountRepository, TokenService tokenService, Pbkdf2PasswordHash passwordHasher) {
        this.accountRepository = accountRepository;
        this.tokenService = tokenService;
        this.passwordHasher = passwordHasher;
    }

    @POST
    @Path("/login")
    public Response login(LoginRequest req) {
        if (req == null || req.username() == null || req.password() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(PROBLEM_JSON)
                    .entity("{\"status\":400,\"title\":\"Bad Request\",\"detail\":\"Username and password are required\"}")
                    .build();
        }

        Optional<EngineerAccount> accountOpt = accountRepository.findByUsername(req.username());
        if (accountOpt.isEmpty()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .type(PROBLEM_JSON)
                    .entity("{\"status\":401,\"title\":\"Unauthorized\",\"detail\":\"Invalid username or password\"}")
                    .build();
        }

        EngineerAccount account = accountOpt.get();
        boolean valid;
        if (passwordHasher != null) {
            try {
                valid = account.verifyPassword(req.password(), passwordHasher);
            } catch (Exception e) {
                valid = account.verifyPassword(req.password());
            }
        } else {
            valid = account.verifyPassword(req.password());
        }

        if (!valid) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .type(PROBLEM_JSON)
                    .entity("{\"status\":401,\"title\":\"Unauthorized\",\"detail\":\"Invalid username or password\"}")
                    .build();
        }

        String token = tokenService.issue(account.getUsername(), account.getRole());
        LoginResponse response = new LoginResponse(token, account.getRole(), TokenService.EXPIRY_SECONDS);
        return Response.ok(response).build();
    }

    @POST
    @Path("/register")
    @Transactional
    public Response register(RegisterRequest req) {
        if (req == null || req.username() == null || req.username().isBlank() ||
                req.password() == null || req.password().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(PROBLEM_JSON)
                    .entity("{\"status\":400,\"title\":\"Bad Request\",\"detail\":\"Username and password are required\"}")
                    .build();
        }

        if (accountRepository.existsByUsername(req.username())) {
            return Response.status(Response.Status.CONFLICT)
                    .type(PROBLEM_JSON)
                    .entity("{\"status\":409,\"title\":\"Conflict\",\"detail\":\"Username already exists\"}")
                    .build();
        }

        String passwordHash;
        if (passwordHasher != null) {
            try {
                passwordHash = passwordHasher.generate(req.password().toCharArray());
            } catch (Exception e) {
                passwordHash = PasswordUtil.hash(req.password());
            }
        } else {
            passwordHash = PasswordUtil.hash(req.password());
        }

        EngineerAccount newAccount = new EngineerAccount(req.username(), passwordHash, EngineRole.ENGINEER);
        accountRepository.save(newAccount);

        return Response.status(Response.Status.CREATED)
                .entity(Map.of("username", newAccount.getUsername()))
                .build();
    }

    public void setAccountRepository(EngineerAccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public void setTokenService(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    public void setPasswordHasher(Pbkdf2PasswordHash passwordHasher) {
        this.passwordHasher = passwordHasher;
    }
}
