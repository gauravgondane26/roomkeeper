package com.roomkeeper.search;

import java.time.Duration;

import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import tools.jackson.databind.json.JsonMapper;

/**
 * Response caches in Redis: {@code hotel:{shape}:{id}:v{version}} for hotel info and
 * {@code search:{shape}:{city hash}:v{version}:{query}} for search results. Each cache
 * stores one response type as JSON; {@code shape} ({@link CacheShapes}) changes with what
 * that type holds, so a deploy that changes it starts with an empty cache rather than
 * reading entries in the old form. Postgres stays the source of truth: if Redis fails,
 * the error is logged and the call goes to the database.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CacheConfig implements CachingConfigurer {

	public static final String HOTEL_INFO = "hotel";

	public static final String SEARCH = "search";

	@Bean
	RedisCacheManager cacheManager(RedisConnectionFactory redis, CacheProperties properties, JsonMapper json) {
		RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
			.computePrefixWith(name -> name + ":")
			.disableCachingNullValues();
		return RedisCacheManager
			// Eviction is by key or version, never by pattern; if a cache is ever cleared, use SCAN
			// rather than KEYS so Redis isn't blocked
			.builder(RedisCacheWriter.nonLockingRedisCacheWriter(redis, BatchStrategies.scan(1000)))
			.cacheDefaults(defaults)
			.withCacheConfiguration(HOTEL_INFO,
					cacheOf(defaults, HotelInfoResponse.class, properties.hotelInfoTtl(), json))
			.withCacheConfiguration(SEARCH, cacheOf(defaults, HotelSearchResponse.class, properties.searchTtl(), json))
			.disableCreateOnMissingCache()
			// Hits and misses per cache, which Spring binds to Micrometer as cache.gets by result
			.enableStatistics()
			.build();
	}

	/** A cache of one response type, stored as JSON under {@code {name}:{shape}:}. */
	private static <T> RedisCacheConfiguration cacheOf(RedisCacheConfiguration defaults, Class<T> type, Duration ttl,
			JsonMapper json) {
		String shape = CacheShapes.of(type);
		return defaults.entryTtl(ttl)
			.computePrefixWith(name -> name + ":" + shape + ":")
			.serializeValuesWith(SerializationPair.fromSerializer(new JacksonJsonRedisSerializer<>(json, type)));
	}

	/** Cache failures are logged and skipped: a read falls through to Postgres. */
	@Override
	public CacheErrorHandler errorHandler() {
		return new LoggingCacheErrorHandler();
	}

}
