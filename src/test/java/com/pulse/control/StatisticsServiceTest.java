package com.pulse.control;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StatisticsService Unit Tests")
class StatisticsServiceTest {

    @Mock
    private EntityManager em;

    @Mock
    private Session session;

    @Mock
    private SessionFactory sessionFactory;

    @Mock
    private Statistics statistics;

    private StatisticsService statisticsService;

    @BeforeEach
    void setUp() {
        statisticsService = new StatisticsService(em);
    }

    @Test
    @DisplayName("getPrepareStatementCount returns count from Hibernate Statistics")
    void testGetPrepareStatementCount() {
        when(em.unwrap(Session.class)).thenReturn(session);
        when(session.getSessionFactory()).thenReturn(sessionFactory);
        when(sessionFactory.getStatistics()).thenReturn(statistics);
        when(statistics.getPrepareStatementCount()).thenReturn(5L);

        long count = statisticsService.getPrepareStatementCount();
        assertThat(count).isEqualTo(5L);
    }

    @Test
    @DisplayName("getPrepareStatementCount returns 0 when EntityManager is null or unwrapping fails")
    void testGetPrepareStatementCount_fallback() {
        StatisticsService emptyService = new StatisticsService();
        assertThat(emptyService.getPrepareStatementCount()).isEqualTo(0L);

        when(em.unwrap(Session.class)).thenThrow(new RuntimeException("Not a Hibernate session"));
        assertThat(statisticsService.getPrepareStatementCount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("clearStatistics invokes clear on Hibernate Statistics")
    void testClearStatistics() {
        when(em.unwrap(Session.class)).thenReturn(session);
        when(session.getSessionFactory()).thenReturn(sessionFactory);
        when(sessionFactory.getStatistics()).thenReturn(statistics);

        statisticsService.clearStatistics();
        verify(statistics).clear();
    }

    @Test
    @DisplayName("measureStatements executes supplier and measures statement count difference")
    void testMeasureStatements() {
        when(em.unwrap(Session.class)).thenReturn(session);
        when(session.getSessionFactory()).thenReturn(sessionFactory);
        when(sessionFactory.getStatistics()).thenReturn(statistics);
        when(statistics.getPrepareStatementCount()).thenReturn(2L, 3L);

        List<String> result = statisticsService.measureStatements("testFindAll", () -> List.of("session1", "session2"));
        assertThat(result).containsExactly("session1", "session2");
        verify(statistics, times(2)).getPrepareStatementCount();
    }
}
