package com.roomkeeper.inventory;

import java.time.LocalDate;
import java.util.UUID;

import com.roomkeeper.testsupport.AbstractDataTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The database itself refuses to oversell, even if application code has a bug. These
 * tests write straight SQL, bypassing every check in the app, to prove the constraints
 * hold on their own.
 */
class InventoryConstraintsIT extends AbstractDataTest {

	private static final LocalDate NIGHT = LocalDate.parse("2026-12-24");

	@Autowired
	JdbcTemplate jdbc;

	private long roomId;

	@BeforeEach
	void createARoom() {
		long ownerId = jdbc.queryForObject("""
				insert into users (email, password_hash, name, role, created_at)
				values (?, 'not-a-real-hash', 'Owner', 'HOTEL_MANAGER', now()) returning id""",
				Long.class, "owner-" + UUID.randomUUID() + "@example.com");
		long hotelId = jdbc.queryForObject("""
				insert into hotels (owner_id, name, city, created_at)
				values (?, 'Seaside', 'Goa', now()) returning id""", Long.class, ownerId);
		roomId = jdbc.queryForObject("""
				insert into rooms (hotel_id, type, base_price, capacity, total_count, created_at)
				values (?, 'Deluxe', 4500.00, 2, 5, now()) returning id""", Long.class, hotelId);
	}

	@Test
	void allowsBookedAndHeldRoomsUpToTheTotal() {
		long id = insertInventory(5);

		int updated = setCounts(id, 3, 2);

		assertThat(updated).isEqualTo(1);
	}

	@Test
	void rejectsMoreBookedAndHeldRoomsThanExist() {
		long id = insertInventory(5);

		assertThatThrownBy(() -> setCounts(id, 3, 3))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("inventory_not_oversold");
	}

	@Test
	void rejectsLoweringTheTotalBelowWhatIsBookedAndHeld() {
		long id = insertInventory(5);
		setCounts(id, 2, 2);

		assertThatThrownBy(() -> jdbc.update("update inventory set total_count = 3 where id = ?", id))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("inventory_not_oversold");
	}

	@Test
	void rejectsNegativeCounts() {
		// A buggy hold release must not "make room" by going below zero
		long id = insertInventory(5);

		assertThatThrownBy(() -> setCounts(id, 0, -1))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void allowsOnlyOneRowPerRoomAndNight() {
		insertInventory(5);

		assertThatThrownBy(() -> insertInventory(5))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("inventory_room_date_key");
	}

	private long insertInventory(int totalCount) {
		return jdbc.queryForObject("insert into inventory (room_id, date, total_count) values (?, ?, ?) returning id",
				Long.class, roomId, NIGHT, totalCount);
	}

	private int setCounts(long id, int booked, int reserved) {
		return jdbc.update("update inventory set booked_count = ?, reserved_count = ? where id = ?", booked, reserved,
				id);
	}

}
