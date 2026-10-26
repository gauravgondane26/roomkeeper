package com.roomkeeper.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.InvalidFieldException;
import com.roomkeeper.common.error.ProblemType;
import com.roomkeeper.hotel.Hotel;
import com.roomkeeper.hotel.Room;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The rules a booking enforces on itself as it moves through its states. */
class BookingTest {

	private static final Instant HELD_AT = Instant.parse("2026-03-10T09:00:00Z");

	private static final Instant EXPIRES_AT = HELD_AT.plusSeconds(600);

	private final Room room = new Room(new Hotel(1L, "Seaside Inn", "Goa", "UTC", List.of(), HELD_AT), "Deluxe",
			new BigDecimal("4000.00"), 2, 5, HELD_AT);

	@Test
	void addingGuestsMovesAHoldOn() {
		Booking booking = twoRooms();

		booking.putGuests(List.of(guest("Asha"), guest("Vikram")), HELD_AT.plusSeconds(60));

		assertThat(booking.getStatus()).isEqualTo(BookingStatus.GUESTS_ADDED);
		assertThat(booking.getGuests()).extracting(Guest::getName).containsExactly("Asha", "Vikram");
	}

	@Test
	void guestsCanBeChangedWhileTheRoomsAreHeld() {
		Booking booking = twoRooms();
		booking.putGuests(List.of(guest("Asha")), HELD_AT.plusSeconds(60));

		booking.putGuests(List.of(guest("Vikram")), HELD_AT.plusSeconds(120));

		assertThat(booking.getStatus()).isEqualTo(BookingStatus.GUESTS_ADDED);
		assertThat(booking.getGuests()).extracting(Guest::getName).containsExactly("Vikram");
	}

	@Test
	void fitsAsManyGuestsAsTheRoomsSleep() {
		// Two rooms sleeping two each
		Booking booking = twoRooms();
		List<Guest> five = List.of(guest("A"), guest("B"), guest("C"), guest("D"), guest("E"));

		assertThatThrownBy(() -> booking.putGuests(five, HELD_AT))
			.isInstanceOf(InvalidFieldException.class);
		booking.putGuests(five.subList(0, 4), HELD_AT);
		assertThat(booking.getGuests()).hasSize(4);
	}

	@Test
	void theHoldRunsOutAtItsExpiryTime() {
		Booking booking = twoRooms();

		assertThat(booking.holdRanOut(EXPIRES_AT.minusMillis(1))).isFalse();
		assertThat(booking.holdRanOut(EXPIRES_AT)).isTrue();
		assertThatThrownBy(() -> booking.putGuests(List.of(guest("Asha")), EXPIRES_AT))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.type()).isEqualTo(ProblemType.HOLD_EXPIRED));
		assertThat(booking.getStatus()).isEqualTo(BookingStatus.RESERVED);
	}

	@Test
	void needsAtLeastOneGuest() {
		assertThatThrownBy(() -> twoRooms().putGuests(List.of(), HELD_AT)).isInstanceOf(InvalidFieldException.class);
	}

	private Booking twoRooms() {
		return new Booking(7L, room, LocalDate.parse("2026-03-10"), LocalDate.parse("2026-03-12"), 2,
				new BigDecimal("16000.00"), EXPIRES_AT, HELD_AT);
	}

	private static Guest guest(String name) {
		return new Guest(7L, name, 30, Gender.OTHER);
	}

}
