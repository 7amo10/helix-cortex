package com.pulse.control;

import com.helix.profiler.async.FlameGraphGenerator;
import com.helix.profiler.flamegraph.FlameGraphAggregator;
import com.helix.profiler.flamegraph.MetricType;
import com.helix.profiler.flamegraph.StackFrameNode;
import com.pulse.boundary.dto.FlameGraphNodeDto;
import com.pulse.boundary.dto.FlameGraphResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import jakarta.enterprise.concurrent.ManagedScheduledExecutorService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseBroadcaster;
import jakarta.ws.rs.sse.SseEventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Control bean providing folded stack trace aggregation, d3-flame-graph / Speedscope transformations,
 * and Server-Sent Events (SSE) live streaming.
 */
@ApplicationScoped
public class FlameGraphControl {

    private static final Logger log = LoggerFactory.getLogger(FlameGraphControl.class);

    @Inject
    private FlameGraphAggregator aggregator;

    @Context
    private Sse sse;

    @Resource(lookup = "java:comp/DefaultManagedScheduledExecutorService")
    private ManagedScheduledExecutorService scheduledExecutor;

    private SseBroadcaster broadcaster;
    private ScheduledExecutorService localExecutor;
    private ScheduledFuture<?> broadcastTask;
    private final FlameGraphGenerator htmlGenerator = new FlameGraphGenerator();

    public FlameGraphControl() {
    }

    public FlameGraphControl(FlameGraphAggregator aggregator,
                             Sse sse,
                             ScheduledExecutorService scheduledExecutor,
                             SseBroadcaster broadcaster) {
        this.aggregator = aggregator != null ? aggregator : new FlameGraphAggregator();
        this.sse = sse;
        this.localExecutor = scheduledExecutor;
        this.broadcaster = broadcaster;
    }

    @PostConstruct
    public void init() {
        if (aggregator == null) {
            aggregator = new FlameGraphAggregator();
        }

        if (broadcaster == null && sse != null) {
            broadcaster = sse.newBroadcaster();
            broadcaster.onClose(sink -> log.info("Flame graph SSE sink disconnected: {}", sink));
            broadcaster.onError((sink, throwable) ->
                    log.warn("Flame graph SSE sink error on {}: {}", sink, throwable.getMessage()));
        }

        ScheduledExecutorService executorToUse = scheduledExecutor != null
                ? scheduledExecutor
                : (localExecutor != null ? localExecutor : Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "flamegraph-telemetry-broadcaster");
                    t.setDaemon(true);
                    return t;
                }));

        if (localExecutor == null && scheduledExecutor == null) {
            localExecutor = executorToUse;
        }

        try {
            broadcastTask = executorToUse.scheduleAtFixedRate(
                    this::sampleAndBroadcast,
                    0,
                    1,
                    TimeUnit.SECONDS
            );
        } catch (Exception e) {
            log.warn("Could not schedule flame graph telemetry broadcaster: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        if (broadcastTask != null) {
            broadcastTask.cancel(true);
        }
        if (broadcaster != null) {
            try {
                broadcaster.close();
            } catch (Exception e) {
                log.debug("Error closing FlameGraph SseBroadcaster: {}", e.getMessage());
            }
        }
        if (localExecutor != null) {
            localExecutor.shutdownNow();
        }
    }

    /**
     * Registers an SSE event sink to receive live flame graph updates.
     */
    public void registerSink(SseEventSink sink, Sse sseContext, MetricType metric, String format) {
        if (sink == null) {
            return;
        }
        if (this.sse == null && sseContext != null) {
            this.sse = sseContext;
        }
        if (broadcaster == null && this.sse != null) {
            broadcaster = this.sse.newBroadcaster();
            broadcaster.onClose(s -> log.info("Flame graph SSE telemetry sink disconnected: {}", s));
            broadcaster.onError((s, t) ->
                    log.warn("Flame graph SSE telemetry sink error on {}: {}", s, t.getMessage()));
        }

        // Send initial state snapshot immediately to new sink
        try {
            if (this.sse != null) {
                OutboundSseEvent initialEvent = createEvent(this.sse, metric != null ? metric : MetricType.CPU_TIME, format);
                sink.send(initialEvent);
            }
        } catch (Exception e) {
            log.warn("Failed to deliver initial flame graph SSE event: {}", e.getMessage());
        }

        if (broadcaster != null) {
            broadcaster.register(sink);
        }
    }

    /**
     * Broadcasts current flame graph telemetry to all connected SSE clients.
     */
    public void sampleAndBroadcast() {
        if (broadcaster == null || sse == null) {
            return;
        }
        try {
            OutboundSseEvent event = createEvent(sse, MetricType.CPU_TIME, "json");
            broadcaster.broadcast(event);
        } catch (Exception e) {
            log.warn("Failed to broadcast flame graph telemetry event: {}", e.getMessage());
        }
    }

    /**
     * Constructs an outbound SSE event for the given metric and format.
     */
    public OutboundSseEvent createEvent(Sse sseContext, MetricType metric, String format) {
        Objects.requireNonNull(sseContext, "sseContext cannot be null");
        MetricType targetMetric = metric != null ? metric : MetricType.CPU_TIME;

        if ("text".equalsIgnoreCase(format) || "folded".equalsIgnoreCase(format)) {
            String folded = getFoldedText(targetMetric);
            return sseContext.newEventBuilder()
                    .name("flamegraph")
                    .id(String.valueOf(System.currentTimeMillis()))
                    .mediaType(MediaType.TEXT_PLAIN_TYPE)
                    .data(String.class, folded)
                    .build();
        } else {
            FlameGraphResponse response = getFlameGraphResponse(targetMetric);
            return sseContext.newEventBuilder()
                    .name("flamegraph")
                    .id(String.valueOf(response.timestamp().toEpochMilli()))
                    .mediaType(MediaType.APPLICATION_JSON_TYPE)
                    .data(FlameGraphResponse.class, response)
                    .build();
        }
    }

    /**
     * Exports raw Brendan Gregg folded stack traces ("frame1;frame2 count\n").
     */
    public String getFoldedText(MetricType metric) {
        MetricType targetMetric = metric != null ? metric : MetricType.CPU_TIME;
        return aggregator != null ? aggregator.exportFolded(targetMetric) : "";
    }

    /**
     * Returns a hierarchical d3-flame-graph JSON node tree.
     */
    public FlameGraphNodeDto getD3Tree(MetricType metric) {
        MetricType targetMetric = metric != null ? metric : MetricType.CPU_TIME;
        if (aggregator == null) {
            return new FlameGraphNodeDto("root", 0L, Collections.emptyList());
        }
        StackFrameNode root = aggregator.getRootNode(targetMetric);
        return FlameGraphNodeDto.from(root);
    }

    /**
     * Builds a Speedscope JSON file-format specification compliant structure.
     */
    public Map<String, Object> getSpeedscopeJson(MetricType metric) {
        MetricType targetMetric = metric != null ? metric : MetricType.CPU_TIME;
        String folded = getFoldedText(targetMetric);

        Map<String, Integer> frameMap = new LinkedHashMap<>();
        List<List<Integer>> samples = new ArrayList<>();
        List<Long> weights = new ArrayList<>();
        long totalWeight = 0;

        if (folded != null && !folded.isBlank()) {
            String[] lines = folded.split("\\r?\\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int lastSpace = line.lastIndexOf(' ');
                if (lastSpace > 0 && lastSpace < line.length() - 1) {
                    String stackPart = line.substring(0, lastSpace).trim();
                    try {
                        long count = Long.parseLong(line.substring(lastSpace + 1).trim());
                        if (count > 0 && !stackPart.isEmpty()) {
                            String[] frames = stackPart.split(";");
                            List<Integer> sample = new ArrayList<>(frames.length);
                            for (String frame : frames) {
                                String cleanFrame = frame.trim();
                                if (!cleanFrame.isEmpty()) {
                                    int idx = frameMap.computeIfAbsent(cleanFrame, k -> frameMap.size());
                                    sample.add(idx);
                                }
                            }
                            if (!sample.isEmpty()) {
                                samples.add(sample);
                                weights.add(count);
                                totalWeight += count;
                            }
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }

        List<Map<String, String>> sharedFrames = frameMap.keySet().stream()
                .map(f -> Map.of("name", f))
                .toList();

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("type", "sampled");
        profile.put("name", targetMetric.getDisplayName());
        profile.put("unit", targetMetric.getUnit());
        profile.put("startValue", 0);
        profile.put("endValue", totalWeight);
        profile.put("samples", samples);
        profile.put("weights", weights);

        Map<String, Object> speedscope = new LinkedHashMap<>();
        speedscope.put("$schema", "https://www.speedscope.app/file-format-spec.json");
        speedscope.put("version", "0.1.2");
        speedscope.put("exporter", "helix-cortex@1.0.0");
        speedscope.put("name", "Helix Flame Graph - " + targetMetric.name());
        speedscope.put("activeProfileIndex", 0);
        speedscope.put("shared", Map.of("frames", sharedFrames));
        speedscope.put("profiles", List.of(profile));

        return speedscope;
    }

    /**
     * Generates a self-contained interactive HTML flame graph.
     */
    public String getHtmlFlameGraph(MetricType metric) {
        MetricType targetMetric = metric != null ? metric : MetricType.CPU_TIME;
        String folded = getFoldedText(targetMetric);
        return htmlGenerator.generateHtmlFlameGraph(folded, "Helix Flame Graph - " + targetMetric.name());
    }

    /**
     * Returns a comprehensive FlameGraphResponse for REST API consumers.
     */
    public FlameGraphResponse getFlameGraphResponse(MetricType metric) {
        MetricType targetMetric = metric != null ? metric : MetricType.CPU_TIME;
        String foldedText = getFoldedText(targetMetric);
        List<String> foldedLines = (foldedText == null || foldedText.isBlank()) ?
                Collections.emptyList() :
                Arrays.stream(foldedText.split("\\r?\\n"))
                        .filter(l -> !l.isBlank())
                        .toList();

        long totalSamples = aggregator != null ? aggregator.getTotalSamples(targetMetric) : 0L;
        int distinctStacks = aggregator != null ? aggregator.getDistinctStacksCount(targetMetric) : 0;
        int maxDepth = aggregator != null ? aggregator.getMaxDepth(targetMetric) : 0;
        FlameGraphNodeDto tree = getD3Tree(targetMetric);
        Map<String, Object> speedscope = getSpeedscopeJson(targetMetric);

        return new FlameGraphResponse(
                targetMetric.name(),
                targetMetric.getUnit(),
                totalSamples,
                distinctStacks,
                maxDepth,
                foldedLines,
                tree,
                speedscope,
                Instant.now()
        );
    }

    public FlameGraphAggregator getAggregator() {
        return aggregator;
    }

    public void setAggregator(FlameGraphAggregator aggregator) {
        this.aggregator = aggregator;
    }

    public SseBroadcaster getBroadcaster() {
        return broadcaster;
    }

    public void setBroadcaster(SseBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    public void setSse(Sse sse) {
        this.sse = sse;
    }
}
