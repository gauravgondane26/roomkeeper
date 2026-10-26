package com.roomkeeper.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.roomkeeper.hotel.Room;
import com.roomkeeper.testsupport.AbstractDataTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The bookings tables: the entities map onto them, and the constraints refuse bad rows
 * even when written with plain SQL, past every check in the app.
 */
class BookingSchemaIT extends AbstractDataTest {

	private static final LocalDate CHECK_IN = LocalDate.parse("2026-12-24");

	private static final LocalDate CHECK_OUT = LocalDate.parse("2026-12-26");

	private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

	@Autowired
	private BookingRepository bookings;

	@Autowired
	private EntityManager entityManager;

	private long userId;

	private long hotelId;

	private long roomId;

	@BeforeEach
	void createARoomAndAUser() {
		hotelId = insertHotel(insertManager(), "Goa", "Asia/Kolkata", true);
		roomId = insertRoom(hotelId);
		userId = insertGuestUser();
	}

	@Test
	void savesAHoldAndReadsItBack() {
		Instant expiresAt = NOW.plusSeconds(600);
		Room room = entityManager.getReference(Room.class, roomId);
		long id = bookings.save(new Booking(userId, room, CHECK_IN, CHECK_OUT, 2, new BigDecimal("16000.00"),
				expiresAt, NOW)).getId();
		entityManager.flush();
		entityManager.clear();

		Booking booking = bookings.findById(id).orElseThrow();

		assertThat(booking.getStatus()).isEqualTo(BookingStatus.RESERVED);
		assertThat(booking.getUserId()).isEqualTo(userId);
		assertThat(booking.getRoom().getId()).isEqualTo(roomId);
		assertThat(booking.getCheckIn()).isEqualTo(CHECK_IN);
		assertThat(booking.getCheckOut()).isEqualTo(CHECK_OUT);
		assertThat(booking.getRoomsCount()).isEqualTo(2);
		assertThat(booking.getAmount()).isEqualByComparingTo("16000.00");
		assertThat(booking.getHoldExpiresAt()).isEqualTo(expiresAt);
		assertThat(booking.getGuests()).isEmpty();
	}

	@Test
	void readsTheGuestsOnABooking() {
		long bookingId = insertBooking(CHECK_IN, CHECK_OUT, 1, "RESERVED");
		addGuest(bookingId, insertSavedGuest("Asha Rao"));
		addGuest(bookingId, insertSavedGuest("Vikram Rao"));

		Booking booking = bookings.findById(bookingId).orElseThrow();

		assertThat(booking.getGuests()).extracting(Guest::getName).containsExactlyInAnyOrder("Asha Rao", "Vikram Rao");
	}

	@Test
	void rejectsACheckOutThatIsNotAfterTheCheckIn() {
		assertThatThrownBy(() -> insertBooking(CHECK_IN, CHECK_IN, 1, "RESERVED"))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("bookings_check_out_after_check_in");
	}

	@Test
	void rejectsABookingForNoRooms() {
		assertThatThrownBy(() -> insertBooking(CHECK_IN, CHECK_OUT, 0, "RESERVED"))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("bookings_rooms_count_check");
	}

	@Test
	void rejectsAnUnknownStatus() {
		assertThatThrownBy(() -> insertBooking(CHECK_IN, CHECK_OUT, 1, "PAID"))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("bookings_status_check");
	}

	@Test
	void keepsARoomTypeWithBookings() {
		insertBooking(CHECK_IN, CHECK_OUT, 1, "CONFIRMED");

		assertThatThrownBy(() -> jdbc.update("delete from rooms where id = ?", roomId))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("bookings_room_id_fkey");
	}

	@Test
	void keepsAHotelWithBookings() {
		// Deleting the hotel cascades to its room types, which the bookings then hold on to
		insertBooking(CHECK_IN, CHECK_OUT, 1, "CONFIRMED");

		assertThatThrownBy(() -> jdbc.update("delete from hotels where id = ?", hotelId))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("bookings_room_id_fkey");
	}

	@Test
	void addsAGuestToABookingOnlyOnce() {
		long bookingId = insertBooking(CHECK_IN, CHECK_OUT, 1, "RESERVED");
		long guestId = insertSavedGuest("Asha Rao");
		addGuest(bookingId, guestId);

		assertThatThrownBy(() -> addGuest(bookingId, guestId))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("booking_guests_pkey");
	}

	@Test
	void rejectsAnUnknownGender() {
		assertThatThrownBy(() -> jdbc.update("insert into guests (user_id, name, age, gender) values (?, 'Asha', 30, 'X')",
				userId))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("guests_gender_check");
	}

	private long insertBooking(LocalDate checkIn, LocalDate checkOut, int rooms, String status) {
		return jdbc.queryForObject("""
				insert into bookings (user_id, room_id, check_in, check_out, rooms_count, status, amount,
				                      hold_expires_at, created_at)
				values (?, ?, ?, ?, ?, ?, 8000.00, now() + interval '10 minutes', now()) returning id""",
				Long.class, userId, roomId, checkIn, checkOut, rooms, status);
	}

	private long insertSavedGuest(String name) {
		return jdbc.queryForObject("insert into guests (user_id, name, age, gender) values (?, ?, 30, 'FEMALE') returning id",
				Long.class, userId, name);
	}

	private void addGuest(long bookingId, long guestId) {
		jdbc.update("insert into booking_guests (booking_id, guest_id) values (?, ?)", bookingId, guestId);
	}

}
