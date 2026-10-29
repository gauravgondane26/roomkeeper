package com.roomkeeper.outbox;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** An outbox event commits or rolls back with the change that wrote it, and only with one. */
class OutboxWriterIT extends AbstractIntegrationTest {

	@Autowired
	OutboxWriter outbox;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	JdbcTemplate jdbc;

	private final long bookingId = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);

	@Test
	void writesTheEventInTheChangesTransaction() {
		transaction.executeWithoutResult(status -> outbox.write(confirmation()));

		Map<String, Object> row = jdbc.queryForMap("""
				select type, payload ->> 'hotel' as hotel, payload ->> 'checkIn' as check_in,
				       (payload ->> 'amount')::numeric as amount, published_at
				from outbox_events where aggregate_id = ?""", bookingId);
		assertThat(row)
			.containsEntry("type", "BOOKING_CONFIRMED")
			.containsEntry("hotel", "Seaside Inn")
			.containsEntry("check_in", "2026-12-24")
			.containsEntry("published_at", null);
		assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("8000.00");
	}

	@Test
	void aChangeThatRollsBackLeavesNoEvent() {
		transaction.executeWithoutResult(status -> {
			outbox.write(confirmation());
			status.setRollbackOnly();
		});

		assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ?", Long.class,
				bookingId))
			.isZero();
	}

	@Test
	void refusesToWriteOutsideAChange() {
		// An event on its own could be published for a change that never happened
		assertThatThrownBy(() -> outbox.write(confirmation())).isInstanceOf(IllegalTransactionStateException.class);
	}

	private BookingConfirmed confirmation() {
		return new BookingConfirmed(bookingId, 7L, "Seaside Inn", "Deluxe", LocalDate.parse("2026-12-24"),
				LocalDate.parse("2026-12-26"), 1, new BigDecimal("8000.00"), "inr");
	}

}
