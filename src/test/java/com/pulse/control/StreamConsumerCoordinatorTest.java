package com.pulse.control;

import com.helix.api.stream.StreamResult;
import com.helix.core.stream.kafka.KafkaEventCodec;
import com.pulse.entity.StreamExecutionRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Task: Stream Consumer Coordinator Unit Tests")
class StreamConsumerCoordinatorTest {

    private StreamExecutionRepository repository;
    private MockConsumer<String, byte[]> mockConsumer;
    private StreamConsumerCoordinator coordinator;

    @BeforeEach
    void setUp() {
        repository = mock(StreamExecutionRepository.class);
        mockConsumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        coordinator = new StreamConsumerCoordinator(mockConsumer, repository, 5, 50);
    }

    @Test
    @DisplayName("Should process Kafka result records, deserialize them, and buffer until batch size")
    @SuppressWarnings("unchecked")
    void testProcessRecordsBatchesCorrectly() {
        when(repository.saveBatch(anyList(), anyInt())).thenAnswer(invocation -> {
            List<StreamExecutionRecord> list = invocation.getArgument(0);
            return list.size();
        });

        List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            StreamResult result = StreamResult.success("ev-" + i, "rules.results", "TestRule", "APPROVED", 1200L);
            byte[] payload = KafkaEventCodec.serializeResult(result);
            records.add(new ConsumerRecord<>("rules.results", 0, (long) i, "ev-" + i, payload));
        }

        coordinator.processRecords(records);

        ArgumentCaptor<List<StreamExecutionRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository, times(1)).saveBatch(captor.capture(), anyInt());

        List<StreamExecutionRecord> persisted = captor.getValue();
        assertThat(persisted).hasSize(5);
        assertThat(persisted.get(0).getEventId()).isEqualTo("ev-0");
        assertThat(persisted.get(0).isSuccess()).isTrue();
        assertThat(persisted.get(0).getMetrics()).isNotEmpty();

        assertThat(coordinator.getTotalConsumed()).isEqualTo(5L);
        assertThat(coordinator.getTotalPersisted()).isEqualTo(5L);
        assertThat(coordinator.getBatchesPersisted()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Should flush partial buffer on direct flush")
    @SuppressWarnings("unchecked")
    void testPartialBufferFlushDirect() {
        when(repository.saveBatch(anyList(), anyInt())).thenAnswer(invocation -> {
            List<StreamExecutionRecord> list = invocation.getArgument(0);
            return list.size();
        });

        List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            StreamResult result = StreamResult.failure("ev-fail-" + i, "rules.results", "RuleF",
                    new RuntimeException("Simulated error"), 800L);
            byte[] payload = KafkaEventCodec.serializeResult(result);
            records.add(new ConsumerRecord<>("rules.results", 0, (long) i, "ev-" + i, payload));
        }

        coordinator.processRecords(records);
        // Batch size is 5, so 3 records remain in buffer
        verify(repository, times(0)).saveBatch(anyList(), anyInt());

        // Now flush directly
        coordinator.flushDirect();

        verify(repository, times(1)).saveBatch(anyList(), anyInt());
        assertThat(coordinator.getTotalConsumed()).isEqualTo(3L);
        assertThat(coordinator.getTotalPersisted()).isEqualTo(3L);
    }
}
