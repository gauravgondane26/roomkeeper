package com.roomkeeper.inventory;

import java.time.LocalDate;

import com.roomkeeper.testsupport.AbstractDataTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The database itself refuses to oversell, even if application code has a bug. These
 * tests write straight SQL, bypassing every check in the app, to prove the constraints
 * hold on their own.
 */
class InventoryConstraintsIT extends AbstractDataTest {

	private static final LocalDate NIGHT = LocalDate.parse("2026-12-24");

	private long roomId;

	@BeforeEach
	void createARoom() {
		roomId = insertRoom(insertHotel(insertManager(), "Goa", "Asia/Kolkata", false));
	}

	@Test
	void allowsBookedAndHeldRoomsUpToTheTotal() {
		long id = insertNight(roomId, NIGHT, 5);

		int updated = setCounts(id, 3, 2);

		assertThat(updated).isEqualTo(1);
	}

	@Test
	void rejectsMoreBookedAndHeldRoomsThanExist() {
		long id = insertNight(roomId, NIGHT, 5);

		assertThatThrownBy(() -> setCounts(id, 3, 3))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("inventory_not_oversold");
	}

	@Test
	void rejectsLoweringTheTotalBelowWhatIsBookedAndHeld() {
		long id = insertNight(roomId, NIGHT, 5);
		setCounts(id, 2, 2);

		assertThatThrownBy(() -> jdbc.update("update inventory set total_count = 3 where id = ?", id))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("inventory_not_oversold");
	}

	@Test
	void rejectsNegativeCounts() {
		// A buggy hold release must not "make room" by going below zero
		long id = insertNight(roomId, NIGHT, 5);

		assertThatThrownBy(() -> setCounts(id, 0, -1))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void allowsOnlyOneRowPerRoomAndNight() {
		insertNight(roomId, NIGHT, 5);

		assertThatThrownBy(() -> insertNight(roomId, NIGHT, 5))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("inventory_room_date_key");
	}

	private int setCounts(long id, int booked, int reserved) {
		return jdbc.update("update inventory set booked_count = ?, reserved_count = ? where id = ?", booked, reserved,
				id);
	}

}
