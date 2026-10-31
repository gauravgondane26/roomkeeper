package com.roomkeeper.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;
import com.roomkeeper.hotel.Hotel;
import com.roomkeeper.hotel.Room;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A place in line for a sold-out stay: waiting, then offered rooms, left, or past its stay. */
class WaitlistEntryTest {

	private static final Instant JOINED_AT = Instant.parse("2026-03-10T09:00:00Z");

	private final Room room = new Room(new Hotel(1L, "Waitlist Inn", "Goa", "UTC", List.of(), JOINED_AT), "Deluxe",
			new BigDecimal("4000.00"), 2, 1, JOINED_AT);

	@Test
	void waitsUntilRoomsAreHeldForIt() {
		WaitlistEntry entry = entry();
		assertThat(entry.getStatus()).isEqualTo(WaitlistStatus.WAITING);
		assertThat(entry.isWaiting()).isTrue();

		entry.offer(42L, JOINED_AT.plusSeconds(600));

		assertThat(entry.getStatus()).isEqualTo(WaitlistStatus.OFFERED);
		assertThat(entry.isWaiting()).isFalse();
		assertThat(entry.getBookingId()).isEqualTo(42L);
		assertThat(entry.getOfferedAt()).isEqualTo(JOINED_AT.plusSeconds(600));
		assertThatIllegalStateException().isThrownBy(() -> entry.offer(43L, JOINED_AT.plusSeconds(601)));
	}

	@Test
	void aWaitingGuestCanLeaveButNotOnceOffered() {
		WaitlistEntry left = entry();
		left.leave();
		assertThat(left.getStatus()).isEqualTo(WaitlistStatus.LEFT);
		assertThatThrownBy(left::leave).isInstanceOfSatisfying(ApiException.class,
				ex -> assertThat(ex.type()).isEqualTo(ProblemType.WAITLIST_STATE));

		WaitlistEntry offered = entry();
		offered.offer(42L, JOINED_AT);
		assertThatThrownBy(offered::leave).isInstanceOfSatisfying(ApiException.class,
				ex -> assertThat(ex.type()).isEqualTo(ProblemType.WAITLIST_STATE));
		assertThat(offered.getStatus()).isEqualTo(WaitlistStatus.OFFERED);
	}

	@Test
	void aStayThatHasBegunIsNoLongerWaitedFor() {
		WaitlistEntry entry = entry();

		assertThat(entry.hasBegunBy(LocalDate.parse("2026-03-10"))).isFalse();
		assertThat(entry.hasBegunBy(LocalDate.parse("2026-03-11"))).isTrue();

		entry.expire();
		assertThat(entry.getStatus()).isEqualTo(WaitlistStatus.EXPIRED);
		assertThatIllegalStateException().isThrownBy(entry::expire);
	}

	@Test
	void keepsTheStayItWaitsFor() {
		WaitlistEntry entry = entry();

		assertThat(entry.getUserId()).isEqualTo(7L);
		assertThat(entry.getRoom()).isSameAs(room);
		assertThat(entry.getCheckIn()).isEqualTo(LocalDate.parse("2026-03-10"));
		assertThat(entry.getCheckOut()).isEqualTo(LocalDate.parse("2026-03-12"));
		assertThat(entry.lastNight()).isEqualTo(LocalDate.parse("2026-03-11"));
		assertThat(entry.getRoomsCount()).isEqualTo(1);
		assertThat(entry.getCreatedAt()).isEqualTo(JOINED_AT);
		assertThat(entry.getBookingId()).isNull();
		assertThat(entry.getOfferedAt()).isNull();
	}

	private WaitlistEntry entry() {
		return new WaitlistEntry(7L, room, LocalDate.parse("2026-03-10"), LocalDate.parse("2026-03-12"), 1, JOINED_AT);
	}

}
