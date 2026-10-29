package com.roomkeeper.outbox;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static com.roomkeeper.testsupport.Concurrently.runTogether;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The publisher hands each waiting event to its consumers, at least once; the consumers
 * (here the confirmation email) tolerate getting one twice. Every test writes its own
 * events: other tests leave theirs in the shared table, and the publisher sends those too.
 */
class OutboxPublisherIT extends AbstractIntegrationTest {

	@Autowired
	OutboxPublisher publisher;

	@Autowired
	OutboxWriter outbox;

	@Autowired
	FakeEmailSender emails;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	StringRedisTemplate redis;

	private TestUser guest;

	private long guestId;

	@BeforeEach
	void signUpAGuest() {
		guest = aUser().build();
		guestId = signUp(guest);
	}

	@Test
	void sendsEachWaitingEventToItsConsumerOnce() {
		long first = confirm("Seaside Inn");
		long second = confirm("Hill View");

		publisher.run();
		publisher.run();

		assertThat(emails.sentTo(guest.email())).extracting(Email::subject)
			.containsExactly("Your stay at Seaside Inn is confirmed", "Your stay at Hill View is confirmed");
		assertThat(jdbc.queryForList("select published_at is not null from outbox_events where id in (?, ?)",
				Boolean.class, first, second))
			.containsOnly(true);
	}

	@Test
	void anEventDeliveredAgainSendsNoSecondEmail() {
		long event = confirm("Seaside Inn");
		publisher.run();
		// As if that run had sent the email and then died before its commit
		jdbc.update("update outbox_events set published_at = null where id = ?", event);

		publisher.run();

		assertThat(emails.deliveriesOf("outbox-" + event)).isEqualTo(2);
		assertThat(emails.sentTo(guest.email())).hasSize(1);
		assertThat(jdbc.queryForObject("select published_at is not null from outbox_events where id = ?",
				Boolean.class, event))
			.isTrue();
	}

	@Test
	void anEventThatKeepsFailingIsParkedWhileTheRestGoOut() {
		// Its guest's account is gone, so the email has nowhere to go
		long broken = write(new BookingConfirmed(newBookingId(), -1L, "Seaside Inn", "Deluxe", LocalDate.parse("2026-12-24"),
				LocalDate.parse("2026-12-26"), 1, new BigDecimal("8000.00"), "inr"));
		long fine = confirm("Hill View");

		publisher.run();

		assertThat(state(broken)).containsEntry("attempts", 1).containsEntry("parked", false);
		assertThat((String) state(broken).get("last_error")).contains("User -1 not found");
		assertThat(state(fine)).containsEntry("published", true);

		for (int run = 2; run <= 11; run++) {
			publisher.run();
		}

		assertThat(state(broken))
			.containsEntry("attempts", OutboxEvent.MAX_ATTEMPTS)
			.containsEntry("parked", true)
			.containsEntry("published", false);
	}

	@Test
	void runsThatOverlapStillSendEachEventOnce() throws Exception {
		// A run that outlives its lock's lease can overlap the next; each locks the events it sends
		List<Long> events = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			events.add(confirm("Hotel " + i));
		}
		List<Callable<Integer>> runs = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			runs.add(publisher::publishWaiting);
		}

		runTogether(runs);

		assertThat(events).allSatisfy(event -> assertThat(emails.deliveriesOf("outbox-" + event)).isEqualTo(1));
		assertThat(emails.sentTo(guest.email())).hasSize(20);
	}

	@Test
	void skipsTheRunWhileAnotherInstanceHoldsTheLock() {
		long event = confirm("Seaside Inn");

		redis.opsForValue().set("lock:outbox", "another-instance", Duration.ofSeconds(30));
		try {
			publisher.run();
			assertThat(emails.sentTo(guest.email())).isEmpty();
		}
		finally {
			redis.delete("lock:outbox");
		}
		publisher.run();

		assertThat(emails.deliveriesOf("outbox-" + event)).isEqualTo(1);
	}

	/** Writes a confirmation for the guest at this hotel; returns the outbox event's id. */
	private long confirm(String hotel) {
		return write(new BookingConfirmed(newBookingId(), guestId, hotel, "Deluxe", LocalDate.parse("2026-12-24"),
				LocalDate.parse("2026-12-26"), 1, new BigDecimal("8000.00"), "inr"));
	}

	private long write(BookingConfirmed confirmation) {
		transaction.executeWithoutResult(status -> outbox.write(confirmation));
		return jdbc.queryForObject("select id from outbox_events where type = 'BOOKING_CONFIRMED' and aggregate_id = ?",
				Long.class, confirmation.bookingId());
	}

	private Map<String, Object> state(long event) {
		return jdbc.queryForMap("""
				select attempts, last_error, failed_at is not null as parked, published_at is not null as published
				from outbox_events where id = ?""", event);
	}

	private static long newBookingId() {
		return ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
	}

}
