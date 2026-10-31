package com.roomkeeper.outbox;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import com.roomkeeper.common.metrics.BusinessMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * One consumer's reader, a member of its consumer group: reads entries from the consumer's
 * stream, delivers the events they name, and acknowledges each entry once its delivery is
 * settled. An entry left unacknowledged (its delivery failed, or its reader died) is taken
 * over by {@link #claimIdle} once it's been idle long enough, by this reader or another
 * instance's.
 * <p>
 * The group is created at the stream's first entry, so a stream that was lost and refilled
 * by resends is read from its start: when Redis says there's no such group, it's created and
 * the read tried again.
 */
final class OutboxStreamReader {

	private static final Logger log = LoggerFactory.getLogger(OutboxStreamReader.class);

	/** Entries read or claimed at a time. */
	static final int COUNT = 50;

	private final String stream;

	private final String group;

	private final Consumer member;

	private final StringRedisTemplate redis;

	private final OutboxDelivery delivery;

	private final BusinessMetrics metrics;

	OutboxStreamReader(OutboxConsumer consumer, String instance, StringRedisTemplate redis, OutboxDelivery delivery,
			BusinessMetrics metrics) {
		this.stream = OutboxRoutes.streamOf(consumer);
		this.group = consumer.name();
		this.member = Consumer.from(group, instance);
		this.redis = redis;
		this.delivery = delivery;
		this.metrics = metrics;
		metrics.outboxStream(group, this::pending, () -> redis.opsForStream().size(stream));
	}

	String stream() {
		return stream;
	}

	/**
	 * Handles the entries no reader of the group has had yet, waiting up to {@code block} for
	 * some to arrive ({@link Duration#ZERO}: not at all). Returns how many it handled.
	 */
	int readNew(Duration block) {
		StreamReadOptions options = StreamReadOptions.empty().count(COUNT);
		if (!block.isZero()) {
			options = options.block(block);
		}
		StreamReadOptions read = options;
		return handle(inGroup(() -> redis.opsForStream().read(member, read,
				StreamOffset.create(stream, ReadOffset.lastConsumed()))));
	}

	/**
	 * Takes over the entries read at least {@code minIdle} ago and still unacknowledged, and
	 * handles them. The claim only succeeds if they're still that idle, so readers claiming
	 * at once take each entry once. Returns how many it handled.
	 */
	int claimIdle(Duration minIdle) {
		PendingMessages idle = inGroup(() -> redis.opsForStream().pending(stream, group, Range.unbounded(), COUNT, minIdle));
		if (idle == null || idle.isEmpty()) {
			return 0;
		}
		RecordId[] ids = idle.stream().map(PendingMessage::getId).toArray(RecordId[]::new);
		int claimed = handle(redis.opsForStream().claim(stream, group, member.getName(), minIdle, ids));
		metrics.outboxClaimed(claimed);
		return claimed;
	}

	/** Entries read and not yet acknowledged, by any member of the group. */
	long pending() {
		return inGroup(() -> redis.opsForStream().pending(stream, group)).getTotalPendingMessages();
	}

	private int handle(List<MapRecord<String, Object, Object>> entries) {
		if (entries == null) {
			return 0;
		}
		for (MapRecord<String, Object, Object> entry : entries) {
			OutboxDelivery.Outcome outcome = deliver(entry);
			log.debug("Entry {} in {} (event {}): {}", entry.getId(), stream, entry.getValue().get("eventId"), outcome);
			if (outcome.letsGo()) {
				redis.opsForStream().acknowledge(stream, group, entry.getId());
			}
		}
		return entries.size();
	}

	private OutboxDelivery.Outcome deliver(MapRecord<String, Object, Object> entry) {
		Object eventId = entry.getValue().get("eventId");
		try {
			return delivery.deliver(Long.parseLong(String.valueOf(eventId)));
		}
		catch (NumberFormatException ex) {
			log.warn("Stream entry {} in {} names no outbox event ({}); dropped", entry.getId(), stream, eventId);
			return OutboxDelivery.Outcome.UNKNOWN;
		}
	}

	/** Runs a command on the group, creating the group (and stream) first if Redis has lost it. */
	private <T> T inGroup(Supplier<T> command) {
		try {
			return command.get();
		}
		catch (DataAccessException ex) {
			if (!saysNoGroup(ex)) {
				throw ex;
			}
			createGroup();
			return command.get();
		}
	}

	private void createGroup() {
		try {
			// From the stream's start: anything already in it (resends, say) is still to be read
			redis.opsForStream().createGroup(stream, ReadOffset.from("0"), group);
			log.info("Created consumer group {} on {}", group, stream);
		}
		catch (DataAccessException ex) {
			if (!says(ex, "BUSYGROUP")) {
				throw ex;
			}
			// Another reader just created it
		}
	}

	private static boolean saysNoGroup(Throwable ex) {
		return says(ex, "NOGROUP");
	}

	private static boolean says(Throwable ex, String error) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause.getMessage() != null && cause.getMessage().contains(error)) {
				return true;
			}
		}
		return false;
	}

}
