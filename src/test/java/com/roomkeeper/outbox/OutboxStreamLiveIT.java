package com.roomkeeper.outbox;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeout;

/**
 * Live delivery, as in production: a commit wakes the relay and readers on their own threads
 * take entries off their streams, with no test calling either. The context is closed after
 * the class, so its readers stop and can't take entries the other tests deliver by hand.
 */
@TestPropertySource(properties = "roomkeeper.outbox.live-delivery=true")
@DirtiesContext
class OutboxStreamLiveIT extends AbstractIntegrationTest {

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

	@Test
	void aCommittedEventReachesItsConsumerWithinASecondOrSo() {
		TestUser guest = aUser().build();
		long guestId = signUp(guest);
		long bookingId = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);

		transaction.executeWithoutResult(status -> outbox.write(new BookingConfirmed(bookingId, guestId, "Seaside Inn",
				"Deluxe", LocalDate.parse("2026-12-24"), LocalDate.parse("2026-12-26"), 1, new BigDecimal("8000.00"),
				"inr")));
		long event = jdbc.queryForObject(
				"select id from outbox_events where type = 'BOOKING_CONFIRMED' and aggregate_id = ?", Long.class, bookingId);

		await().atMost(Duration.ofSeconds(2))
			.untilAsserted(() -> assertThat(emails.deliveriesOf("outbox-" + event)).isOne());
		assertThat(emails.sentTo(guest.email())).extracting(Email::subject)
			.contains("Welcome to RoomKeeper", "Your stay at Seaside Inn is confirmed");
	}

	@Test
	void readersWaitingOnTheirStreamsDontHoldUpOtherRedisCalls() {
		// Three readers are blocked on their streams right now, each on its own connection
		assertTimeout(Duration.ofSeconds(1), () -> {
			for (int i = 0; i < 20; i++) {
				redis.opsForValue().get("anything");
			}
		});
	}

}
