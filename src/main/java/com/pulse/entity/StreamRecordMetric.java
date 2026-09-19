package com.pulse.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * Child entity capturing granular metadata and metrics associated with a StreamExecutionRecord.
 */
@Entity
@Table(name = "stream_record_metric", indexes = {
        @Index(name = "idx_stream_metric_key", columnList = "metric_key")
})
public class StreamRecordMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "record_id", nullable = false)
    private StreamExecutionRecord record;

    @Column(name = "metric_key", length = 100, nullable = false)
    private String metricKey;

    @Column(name = "metric_value")
    private Double metricValue;

    @Column(name = "string_value", length = 255)
    private String stringValue;

    public StreamRecordMetric() {
    }

    public StreamRecordMetric(StreamExecutionRecord record, String metricKey, Double metricValue, String stringValue) {
        this.record = record;
        this.metricKey = metricKey;
        this.metricValue = metricValue;
        this.stringValue = stringValue;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public StreamExecutionRecord getRecord() {
        return record;
    }

    public void setRecord(StreamExecutionRecord record) {
        this.record = record;
    }

    public String getMetricKey() {
        return metricKey;
    }

    public void setMetricKey(String metricKey) {
        this.metricKey = metricKey;
    }

    public Double getMetricValue() {
        return metricValue;
    }

    public void setMetricValue(Double metricValue) {
        this.metricValue = metricValue;
    }

    public String getStringValue() {
        return stringValue;
    }

    public void setStringValue(String stringValue) {
        this.stringValue = stringValue;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StreamRecordMetric that = (StreamRecordMetric) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
