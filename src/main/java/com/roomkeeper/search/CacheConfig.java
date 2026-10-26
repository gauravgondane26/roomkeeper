package com.roomkeeper.search;

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
 * Response caches in Redis: {@code hotel:{id}} for hotel info and
 * {@code search:{city hash}:v{version}:{query}} for search results. Each cache stores one
 * response type as JSON. Postgres stays the source of truth: if Redis fails, the error is
 * logged and the call goes to the database.
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
			// SCAN rather than KEYS when a cache is cleared by pattern, so Redis isn't blocked
			.builder(RedisCacheWriter.nonLockingRedisCacheWriter(redis, BatchStrategies.scan(1000)))
			.cacheDefaults(defaults)
			.withCacheConfiguration(HOTEL_INFO, defaults.entryTtl(properties.hotelInfoTtl())
				.serializeValuesWith(SerializationPair.fromSerializer(
						new JacksonJsonRedisSerializer<>(json, HotelInfoResponse.class))))
			.withCacheConfiguration(SEARCH, defaults.entryTtl(properties.searchTtl())
				.serializeValuesWith(SerializationPair.fromSerializer(
						new JacksonJsonRedisSerializer<>(json, HotelSearchResponse.class))))
			.disableCreateOnMissingCache()
			.build();
	}

	/** Cache failures are logged and skipped: a read falls through to Postgres. */
	@Override
	public CacheErrorHandler errorHandler() {
		return new LoggingCacheErrorHandler();
	}

}
