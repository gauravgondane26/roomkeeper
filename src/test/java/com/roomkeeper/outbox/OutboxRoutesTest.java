package com.roomkeeper.outbox;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Each event type goes to exactly one consumer, through that consumer's stream: the event
 * row then says whether its one consumer has acted on it (docs/outbox-streams.md).
 */
class OutboxRoutesTest {

	private static final Set<OutboxEventType> BOOKING = EnumSet.of(OutboxEventType.BOOKING_CONFIRMED,
			OutboxEventType.BOOKING_CANCELLED, OutboxEventType.WAITLIST_OFFERED);

	private static final Set<OutboxEventType> ACCOUNT = EnumSet.of(OutboxEventType.ACCOUNT_CREATED,
			OutboxEventType.SIGNUP_FOR_EXISTING_ACCOUNT);

	private final OutboxConsumer bookingEmails = consumer("booking-emails", BOOKING);

	private final OutboxConsumer accountEmails = consumer("account-emails", ACCOUNT);

	private final OutboxConsumer refunds = consumer("refunds", EnumSet.of(OutboxEventType.REFUND_DUE));

	@Test
	void routesEachTypeToItsConsumersStream() {
		OutboxRoutes routes = new OutboxRoutes(List.of(bookingEmails, accountEmails, refunds));

		assertThat(routes.consumerOf(OutboxEventType.BOOKING_CANCELLED)).isSameAs(bookingEmails);
		assertThat(routes.consumerOf(OutboxEventType.REFUND_DUE)).isSameAs(refunds);
		assertThat(OutboxRoutes.streamOf(refunds)).isEqualTo("outbox:refunds");
		assertThat(routes.consumers()).containsExactly(bookingEmails, accountEmails, refunds);
	}

	@Test
	void refusesATypeNoConsumerTakes() {
		assertThatIllegalStateException().isThrownBy(() -> new OutboxRoutes(List.of(bookingEmails, accountEmails)))
			.withMessageContaining("REFUND_DUE");
	}

	@Test
	void refusesATypeTwoConsumersTake() {
		OutboxConsumer alsoRefunds = consumer("refund-audit", EnumSet.of(OutboxEventType.REFUND_DUE));

		assertThatIllegalStateException()
			.isThrownBy(() -> new OutboxRoutes(List.of(bookingEmails, accountEmails, refunds, alsoRefunds)))
			.withMessageContaining("REFUND_DUE")
			.withMessageContaining("refunds")
			.withMessageContaining("refund-audit");
	}

	@Test
	void refusesTwoConsumersWithOneName() {
		OutboxConsumer sameName = consumer("refunds", EnumSet.noneOf(OutboxEventType.class));

		assertThatIllegalStateException()
			.isThrownBy(() -> new OutboxRoutes(List.of(bookingEmails, accountEmails, refunds, sameName)))
			.withMessageContaining("refunds");
	}

	private static OutboxConsumer consumer(String name, Set<OutboxEventType> types) {
		return new OutboxConsumer() {

			@Override
			public String name() {
				return name;
			}

			@Override
			public Set<OutboxEventType> types() {
				return types;
			}

			@Override
			public void consume(OutboxEvent event) {
			}

		};
	}

}
