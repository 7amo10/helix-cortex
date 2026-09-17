package com.pulse.control;

import com.helix.profiler.flamegraph.FlameGraphAggregator;
import com.helix.profiler.flamegraph.MetricType;
import com.pulse.boundary.dto.FlameGraphNodeDto;
import com.pulse.boundary.dto.FlameGraphResponse;
import jakarta.ws.rs.core.MediaType;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlameGraphControlTest {

    private FlameGraphAggregator aggregator;

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

    private FlameGraphControl control;

    @BeforeEach
    void setUp() {
        aggregator = new FlameGraphAggregator();
        control = new FlameGraphControl(aggregator, sse, null, broadcaster);

        lenient().when(sse.newEventBuilder()).thenReturn(eventBuilder);
        lenient().when(eventBuilder.name(anyString())).thenReturn(eventBuilder);
        lenient().when(eventBuilder.id(anyString())).thenReturn(eventBuilder);
        lenient().when(eventBuilder.mediaType(any(MediaType.class))).thenReturn(eventBuilder);
        lenient().when(eventBuilder.data(any(Class.class), any())).thenReturn(eventBuilder);
        lenient().when(eventBuilder.build()).thenReturn(mockEvent);
    }

    @AfterEach
    void tearDown() {
        if (control != null) {
            control.destroy();
        }
    }

    @Test
    @DisplayName("getFoldedText returns Brendan Gregg folded stack traces")
    void testGetFoldedText() {
        aggregator.addSample(MetricType.CPU_TIME, List.of("com.pulse.App", "com.pulse.Worker", "run"), 42L);
        aggregator.addSample(MetricType.CPU_TIME, List.of("com.pulse.App", "com.pulse.Worker", "compute"), 18L);

        String folded = control.getFoldedText(MetricType.CPU_TIME);

        assertThat(folded).contains("com.pulse.App;com.pulse.Worker;run 42");
        assertThat(folded).contains("com.pulse.App;com.pulse.Worker;compute 18");
    }

    @Test
    @DisplayName("getD3Tree produces hierarchical node tree matching d3-flame-graph spec")
    void testGetD3Tree() {
        aggregator.addSample(MetricType.CPU_TIME, List.of("com.pulse.App", "com.pulse.Worker", "run"), 50L);

        FlameGraphNodeDto rootDto = control.getD3Tree(MetricType.CPU_TIME);

        assertThat(rootDto).isNotNull();
        assertThat(rootDto.name()).isEqualTo("root");
        assertThat(rootDto.value()).isEqualTo(50L);
        assertThat(rootDto.children()).isNotEmpty();

        FlameGraphNodeDto appChild = rootDto.children().get(0);
        assertThat(appChild.name()).isEqualTo("com.pulse.App");
        assertThat(appChild.value()).isEqualTo(50L);
    }

    @Test
    @DisplayName("getSpeedscopeJson produces standard Speedscope JSON schema v0.1.2")
    @SuppressWarnings("unchecked")
    void testGetSpeedscopeJson() {
        aggregator.addSample(MetricType.CPU_TIME, List.of("com.pulse.App", "com.pulse.Worker", "run"), 100L);

        Map<String, Object> speedscope = control.getSpeedscopeJson(MetricType.CPU_TIME);

        assertThat(speedscope.get("$schema")).isEqualTo("https://www.speedscope.app/file-format-spec.json");
        assertThat(speedscope.get("version")).isEqualTo("0.1.2");
        assertThat(speedscope.get("exporter")).isEqualTo("helix-cortex@1.0.0");
        assertThat(speedscope.get("name")).asString().contains("Helix Flame Graph");

        Map<String, Object> shared = (Map<String, Object>) speedscope.get("shared");
        assertThat(shared).containsKey("frames");
        List<Map<String, String>> frames = (List<Map<String, String>>) shared.get("frames");
        assertThat(frames).extracting(m -> m.get("name"))
                .contains("com.pulse.App", "com.pulse.Worker", "run");

        List<Map<String, Object>> profiles = (List<Map<String, Object>>) speedscope.get("profiles");
        assertThat(profiles).hasSize(1);
        Map<String, Object> profile = profiles.get(0);
        assertThat(profile.get("type")).isEqualTo("sampled");
        assertThat(profile.get("endValue")).isEqualTo(100L);

        List<List<Integer>> samples = (List<List<Integer>>) profile.get("samples");
        assertThat(samples).hasSize(1);
        assertThat(samples.get(0)).containsExactly(0, 1, 2);

        List<Long> weights = (List<Long>) profile.get("weights");
        assertThat(weights).containsExactly(100L);
    }

    @Test
    @DisplayName("getHtmlFlameGraph generates self-contained HTML page")
    void testGetHtmlFlameGraph() {
        aggregator.addSample(MetricType.CPU_TIME, List.of("com.pulse.App", "main"), 10L);

        String html = control.getHtmlFlameGraph(MetricType.CPU_TIME);

        assertThat(html).contains("<!DOCTYPE html>");
        assertThat(html).contains("Helix Flame Graph - CPU_TIME");
        assertThat(html).contains("Total Samples Collected: 10");
        assertThat(html).contains("com.pulse.App;main");
    }

    @Test
    @DisplayName("getFlameGraphResponse populates full REST response DTO")
    void testGetFlameGraphResponse() {
        aggregator.addSample(MetricType.ALLOCATION_BYTES, List.of("com.pulse.MemAlloc", "allocate"), 2048L);

        FlameGraphResponse resp = control.getFlameGraphResponse(MetricType.ALLOCATION_BYTES);

        assertThat(resp.metric()).isEqualTo("ALLOCATION_BYTES");
        assertThat(resp.unit()).isEqualTo("bytes");
        assertThat(resp.totalSamples()).isEqualTo(2048L);
        assertThat(resp.distinctStacks()).isEqualTo(1);
        assertThat(resp.maxDepth()).isEqualTo(2);
        assertThat(resp.folded()).contains("com.pulse.MemAlloc;allocate 2048");
        assertThat(resp.tree()).isNotNull();
        assertThat(resp.speedscope()).containsKey("$schema");
    }

    @Test
    @DisplayName("registerSink delivers immediate initial SSE event and registers sink with broadcaster")
    void testRegisterSink() {
        aggregator.addSample(MetricType.CPU_TIME, List.of("com.pulse.App", "run"), 15L);

        control.registerSink(sink, sse, MetricType.CPU_TIME, "json");

        verify(sink).send(any(OutboundSseEvent.class));
        verify(broadcaster).register(sink);
    }

    @Test
    @DisplayName("sampleAndBroadcast broadcasts flame graph event via broadcaster")
    void testSampleAndBroadcast() {
        aggregator.addSample(MetricType.CPU_TIME, List.of("com.pulse.App", "run"), 15L);

        control.sampleAndBroadcast();

        verify(broadcaster).broadcast(mockEvent);
    }
}
