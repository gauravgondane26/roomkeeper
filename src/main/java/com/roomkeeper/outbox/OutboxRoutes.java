package com.roomkeeper.outbox;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Which consumer, and so which stream, each event type goes to. Exactly one consumer per
 * type, checked at startup: the event row records whether that one consumer has acted on
 * it. A second consumer for a type would need delivery tracked per consumer instead.
 */
@Component
public class OutboxRoutes {

	private final List<OutboxConsumer> consumers;

	private final Map<OutboxEventType, OutboxConsumer> byType = new EnumMap<>(OutboxEventType.class);

	public OutboxRoutes(List<OutboxConsumer> consumers) {
		this.consumers = List.copyOf(consumers);
		Set<String> names = new HashSet<>();
		for (OutboxConsumer consumer : consumers) {
			if (!names.add(consumer.name())) {
				throw new IllegalStateException("Two outbox consumers are named " + consumer.name());
			}
			for (OutboxEventType type : consumer.types()) {
				OutboxConsumer other = byType.putIfAbsent(type, consumer);
				if (other != null) {
					throw new IllegalStateException(type + " events have two consumers, " + other.name() + " and "
							+ consumer.name() + "; each type goes to one");
				}
			}
		}
		for (OutboxEventType type : OutboxEventType.values()) {
			if (!byType.containsKey(type)) {
				throw new IllegalStateException("No outbox consumer takes " + type + " events");
			}
		}
	}

	/** The one consumer of this type's events. */
	public OutboxConsumer consumerOf(OutboxEventType type) {
		return byType.get(type);
	}

	public List<OutboxConsumer> consumers() {
		return consumers;
	}

	/** The Redis stream this consumer reads; its consumer group has the consumer's name. */
	public static String streamOf(OutboxConsumer consumer) {
		return "outbox:" + consumer.name();
	}

}
