package com.roomkeeper.common.idempotency;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Idempotency keys: a finished request's response is kept for {@code ttl}; a running request
 * holds its key for at most {@code lease}, so a request that crashes doesn't block its key
 * for the whole {@code ttl}.
 */
@ConfigurationProperties("roomkeeper.idempotency")
public record IdempotencyProperties(Duration ttl, Duration lease) {
}
