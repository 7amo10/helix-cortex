package com.pulse.boundary;

import com.helix.api.CompiledRule;
import com.helix.api.RuleEngine;
import com.helix.core.DefaultRuleEngine;
import com.helix.core.executor.VirtualThreadRuleExecutor;
import com.helix.core.parser.RuleSchema;
import com.helix.profiler.flamegraph.FlameGraphAggregator;
import com.helix.profiler.flamegraph.MetricType;
import com.pulse.boundary.dto.FlameGraphNodeDto;
import com.pulse.boundary.dto.FlameGraphResponse;
import com.pulse.boundary.filter.JwtSecurityFilter;
import com.pulse.boundary.filter.JwtSecurityContext;
import com.pulse.boundary.filter.TokenClaims;
import com.pulse.control.ExecutorType;
import com.pulse.control.FlameGraphControl;
import com.pulse.control.RuleExecutionService;
import com.pulse.control.RuleSessionRepository;
import com.pulse.control.TokenService;
import com.pulse.entity.EngineRole;
import com.pulse.entity.RuleSession;
import com.pulse.entity.SessionStatus;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseBroadcaster;
import jakarta.ws.rs.sse.SseEventSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Integration test suite verifying:
 * 1. Folded stack traces stream valid data under active rule evaluation load.
 * 2. MicroProfile JWT security filter enforces 401 Unauthorized for unauthenticated requests.
 * 3. Standard flame graph visualizers (d3-flame-graph and Speedscope) formats are fully supported.
 */
@ExtendWith(MockitoExtension.class)
class FlameGraphTelemetryIntegrationTest {

    private RuleEngine ruleEngine;
    private FlameGraphAggregator aggregator;
    private FlameGraphControl flameGraphControl;
    private RuleExecutionService executionService;
    private TelemetryResource telemetryResource;
    private TokenService tokenService;
    private JwtSecurityFilter jwtSecurityFilter;

    @Mock
    private RuleSessionRepository sessionRepository;

    @Mock
    private Sse sse;

    @Mock
    private SseBroadcaster broadcaster;

    @Mock
    private SseEventSink sink;

    @Mock
    private OutboundSseEvent.Builder eventBuilder;

    @Mock
    private OutboundSseEvent mockEvent;

    @BeforeEach
    void setUp() {
        ruleEngine = new DefaultRuleEngine();
        aggregator = new FlameGraphAggregator();
        flameGraphControl = new FlameGraphControl(aggregator, sse, null, broadcaster);

        lenient().when(sse.newEventBuilder()).thenReturn(eventBuilder);
        lenient().when(eventBuilder.name(anyString())).thenReturn(eventBuilder);
        lenient().when(eventBuilder.id(anyString())).thenReturn(eventBuilder);
        lenient().when(eventBuilder.mediaType(any(MediaType.class))).thenReturn(eventBuilder);
        lenient().when(eventBuilder.data(any(Class.class), any())).thenReturn(eventBuilder);
        lenient().when(eventBuilder.build()).thenReturn(mockEvent);

        executionService = new RuleExecutionService(ruleEngine, sessionRepository, ExecutorType.VIRTUAL_THREADS);
        executionService.setFlameGraphAggregator(aggregator);

        telemetryResource = new TelemetryResource(null, flameGraphControl);

        tokenService = new TokenService();
        jwtSecurityFilter = new JwtSecurityFilter(tokenService, null);
    }

    @AfterEach
    void tearDown() {
        if (executionService != null) {
            executionService.destroy();
        }
        if (flameGraphControl != null) {
            flameGraphControl.destroy();
        }
    }

    @Test
    @DisplayName("Acceptance Criteria 1: Folded stack traces stream valid data under active rule evaluation load")
    void testStreamingValidFoldedStacksUnderEvaluationLoad() throws Exception {
        int concurrentEvaluations = 250;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrentEvaluations);

        RuleSchema schema = new RuleSchema(
                "active_rule", "1.0", "Active rule evaluation", "RULE",
                "a + b > 20", Map.of("a", Integer.class, "b", Integer.class)
        );
        CompiledRule compiledRule = ruleEngine.compile(schema);
        executionService.cacheCompiledRule("active_rule:1.0", compiledRule);

        RuleSession session = new RuleSession("analyst_user", "{}", "active_rule:1.0", SessionStatus.COMPILED);
        when(sessionRepository.findById(500L)).thenReturn(Optional.of(session));

        ExecutorService clientPool = Executors.newVirtualThreadPerTaskExecutor();
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < concurrentEvaluations; i++) {
            final int index = i;
            clientPool.submit(() -> {
                try {
                    startLatch.await(5, TimeUnit.SECONDS);
                    Map<String, Object> vars = Map.of("a", 15 + (index % 5), "b", 10);
                    SecurityContext ctx = createMockSecurityContext("engineer_" + index, EngineRole.ENGINEER);
                    executionService.execute(500L, vars, ctx);
                    successCount.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release all concurrent rule evaluations
        startLatch.countDown();
        boolean completed = doneLatch.await(15, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(concurrentEvaluations);

        // 1. Verify REST endpoint plain-text folded output under active load
        Response textResp = telemetryResource.getFlameGraph("CPU_TIME", "text", null);
        assertThat(textResp.getStatus()).isEqualTo(200);
        assertThat(textResp.getMediaType()).isEqualTo(MediaType.TEXT_PLAIN_TYPE);
        String foldedText = (String) textResp.getEntity();
        assertThat(foldedText).isNotBlank();
        assertThat(foldedText).contains("com.pulse.boundary.RuleResource.execute;com.pulse.control.RuleExecutionService.execute;com.helix.engine.RuleExecution.active_rule");

        // 2. Verify REST endpoint JSON / d3 format under active load
        Response d3Resp = telemetryResource.getFlameGraph("CPU_TIME", "d3", null);
        assertThat(d3Resp.getStatus()).isEqualTo(200);
        assertThat(d3Resp.getMediaType()).isEqualTo(MediaType.APPLICATION_JSON_TYPE);
        FlameGraphNodeDto d3Tree = (FlameGraphNodeDto) d3Resp.getEntity();
        assertThat(d3Tree.name()).isEqualTo("root");
        assertThat(d3Tree.value()).isGreaterThan(0L);
        assertThat(d3Tree.children()).isNotEmpty();

        // 3. Verify REST endpoint Speedscope format under active load
        Response speedscopeResp = telemetryResource.getFlameGraph("CPU_TIME", "speedscope", null);
        assertThat(speedscopeResp.getStatus()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> speedscopeJson = (Map<String, Object>) speedscopeResp.getEntity();
        assertThat(speedscopeJson.get("$schema")).isEqualTo("https://www.speedscope.app/file-format-spec.json");
        assertThat(speedscopeJson.get("version")).isEqualTo("0.1.2");

        // 4. Verify SSE stream registration and event emission under active load
        telemetryResource.streamFlameGraph(sink, sse, "CPU_TIME", "json");
        verify(sink, atLeastOnce()).send(any(OutboundSseEvent.class));
        verify(broadcaster).register(sink);

        // 5. Verify memory allocation metrics also captured
        Response allocTextResp = telemetryResource.getFlameGraph("ALLOCATION_BYTES", "text", null);
        String allocFolded = (String) allocTextResp.getEntity();
        assertThat(allocFolded).contains("java.lang.Object.<init>");

        clientPool.shutdown();
    }

    @Test
    @DisplayName("Acceptance Criteria 2: MicroProfile JWT security filter enforces 401 Unauthorized for unauthenticated calls")
    void testJwtSecurityEnforcement() throws Exception {
        // Case A: Missing Authorization header
        ContainerRequestContext missingHeaderContext = mock(ContainerRequestContext.class);
        when(missingHeaderContext.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn(null);

        ArgumentCaptor<Response> responseCaptor = ArgumentCaptor.forClass(Response.class);
        jwtSecurityFilter.filter(missingHeaderContext);

        verify(missingHeaderContext).abortWith(responseCaptor.capture());
        Response missingResp = responseCaptor.getValue();
        assertThat(missingResp.getStatus()).isEqualTo(Response.Status.UNAUTHORIZED.getStatusCode());
        assertThat(missingResp.getEntity()).asString().contains("\"status\":401");
        assertThat(missingResp.getEntity()).asString().contains("Unauthorized");

        // Case B: Malformed Authorization header (not Bearer)
        ContainerRequestContext malformedContext = mock(ContainerRequestContext.class);
        when(malformedContext.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Basic dXNlcjpwYXNz");

        jwtSecurityFilter.filter(malformedContext);
        verify(malformedContext).abortWith(argThat(r -> r.getStatus() == Response.Status.UNAUTHORIZED.getStatusCode()));

        // Case C: Empty Bearer token
        ContainerRequestContext emptyContext = mock(ContainerRequestContext.class);
        when(emptyContext.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer ");

        jwtSecurityFilter.filter(emptyContext);
        verify(emptyContext).abortWith(argThat(r -> r.getStatus() == Response.Status.UNAUTHORIZED.getStatusCode()));

        // Case D: Valid signed Bearer token passes and sets JwtSecurityContext
        String validToken = tokenService.issue("engineer1", EngineRole.ENGINEER);
        ContainerRequestContext validContext = mock(ContainerRequestContext.class);
        when(validContext.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + validToken);

        jwtSecurityFilter.filter(validContext);

        verify(validContext, never()).abortWith(any(Response.class));
        ArgumentCaptor<SecurityContext> secCaptor = ArgumentCaptor.forClass(SecurityContext.class);
        verify(validContext).setSecurityContext(secCaptor.capture());
        SecurityContext boundContext = secCaptor.getValue();
        assertThat(boundContext.getUserPrincipal().getName()).isEqualTo("engineer1");
        assertThat(boundContext.isUserInRole("ENGINEER")).isTrue();
    }

    @Test
    @DisplayName("Content negotiation respects Accept header when format query param is absent")
    void testContentNegotiationViaAcceptHeader() {
        aggregator.addSample(MetricType.CPU_TIME, List.of("com.pulse.Root", "step1"), 25L);

        HttpHeaders textHeaders = mock(HttpHeaders.class);
        when(textHeaders.getAcceptableMediaTypes()).thenReturn(List.of(MediaType.TEXT_PLAIN_TYPE));

        Response resp = telemetryResource.getFlameGraph("CPU_TIME", null, textHeaders);
        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(resp.getMediaType()).isEqualTo(MediaType.TEXT_PLAIN_TYPE);
        assertThat(resp.getEntity()).asString().contains("com.pulse.Root;step1 25");

        HttpHeaders htmlHeaders = mock(HttpHeaders.class);
        when(htmlHeaders.getAcceptableMediaTypes()).thenReturn(List.of(MediaType.TEXT_HTML_TYPE));

        Response htmlResp = telemetryResource.getFlameGraph("CPU_TIME", null, htmlHeaders);
        assertThat(htmlResp.getStatus()).isEqualTo(200);
        assertThat(htmlResp.getMediaType()).isEqualTo(MediaType.TEXT_HTML_TYPE);
        assertThat(htmlResp.getEntity()).asString().contains("<!DOCTYPE html>");
    }

    private SecurityContext createMockSecurityContext(String username, EngineRole role) {
        Instant now = Instant.now();
        TokenClaims claims = new TokenClaims(username, role, now, now.plusSeconds(3600));
        return new JwtSecurityContext(claims, true);
    }
}
