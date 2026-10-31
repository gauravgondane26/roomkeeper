package com.roomkeeper.outbox;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.testsupport.MutableClockConfig;
import com.roomkeeper.testsupport.RaceTest;
import com.roomkeeper.testsupport.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static com.roomkeeper.testsupport.Concurrently.runTogether;
import static com.roomkeeper.testsupport.Containers.REDIS;
import static com.roomkeeper.testsupport.Containers.whilePaused;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Outbox events travel through a Redis stream per consumer, and Postgres decides what's been
 * delivered: whatever Redis loses, or a reader drops, is sent again, and an event handed over
 * twice is acted on once (docs/outbox-streams.md). The tests drive the relay, readers and
 * sweeper by hand (live delivery is off in tests); OutboxStreamLiveIT runs the real threads.
 * <p>
 * Each test writes its own events and checks only those: other tests leave theirs in the
 * shared table, and the relay sends those too. Redis is emptied before every test.
 */
@Import(MutableClockConfig.class)
class OutboxStreamIT extends AbstractIntegrationTest {

	private static final String BOOKING_EMAILS = "outbox:booking-emails";

	@Autowired
	OutboxRelay relay;

	@Autowired
	OutboxStreamReaders readers;

	@Autowired
	OutboxDelivery delivery;

	@Autowired
	OutboxSweeper sweeper;

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

	@Autowired
	MutableClock clock;

	private TestUser guest;

	private long guestId;

	@BeforeEach
	void signUpAGuest() {
		guest = aUser().build();
		guestId = signUp(guest);
	}

	@Test
	void eachEventGoesThroughItsConsumersStreamAndIsDeliveredOnce() {
		long first = confirm("Seaside Inn");
		long second = confirm("Hill View");

		deliverOutbox();
		deliverOutbox();

		assertThat(confirmations()).extracting(Email::subject)
			.containsExactly("Your stay at Seaside Inn is confirmed", "Your stay at Hill View is confirmed");
		assertThat(List.of(first, second)).allSatisfy(event -> {
			assertThat(state(event)).containsEntry("published", true);
			assertThat(entriesOf(BOOKING_EMAILS, event)).hasSize(1);
			assertThat(isPending(BOOKING_EMAILS, event)).isFalse();
		});
	}

	@Test
	void eachConsumerReadsOnlyItsOwnStream() {
		long confirmation = confirm("Seaside Inn");
		long welcome = jdbc.queryForObject(
				"select id from outbox_events where type = 'ACCOUNT_CREATED' and aggregate_id = ?", Long.class, guestId);

		relay.relayWaiting();

		assertThat(entriesOf(BOOKING_EMAILS, confirmation)).hasSize(1);
		assertThat(entriesOf("outbox:account-emails", confirmation)).isEmpty();
		assertThat(entriesOf("outbox:refunds", confirmation)).isEmpty();
		assertThat(entriesOf("outbox:account-emails", welcome)).hasSize(1);
		assertThat(state(confirmation)).containsEntry("sent", true).containsEntry("published", false);
	}

	@Test
	void aChangeThatRollsBackSendsNothing() {
		long bookingId = newBookingId();
		transaction.executeWithoutResult(status -> {
			outbox.write(confirmation(bookingId, guestId, "Seaside Inn"));
			status.setRollbackOnly();
		});

		deliverOutbox();

		assertThat(outboxEvents("BOOKING_CONFIRMED", bookingId)).isZero();
		assertThat(confirmations()).isEmpty();
	}

	@Test
	void aConsumerThatKeepsFailingIsRetriedThenParkedAndItsEntryLetGo() {
		// Its guest's account is gone, so the email has nowhere to go
		long broken = write(confirmation(newBookingId(), -1L, "Seaside Inn"));
		long fine = confirm("Hill View");

		deliverOutbox();

		assertThat(state(broken)).containsEntry("attempts", 1).containsEntry("parked", false);
		assertThat((String) state(broken).get("last_error")).contains("User -1 not found");
		assertThat(isPending(BOOKING_EMAILS, broken)).as("left for another try").isTrue();
		assertThat(state(fine)).containsEntry("published", true);

		for (int run = 2; run <= OutboxEvent.MAX_ATTEMPTS; run++) {
			deliverOutbox();
		}

		assertThat(state(broken))
			.containsEntry("attempts", OutboxEvent.MAX_ATTEMPTS)
			.containsEntry("parked", true)
			.containsEntry("published", false);
		assertThat(isPending(BOOKING_EMAILS, broken)).as("acknowledged once parked").isFalse();
	}

	@Test
	void anEntryACrashedReaderHeldIsClaimedAndDelivered() {
		long event = confirm("Seaside Inn");
		relay.relayWaiting();
		// Another instance reads it, then dies before handling it
		readAs("crashed-instance");

		readers.readOnce();
		assertThat(emails.deliveriesOf("outbox-" + event)).as("no longer a new entry").isZero();
		assertThat(readers.claimIdle(Duration.ofSeconds(30))).as("not idle long enough").isZero();
		assertThat(emails.deliveriesOf("outbox-" + event)).isZero();

		await().atMost(Duration.ofSeconds(5)).until(() -> readers.claimIdle(Duration.ofMillis(200)) > 0);

		assertThat(emails.deliveriesOf("outbox-" + event)).isOne();
		assertThat(state(event)).containsEntry("published", true);
		assertThat(isPending(BOOKING_EMAILS, event)).isFalse();
	}

	@Test
	void anEntryHandedOverAgainAfterItsDeliveryCommittedIsLetGoWithoutActing() {
		long event = confirm("Seaside Inn");
		relay.relayWaiting();
		// Another instance reads it, delivers it, and dies before acknowledging it
		readAs("crashed-instance");
		assertThat(delivery.deliver(event)).isEqualTo(OutboxDelivery.Outcome.PUBLISHED);

		readers.claimIdle(Duration.ZERO);

		assertThat(emails.deliveriesOf("outbox-" + event)).isOne();
		assertThat(isPending(BOOKING_EMAILS, event)).isFalse();
	}

	@Test
	void aStreamRedisLostIsSentAgainFromPostgres() {
		long event = confirm("Seaside Inn");
		relay.relayWaiting();
		// The stream and its group are gone: Redis was flushed, or restarted without persistence
		redis.delete(BOOKING_EMAILS);

		deliverOutbox();
		assertThat(emails.deliveriesOf("outbox-" + event)).as("sent, so not sent again yet").isZero();

		clock.advance(Duration.ofMinutes(2).minusSeconds(1));
		sweeper.resendStale();
		assertThat(state(event)).as("not long enough yet").containsEntry("sent", true);

		clock.advance(Duration.ofSeconds(2));
		assertThat(sweeper.resendStale()).isPositive();
		deliverOutbox();

		assertThat(emails.deliveriesOf("outbox-" + event)).isOne();
		assertThat(state(event)).containsEntry("published", true);
	}

	@Test
	void anEntryTrimmedAwayBeforeItWasReadIsSentAgain() {
		long event = confirm("Seaside Inn");
		relay.relayWaiting();
		// The stream is capped; a reader that falls far enough behind loses the oldest entries
		redis.opsForStream().trim(BOOKING_EMAILS, 0);

		deliverOutbox();
		clock.advance(Duration.ofMinutes(3));
		sweeper.resendStale();
		deliverOutbox();

		assertThat(emails.deliveriesOf("outbox-" + event)).isOne();
	}

	@Test
	void anEventWrittenWhileRedisIsntAnsweringWaitsAndIsDeliveredOnceItsBack() {
		AtomicLong event = new AtomicLong();

		whilePaused(REDIS, () -> {
			event.set(confirm("Seaside Inn"));
			relay.relayWaiting();
			assertThat(state(event.get())).as("not sent while Redis is down").containsEntry("sent", false);
		});
		deliverOutbox();

		assertThat(emails.deliveriesOf("outbox-" + event.get())).isOne();
	}

	@RaceTest
	void readersRacingForOneEntryDeliverItOnce() throws Exception {
		long event = confirm("Seaside Inn");
		relay.relayWaiting();
		List<Callable<OutboxDelivery.Outcome>> deliveries = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			deliveries.add(() -> delivery.deliver(event));
		}

		List<OutboxDelivery.Outcome> outcomes = runTogether(deliveries);

		assertThat(outcomes).filteredOn(outcome -> outcome == OutboxDelivery.Outcome.PUBLISHED).hasSize(1);
		assertThat(emails.deliveriesOf("outbox-" + event)).isOne();
	}

	@RaceTest
	void relaysThatOverlapSendEachEventOnce() throws Exception {
		// The safety poll and a commit's wake can run at once; each locks the events it sends
		List<Long> events = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			events.add(confirm("Hotel " + i));
		}
		List<Callable<Integer>> relays = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			relays.add(relay::relayWaiting);
		}

		runTogether(relays);

		assertThat(events).allSatisfy(event -> assertThat(entriesOf(BOOKING_EMAILS, event)).hasSize(1));
		deliverOutbox();
		assertThat(confirmations()).hasSize(20);
	}

	@Test
	void theSafetyPollSkipsItsRunWhileAnotherInstanceHoldsTheLock() {
		long event = confirm("Seaside Inn");

		redis.opsForValue().set("lock:outbox", "another-instance", Duration.ofSeconds(30));
		try {
			relay.run();
			assertThat(state(event)).containsEntry("sent", false);
		}
		finally {
			redis.delete("lock:outbox");
		}
		relay.run();

		assertThat(state(event)).containsEntry("sent", true);
	}

	/** Reads every new entry of the booking emails stream as another instance would, acknowledging none. */
	private void readAs(String instance) {
		try {
			redis.opsForStream().createGroup(BOOKING_EMAILS, ReadOffset.from("0"), "booking-emails");
		}
		catch (DataAccessException alreadyThere) {
			// BUSYGROUP: the group exists
		}
		redis.opsForStream().read(Consumer.from("booking-emails", instance), StreamReadOptions.empty().count(10_000),
				StreamOffset.create(BOOKING_EMAILS, ReadOffset.lastConsumed()));
	}

	/** The stream's entries for this event. */
	private List<MapRecord<String, Object, Object>> entriesOf(String stream, long event) {
		return redis.opsForStream().range(stream, Range.unbounded()).stream()
			.filter(entry -> String.valueOf(event).equals(entry.getValue().get("eventId")))
			.toList();
	}

	/** Whether an entry for this event was read and not yet acknowledged. */
	private boolean isPending(String stream, long event) {
		var pending = redis.opsForStream().pending(stream, "booking-emails", Range.unbounded(), 10_000);
		return entriesOf(stream, event).stream()
			.anyMatch(entry -> pending.stream().anyMatch(message -> message.getId().equals(entry.getId())));
	}

	/** The booking confirmations the guest was sent (signing up sent them a welcome too). */
	private List<Email> confirmations() {
		return emails.sentTo(guest.email()).stream().filter(email -> email.subject().endsWith(" is confirmed")).toList();
	}

	/** Writes a confirmation for the guest at this hotel; returns the outbox event's id. */
	private long confirm(String hotel) {
		return write(confirmation(newBookingId(), guestId, hotel));
	}

	private long write(BookingConfirmed confirmation) {
		transaction.executeWithoutResult(status -> outbox.write(confirmation));
		return jdbc.queryForObject("select id from outbox_events where type = 'BOOKING_CONFIRMED' and aggregate_id = ?",
				Long.class, confirmation.bookingId());
	}

	private static BookingConfirmed confirmation(long bookingId, long userId, String hotel) {
		return new BookingConfirmed(bookingId, userId, hotel, "Deluxe", LocalDate.parse("2026-12-24"),
				LocalDate.parse("2026-12-26"), 1, new BigDecimal("8000.00"), "inr");
	}

	private Map<String, Object> state(long event) {
		return jdbc.queryForMap("""
				select attempts, last_error, sent_at is not null as sent, failed_at is not null as parked,
				       published_at is not null as published
				from outbox_events where id = ?""", event);
	}

	private static long newBookingId() {
		return ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
	}

}
