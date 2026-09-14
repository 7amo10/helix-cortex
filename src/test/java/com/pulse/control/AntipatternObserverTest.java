package com.pulse.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AntipatternObserverTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    private AntipatternObserver observer;

    @BeforeEach
    void setUp() {
        observer = new AntipatternObserver(auditLogRepository);
    }

    @Test
    @DisplayName("AntipatternObserver is annotated with @ApplicationScoped")
    void testClassAnnotation() {
        assertThat(AntipatternObserver.class.isAnnotationPresent(ApplicationScoped.class)).isTrue();
    }

    @Test
    @DisplayName("onAntipattern method parameter is annotated with @ObservesAsync and not @Observes")
    void testMethodAnnotations() throws NoSuchMethodException {
        Method method = AntipatternObserver.class.getMethod("onAntipattern", RuleAntipatternEvent.class);
        assertThat(method.getReturnType()).isEqualTo(void.class);

        Annotation[][] paramAnnotations = method.getParameterAnnotations();
        assertThat(paramAnnotations).isNotEmpty();
        assertThat(paramAnnotations[0]).isNotEmpty();

        boolean hasObservesAsync = false;
        boolean hasObserves = false;
        for (Annotation a : paramAnnotations[0]) {
            if (a.annotationType().equals(ObservesAsync.class)) {
                hasObservesAsync = true;
            }
            if (a.annotationType().equals(Observes.class)) {
                hasObserves = true;
            }
        }

        assertThat(hasObservesAsync).isTrue();
        assertThat(hasObserves).isFalse();
    }

    @Test
    @DisplayName("onAntipattern logs alert and calls auditLogRepository.logAsync")
    void testOnAntipatternProcessesEvent() {
        Instant now = Instant.now();
        RuleAntipatternEvent event = new RuleAntipatternEvent(
                "com.pulse.rule.BadService",
                AntipatternType.STRING_CONCAT_LOOP,
                now,
                15L
        );

        observer.onAntipattern(event);

        verify(auditLogRepository, times(1)).logAsync(
                eq("SYSTEM"),
                contains("com.pulse.rule.BadService"),
                eq("EVENT"),
                eq(true)
        );
    }

    @Test
    @DisplayName("onAntipattern gracefully handles null event without exception")
    void testOnAntipatternNullEvent() {
        observer.onAntipattern(null);
        verifyNoInteractions(auditLogRepository);
    }
}
