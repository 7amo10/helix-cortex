package com.pulse.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.NamedAttributeNode;
import jakarta.persistence.NamedEntityGraph;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * JPA entity capturing streamed rule execution results, latency metrics,
 * and associated status for batch persistence into PostgreSQL.
 */
@Entity
@Table(name = "stream_execution_record", indexes = {
        @Index(name = "idx_stream_exec_event_id", columnList = "event_id"),
        @Index(name = "idx_stream_exec_topic", columnList = "topic")
})
@NamedEntityGraph(
        name = "StreamExecutionRecord.metrics",
        attributeNodes = @NamedAttributeNode("metrics")
)
public class StreamExecutionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", length = 100, nullable = false)
    private String eventId;

    @Column(name = "topic", length = 150, nullable = false)
    private String topic;

    @Column(name = "rule_name", length = 150)
    private String ruleName;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "result_payload", columnDefinition = "TEXT")
    private String resultPayload;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "execution_time_nanos", nullable = false)
    private long executionTimeNanos;

    @JsonSerialize(using = ToStringSerializer.class)
    @Column(name = "persisted_at", nullable = false)
    private Instant persistedAt;

    @BatchSize(size = 25)
    @OneToMany(mappedBy = "record", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<StreamRecordMetric> metrics = new ArrayList<>();

    public StreamExecutionRecord() {
        this.persistedAt = Instant.now();
    }

    public StreamExecutionRecord(String eventId, String topic, String ruleName, boolean success,
                                 String resultPayload, String errorMessage, long executionTimeNanos) {
        this();
        this.eventId = eventId;
        this.topic = topic;
        this.ruleName = ruleName;
        this.success = success;
        this.resultPayload = resultPayload;
        this.errorMessage = errorMessage;
        this.executionTimeNanos = executionTimeNanos;
    }

    public void addMetric(StreamRecordMetric metric) {
        if (metric != null) {
            metrics.add(metric);
            metric.setRecord(this);
        }
    }

    public void addMetric(String key, Double metricValue, String stringValue) {
        addMetric(new StreamRecordMetric(this, key, metricValue, stringValue));
    }

    public void removeMetric(StreamRecordMetric metric) {
        if (metric != null) {
            metrics.remove(metric);
            metric.setRecord(null);
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getResultPayload() {
        return resultPayload;
    }

    public void setResultPayload(String resultPayload) {
        this.resultPayload = resultPayload;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public long getExecutionTimeNanos() {
        return executionTimeNanos;
    }

    public void setExecutionTimeNanos(long executionTimeNanos) {
        this.executionTimeNanos = executionTimeNanos;
    }

    public Instant getPersistedAt() {
        return persistedAt;
    }

    public void setPersistedAt(Instant persistedAt) {
        this.persistedAt = persistedAt;
    }

    public List<StreamRecordMetric> getMetrics() {
        return Collections.unmodifiableList(metrics);
    }

    public void setMetrics(List<StreamRecordMetric> metrics) {
        this.metrics = metrics != null ? metrics : new ArrayList<>();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StreamExecutionRecord that = (StreamExecutionRecord) o;
        return Objects.equals(id, that.id) || (eventId != null && Objects.equals(eventId, that.eventId));
    }

    @Override
    public int hashCode() {
        return eventId != null ? Objects.hash(eventId) : Objects.hash(id);
    }
}
