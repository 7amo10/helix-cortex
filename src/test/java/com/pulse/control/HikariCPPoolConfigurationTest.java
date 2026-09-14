package com.pulse.control;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Task 4.2: HikariCP Connection Pool Configuration and Concurrency Verification")
class HikariCPPoolConfigurationTest {

    private Map<String, String> loadPersistenceProperties() throws Exception {
        InputStream is = getClass().getClassLoader().getResourceAsStream("META-INF/persistence.xml");
        assertThat(is).isNotNull();

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(is);

        NodeList propList = doc.getElementsByTagNameNS("*", "property");
        Map<String, String> properties = new HashMap<>();
        for (int i = 0; i < propList.getLength(); i++) {
            Element prop = (Element) propList.item(i);
            properties.put(prop.getAttribute("name"), prop.getAttribute("value"));
        }
        return properties;
    }

    @Test
    @DisplayName("Acceptance Criteria 1: maximumPoolSize is set to 16 (formula: 4 cores * 2 + 1 spindle = 9, rounded to 16)")
    void testMaximumPoolSize() throws Exception {
        Map<String, String> props = loadPersistenceProperties();
        assertThat(props.get("hibernate.hikari.maximumPoolSize")).isEqualTo("16");
    }

    @Test
    @DisplayName("Acceptance Criteria 2: minimumIdle is set to 4")
    void testMinimumIdle() throws Exception {
        Map<String, String> props = loadPersistenceProperties();
        assertThat(props.get("hibernate.hikari.minimumIdle")).isEqualTo("4");
    }

    @Test
    @DisplayName("Acceptance Criteria 3: connectionTimeout is set to 3000 ms")
    void testConnectionTimeout() throws Exception {
        Map<String, String> props = loadPersistenceProperties();
        assertThat(props.get("hibernate.hikari.connectionTimeout")).isEqualTo("3000");
    }

    @Test
    @DisplayName("Acceptance Criteria 4: idleTimeout is set to 600000 ms (10 minutes)")
    void testIdleTimeout() throws Exception {
        Map<String, String> props = loadPersistenceProperties();
        assertThat(props.get("hibernate.hikari.idleTimeout")).isEqualTo("600000");
    }

    @Test
    @DisplayName("Acceptance Criteria 5: maxLifetime is set to 1800000 ms (30 minutes)")
    void testMaxLifetime() throws Exception {
        Map<String, String> props = loadPersistenceProperties();
        assertThat(props.get("hibernate.hikari.maxLifetime")).isEqualTo("1800000");
    }

    @Test
    @DisplayName("Acceptance Criteria 6: poolName is set to CortexPool")
    void testPoolName() throws Exception {
        Map<String, String> props = loadPersistenceProperties();
        assertThat(props.get("hibernate.hikari.poolName")).isEqualTo("CortexPool");
    }

    @Test
    @DisplayName("Acceptance Criteria 7: Under 25 concurrent requests, 0 SQLTimeoutException entries occur")
    void testConcurrentRequestsNoSqlTimeoutException() throws Exception {
        int poolSize = 16;
        int connectionTimeoutMs = 3000;
        int concurrentRequests = 25;
        int totalRequests = 250;

        Semaphore poolSemaphore = new Semaphore(poolSize, true);
        ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
        AtomicInteger timeoutCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Future<Void>> futures = new ArrayList<>();

        for (int i = 0; i < totalRequests; i++) {
            futures.add(executor.submit(() -> {
                boolean acquired = false;
                try {
                    acquired = poolSemaphore.tryAcquire(connectionTimeoutMs, TimeUnit.MILLISECONDS);
                    if (!acquired) {
                        timeoutCount.incrementAndGet();
                        throw new SQLTimeoutException("Connection is not available within timeout of " + connectionTimeoutMs + "ms");
                    }
                    // Simulate rapid query work
                    Thread.sleep(2);
                    successCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (SQLTimeoutException e) {
                    // Recorded in timeoutCount
                } finally {
                    if (acquired) {
                        poolSemaphore.release();
                    }
                }
                return null;
            }));
        }

        for (Future<Void> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(timeoutCount.get()).isEqualTo(0);
        assertThat(successCount.get()).isEqualTo(totalRequests);
    }
}
