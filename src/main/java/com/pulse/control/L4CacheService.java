package com.pulse.control;

import com.helix.core.cache.l4.L4RedisConfig;
import com.helix.core.cache.l4.L4RedisRuleCache;
import com.helix.core.cache.l4.RedisCacheInvalidationListener;
import com.helix.core.cache.l4.RedisCacheInvalidator;
import com.pulse.boundary.dto.CacheMetricsResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Enterprise service managing the L4 Redis distributed rule cache lifecycle,
 * cluster invalidation broadcasting, and cache telemetry.
 */
@ApplicationScoped
public class L4CacheService {

    private static final Logger log = LoggerFactory.getLogger(L4CacheService.class);

    @Inject
    @ConfigProperty(name = "helix.cortex.cache.redis.host", defaultValue = "localhost")
    private String host;

    @Inject
    @ConfigProperty(name = "helix.cortex.cache.redis.port", defaultValue = "6379")
    private int port;

    @Inject
    @ConfigProperty(name = "helix.cortex.cache.redis.timeout", defaultValue = "2000")
    private int timeout;

    @Inject
    @ConfigProperty(name = "helix.cortex.cache.redis.ttl-seconds", defaultValue = "86400")
    private int ttlSeconds;

    @Inject
    @ConfigProperty(name = "helix.cortex.cache.redis.fallback-enabled", defaultValue = "true")
    private boolean fallbackEnabled;

    @Inject
    @ConfigProperty(name = "helix.cortex.cache.redis.pool.max-total", defaultValue = "32")
    private int maxTotal;

    private L4RedisRuleCache cache;
    private JedisPool jedisPool;
    private RedisCacheInvalidator invalidator;
    private RedisCacheInvalidationListener invalidationListener;
    private final Set<Consumer<String>> invalidationCallbacks = ConcurrentHashMap.newKeySet();

    public L4CacheService() {
    }

    public L4CacheService(String host, int port, int timeout, boolean fallbackEnabled) {
        this.host = host;
        this.port = port;
        this.timeout = timeout;
        this.ttlSeconds = 86400;
        this.fallbackEnabled = fallbackEnabled;
        this.maxTotal = 32;
        init();
    }

    @PostConstruct
    public void init() {
        log.info("Initializing L4 Redis Cache service connecting to {}:{}", host, port);
        L4RedisConfig config = new L4RedisConfig(host, port, timeout, maxTotal, ttlSeconds, fallbackEnabled);
        this.cache = new L4RedisRuleCache(config);

        if (!this.cache.isFallbackActive()) {
            try {
                JedisPoolConfig poolConfig = new JedisPoolConfig();
                poolConfig.setMaxTotal(maxTotal);
                this.jedisPool = new JedisPool(poolConfig, host, port, timeout);
                this.invalidator = new RedisCacheInvalidator(this.jedisPool);
                this.invalidationListener = new RedisCacheInvalidationListener(this.jedisPool, ruleName -> {
                    log.info("Received cluster cache invalidation for rule: {}", ruleName);
                    if (ruleName != null) {
                        try {
                            cache.invalidate(ruleName);
                        } catch (Exception ignored) {
                        }
                        for (Consumer<String> cb : invalidationCallbacks) {
                            try {
                                cb.accept(ruleName);
                            } catch (Exception e) {
                                log.warn("Error invoking invalidation callback: {}", e.getMessage());
                            }
                        }
                    }
                });
                this.invalidationListener.start();
            } catch (Exception e) {
                log.warn("Could not start Redis cache invalidator / listener: {}", e.getMessage());
            }
        } else {
            log.info("L4 Redis cache initialized in local fallback mode (Redis unreachable or fallback forced)");
        }
    }

    @PreDestroy
    public void destroy() {
        log.info("Shutting down L4 Redis Cache service");
        if (invalidationListener != null) {
            try {
                invalidationListener.close();
            } catch (Exception e) {
                log.warn("Error stopping invalidation listener: {}", e.getMessage());
            }
        }
        if (jedisPool != null && !jedisPool.isClosed()) {
            try {
                jedisPool.close();
            } catch (Exception e) {
                log.warn("Error closing JedisPool: {}", e.getMessage());
            }
        }
        if (cache != null) {
            try {
                cache.close();
            } catch (Exception e) {
                log.warn("Error closing L4 cache: {}", e.getMessage());
            }
        }
    }

    @Produces
    @ApplicationScoped
    public L4RedisRuleCache produceL4RedisRuleCache() {
        return getCache();
    }

    public L4RedisRuleCache getCache() {
        if (this.cache == null) {
            init();
        }
        return this.cache;
    }

    public boolean isConnected() {
        if (cache == null || cache.isFallbackActive() || jedisPool == null) {
            return false;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            return "PONG".equalsIgnoreCase(jedis.ping());
        } catch (Exception e) {
            return false;
        }
    }

    public long getHitCount() {
        return cache != null ? cache.getHitCount() : 0L;
    }

    public long getMissCount() {
        return cache != null ? cache.getMissCount() : 0L;
    }

    public double getHitRatio() {
        long hits = getHitCount();
        long misses = getMissCount();
        long total = hits + misses;
        return total > 0 ? (double) hits / total : 0.0;
    }

    public void invalidate(String ruleName) {
        invalidate(ruleName, null);
    }

    public void invalidate(String ruleName, String version) {
        Objects.requireNonNull(ruleName, "ruleName cannot be null");
        log.info("Invalidating L4 cache for rule '{}' (version: {})", ruleName, version);

        if (cache != null) {
            cache.invalidate(ruleName);
        }

        if (invalidator != null) {
            invalidator.broadcastInvalidation(ruleName);
        }

        for (Consumer<String> cb : invalidationCallbacks) {
            try {
                cb.accept(ruleName);
            } catch (Exception e) {
                log.warn("Error notifying local invalidation callback: {}", e.getMessage());
            }
        }
    }

    public void clear() {
        log.info("Clearing all L4 cache entries across cluster");
        if (cache != null) {
            cache.invalidateAll();
        }
        if (invalidator != null) {
            invalidator.broadcastInvalidation("__ALL__");
        }
        for (Consumer<String> cb : invalidationCallbacks) {
            try {
                cb.accept("__ALL__");
            } catch (Exception e) {
                log.warn("Error notifying local invalidation callback: {}", e.getMessage());
            }
        }
    }

    public void registerInvalidationListener(Consumer<String> callback) {
        if (callback != null) {
            invalidationCallbacks.add(callback);
        }
    }

    public CacheMetricsResponse getMetrics() {
        return new CacheMetricsResponse(
                getHitCount(),
                getMissCount(),
                isConnected(),
                getHitRatio(),
                cache != null && cache.isFallbackActive(),
                host != null ? host : "localhost",
                port > 0 ? port : 6379
        );
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }
}
