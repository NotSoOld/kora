package ru.tinkoff.kora.cache.caffeine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.StatsCounter;
import com.typesafe.config.Config;
import ru.tinkoff.kora.application.graph.TypeRef;
import ru.tinkoff.kora.cache.telemetry.CacheMetrics;
import ru.tinkoff.kora.cache.telemetry.CacheTelemetry;
import ru.tinkoff.kora.cache.telemetry.CacheTracer;
import ru.tinkoff.kora.cache.telemetry.DefaultCacheTelemetry;
import ru.tinkoff.kora.common.DefaultComponent;
import ru.tinkoff.kora.common.Tag;
import ru.tinkoff.kora.config.common.extractor.ConfigValueExtractor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;

public interface CaffeineCacheModule {

    @DefaultComponent
    default CacheTelemetry defaultCacheTelemetry(@Nullable CacheMetrics metrics, @Nullable CacheTracer tracer) {
        return new DefaultCacheTelemetry(metrics, tracer);
    }

    @DefaultComponent
    default CaffeineCacheFactory caffeineCacheFactory() {
        return new CaffeineCacheFactory() {
            @Nonnull
            @Override
            public <K, V> Cache<K, V> build(@Nonnull CaffeineCacheConfig config) {
                final Caffeine<K, V> builder = (Caffeine<K, V>) Caffeine.newBuilder();
                if (config.expireAfterWrite() != null)
                    builder.expireAfterWrite(config.expireAfterWrite());
                if (config.expireAfterAccess() != null)
                    builder.expireAfterAccess(config.expireAfterAccess());
                if (config.initialSize() != null)
                    builder.initialCapacity(config.initialSize());
                if (config.maximumSize() != null)
                    builder.maximumSize(config.maximumSize());
                return builder.recordStats(StatsCounter::disabledStatsCounter).build();
            }
        };
    }
}
