package com.roomkeeper.search;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code roomkeeper.cache}: how long cached responses live. Hotel details change rarely;
 * search results depend on availability, so they live briefly and are also evicted when a
 * hotel's inventory changes.
 */
@ConfigurationProperties("roomkeeper.cache")
public record CacheProperties(Duration hotelInfoTtl, Duration searchTtl) {
}
