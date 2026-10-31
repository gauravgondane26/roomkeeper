package com.roomkeeper.outbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code roomkeeper.outbox}: how events travel from the outbox table through a Redis stream
 * per consumer (docs/outbox-streams.md).
 *
 * @param relayInterval how often the relay sends unsent events whatever happens; a commit
 * normally wakes it first
 * @param readBlock how long a reader waits on its stream for new entries; under the Redis
 * command timeout (checked at startup)
 * @param claimIdle how long an entry sits unacknowledged before a reader takes it over: its
 * reader died, or its consumer failed and it's tried again
 * @param resendAfter how long a sent event waits to be published before it's sent again, in
 * case its stream lost it
 * @param maxStreamLength roughly how many entries each stream keeps
 * @param liveDelivery whether readers run on their own threads and each commit wakes the
 * relay; off in tests, which deliver by hand
 */
@ConfigurationProperties("roomkeeper.outbox")
public record OutboxProperties(Duration relayInterval, Duration readBlock, Duration claimIdle, Duration resendAfter,
		int maxStreamLength, boolean liveDelivery) {

}
