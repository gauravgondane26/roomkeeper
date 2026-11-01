package com.roomkeeper.outbox;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.roomkeeper.common.metrics.BusinessMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisConnectionUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * A reader per consumer, each on its own virtual thread while live delivery is on: it waits
 * on its stream for up to roomkeeper.outbox.read-block at a time, and every
 * roomkeeper.outbox.claim-idle takes over entries left that long unacknowledged. Not on the
 * scheduler, whose one thread the other jobs share.
 * <p>
 * A blocking read runs on a dedicated Redis connection, so it can't hold up other commands.
 * Each reader keeps one bound to its thread rather than opening one per read. If Redis
 * fails, the reader logs it, drops its connection, and tries again a second later.
 * <p>
 * Every instance's readers join the same groups, named after the instance's host.
 */
@Component
public class OutboxStreamReaders implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(OutboxStreamReaders.class);

	private static final Duration BACKOFF = Duration.ofSeconds(1);

	private final List<OutboxStreamReader> readers = new ArrayList<>();

	private final RedisConnectionFactory connections;

	private final OutboxProperties properties;

	private final Clock clock;

	private final List<Thread> threads = new ArrayList<>();

	private volatile boolean running;

	OutboxStreamReaders(OutboxRoutes routes, StringRedisTemplate redis, OutboxDelivery delivery,
			RedisConnectionFactory connections, OutboxProperties properties, BusinessMetrics metrics, Clock clock) {
		this.connections = connections;
		this.properties = properties;
		this.clock = clock;
		String instance = instanceName();
		for (OutboxConsumer consumer : routes.consumers()) {
			readers.add(new OutboxStreamReader(consumer, instance, redis, delivery, metrics));
		}
	}

	/** Each reader handles its new entries once, without waiting; returns how many in all. */
	public int readOnce() {
		return readers.stream().mapToInt(reader -> reader.readNew(Duration.ZERO)).sum();
	}

	/** Each reader takes over and handles entries idle at least this long; returns how many in all. */
	public int claimIdle(Duration minIdle) {
		return readers.stream().mapToInt(reader -> reader.claimIdle(minIdle)).sum();
	}

	@Override
	public void start() {
		if (!properties.liveDelivery()) {
			return;
		}
		running = true;
		for (OutboxStreamReader reader : readers) {
			threads.add(Thread.ofVirtual().name("outbox-reader-" + reader.stream()).start(() -> loop(reader)));
		}
		log.info("Reading outbox streams {}", readers.stream().map(OutboxStreamReader::stream).toList());
	}

	@Override
	public void stop() {
		running = false;
		for (Thread thread : threads) {
			try {
				// A blocked read returns within read-block
				thread.join(properties.readBlock().plus(BACKOFF));
				thread.interrupt();
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
		threads.clear();
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	private void loop(OutboxStreamReader reader) {
		Instant lastClaim = clock.instant();
		boolean connected = false;
		try {
			while (running) {
				try {
					// Connecting can fail too (Redis still starting, a slow first connection): it's
					// retried like any other failure, so the reader's thread never dies of it
					if (!connected) {
						RedisConnectionUtils.bindConnection(connections);
						connected = true;
					}
					reader.readNew(properties.readBlock());
					if (!clock.instant().isBefore(lastClaim.plus(properties.claimIdle()))) {
						reader.claimIdle(properties.claimIdle());
						lastClaim = clock.instant();
					}
				}
				catch (RuntimeException ex) {
					if (!running) {
						return;
					}
					log.warn("Outbox reader of {} failed; trying again in {}", reader.stream(), BACKOFF, ex);
					// A fresh connection next time round, in case that one broke
					RedisConnectionUtils.unbindConnection(connections);
					connected = false;
					Thread.sleep(BACKOFF);
				}
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		finally {
			RedisConnectionUtils.unbindConnection(connections);
		}
	}

	private static String instanceName() {
		String host = System.getenv("HOSTNAME");
		if (host != null && !host.isBlank()) {
			return host;
		}
		try {
			return InetAddress.getLocalHost().getHostName();
		}
		catch (UnknownHostException ex) {
			return "roomkeeper";
		}
	}

}
