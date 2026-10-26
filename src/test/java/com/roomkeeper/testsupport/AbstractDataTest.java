package com.roomkeeper.testsupport;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for data slice tests: JPA, Flyway, and the repositories against the shared
 * Postgres container, without the web layer or Redis. Each test runs in a transaction
 * that is rolled back afterwards.
 * <p>
 * The fixture methods write plain SQL, so data tests don't depend on the services they
 * might be testing around.
 */
@DataJpaTest
@ActiveProfiles("test")
public abstract class AbstractDataTest {

	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = Containers.POSTGRES;

	@Autowired
	protected JdbcTemplate jdbc;

	/** A hotel manager with a unique email, to own fixture hotels; returns the user's id. */
	protected long insertManager() {
		return insertUser("Owner", "HOTEL_MANAGER");
	}

	/** A guest account with a unique email, to make bookings; returns the user's id. */
	protected long insertGuestUser() {
		return insertUser("Guest", "GUEST");
	}

	private long insertUser(String name, String role) {
		return jdbc.queryForObject("""
				insert into users (email, password_hash, name, role, created_at)
				values (?, 'not-a-real-hash', ?, ?, now()) returning id""",
				Long.class, name.toLowerCase() + "-" + UUID.randomUUID() + "@example.com", name, role);
	}

	/** A hotel named "Hotel {id}" with wifi and a pool; returns its id. */
	protected long insertHotel(long ownerId, String city, String timeZone, boolean active) {
		long id = jdbc.queryForObject("""
				insert into hotels (owner_id, name, city, time_zone, amenities, active, created_at)
				values (?, 'pending', ?, ?, '{wifi,pool}', ?, now()) returning id""",
				Long.class, ownerId, city, timeZone, active);
		jdbc.update("update hotels set name = ? where id = ?", "Hotel " + id, id);
		return id;
	}

	/** A room type with a unique name: 4000.00 a night, sleeps 2, 5 rooms. Returns its id. */
	protected long insertRoom(long hotelId) {
		return jdbc.queryForObject("""
				insert into rooms (hotel_id, type, base_price, capacity, total_count, created_at)
				values (?, ?, 4000.00, 2, 5, now()) returning id""", Long.class, hotelId,
				"Type-" + UUID.randomUUID());
	}

	/** One open night with {@code total} rooms and none sold; returns its id. */
	protected long insertNight(long roomId, LocalDate night, int total) {
		return jdbc.queryForObject("insert into inventory (room_id, date, total_count) values (?, ?, ?) returning id",
				Long.class, roomId, night, total);
	}

	/** Sets how many rooms are booked on a night. */
	protected void setBooked(long roomId, LocalDate night, int booked) {
		jdbc.update("update inventory set booked_count = ? where room_id = ? and date = ?", booked, roomId, night);
	}

}
