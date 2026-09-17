package com.pulse.control;

import com.pulse.boundary.filter.JwtSecurityContext;
import com.pulse.boundary.filter.TokenClaims;
import com.pulse.entity.EngineRole;
import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Task 5.1 & AC 2: MicroProfile JWT Security Context Propagation Across Virtual Threads")
class SecurityContextPropagationTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("JWT claims and SecurityContext correctly resolve inside virtual thread executions")
    void testSecurityContextPropagationInVirtualThread() throws Exception {
        Instant now = Instant.now();
        TokenClaims claims = new TokenClaims("engineer_42", EngineRole.ENGINEER, now, now.plusSeconds(3600));
        SecurityContext ctx = new JwtSecurityContext(claims, true);

        AtomicReference<String> capturedPrincipal = new AtomicReference<>();
        AtomicBoolean capturedIsEngineer = new AtomicBoolean(false);
        AtomicBoolean capturedIsAdmin = new AtomicBoolean(false);
        AtomicBoolean capturedIsVirtual = new AtomicBoolean(false);
        AtomicReference<TokenClaims> capturedClaims = new AtomicReference<>();

        Callable<Void> task = () -> {
            capturedIsVirtual.set(Thread.currentThread().isVirtual());
            capturedPrincipal.set(SecurityContextHolder.getPrincipalName());
            capturedIsEngineer.set(SecurityContextHolder.isUserInRole("ENGINEER"));
            capturedIsAdmin.set(SecurityContextHolder.isUserInRole("ADMIN"));

            SecurityContext current = SecurityContextHolder.getContext();
            if (current instanceof JwtSecurityContext jwtCtx) {
                capturedClaims.set(jwtCtx.getClaims());
            }
            return null;
        };

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Void> future = executor.submit(SecurityContextHolder.wrap(task, ctx));
            future.get(5, TimeUnit.SECONDS);
        }

        assertThat(capturedIsVirtual.get()).isTrue();
        assertThat(capturedPrincipal.get()).isEqualTo("engineer_42");
        assertThat(capturedIsEngineer.get()).isTrue();
        assertThat(capturedIsAdmin.get()).isFalse();
        assertThat(capturedClaims.get()).isNotNull();
        assertThat(capturedClaims.get().subject()).isEqualTo("engineer_42");
        assertThat(capturedClaims.get().role()).isEqualTo(EngineRole.ENGINEER);
    }

    @Test
    @DisplayName("Multiple concurrent virtual threads maintain independent isolated security contexts")
    void testConcurrentVirtualThreadsIsolatedContexts() throws Exception {
        int threadCount = 100;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ConcurrentMap<Integer, Boolean> results = new ConcurrentHashMap<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threadCount; i++) {
                final int id = i;
                EngineRole role = (id % 2 == 0) ? EngineRole.ADMIN : EngineRole.ENGINEER;
                String username = "user_" + id;
                TokenClaims claims = new TokenClaims(username, role, Instant.now(), Instant.now().plusSeconds(3600));
                SecurityContext ctx = new JwtSecurityContext(claims, true);

                Callable<Boolean> task = () -> {
                    boolean validName = username.equals(SecurityContextHolder.getPrincipalName());
                    boolean validRole = SecurityContextHolder.isUserInRole(role.name());
                    boolean isVirtual = Thread.currentThread().isVirtual();
                    return validName && validRole && isVirtual;
                };

                executor.submit(() -> {
                    try {
                        Boolean ok = SecurityContextHolder.wrap(task, ctx).call();
                        results.put(id, ok);
                    } catch (Exception e) {
                        results.put(id, false);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean completed = latch.await(10, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
            assertThat(results).hasSize(threadCount);
            assertThat(results.values()).allMatch(b -> b);
        }
    }

    @Test
    @DisplayName("Context cleanup: ThreadLocal is safely cleared after task execution completes")
    void testContextCleanupAfterExecution() throws Exception {
        TokenClaims claims = new TokenClaims("transient_user", EngineRole.ENGINEER, Instant.now(), Instant.now().plusSeconds(3600));
        SecurityContext ctx = new JwtSecurityContext(claims, false);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(SecurityContextHolder.wrap(() -> {
                assertThat(SecurityContextHolder.getPrincipalName()).isEqualTo("transient_user");
                return null;
            }, ctx)).get(5, TimeUnit.SECONDS);
        }

        assertThat(SecurityContextHolder.getContext()).isNull();
        assertThat(SecurityContextHolder.getPrincipalName()).isEqualTo("anonymous");
    }
}
