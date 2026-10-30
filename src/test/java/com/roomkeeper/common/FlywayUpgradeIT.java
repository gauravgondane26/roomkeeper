package com.roomkeeper.common;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import com.roomkeeper.testsupport.Containers;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production databases hold data, so every migration must also work on rows that existed
 * before it, not only on the empty schema the other tests start from. This builds a
 * database the way production grew one: schema up to V3 with hotels, rooms and nights in
 * it, then up to V11 with bookings, payments and outbox events, then every migration since.
 * The migrations that change existing rows (time zones, hand-set totals, hotel approval,
 * refunded amounts) are checked to have done so. Its own database, so the shared one the
 * other tests use is untouched.
 */
class FlywayUpgradeIT {

	private DataSource dataSource;

	private JdbcTemplate jdbc;

	@BeforeEach
	void createAnEmptyDatabase() throws SQLException {
		String name = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
		try (Connection admin = DriverManager.getConnection(Containers.POSTGRES.getJdbcUrl(),
				Containers.POSTGRES.getUsername(), Containers.POSTGRES.getPassword())) {
			admin.createStatement().execute("create database " + name);
		}
		String url = Containers.POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
		dataSource = new DriverManagerDataSource(url, Containers.POSTGRES.getUsername(), Containers.POSTGRES.getPassword());
		jdbc = new JdbcTemplate(dataSource);
	}

	@Test
	void everyMigrationKeepsAndCarriesForwardTheDataBeforeIt() {
		migrateTo("3");
		long manager = jdbc.queryForObject("""
				insert into users (email, password_hash, name, role, created_at)
				values ('manager@example.com', '{bcrypt}x', 'Manager', 'HOTEL_MANAGER', now()) returning id""", Long.class);
		long guest = jdbc.queryForObject("""
				insert into users (email, password_hash, name, role, created_at)
				values ('guest@example.com', '{bcrypt}x', 'Guest', 'GUEST', now()) returning id""", Long.class);
		long onSale = hotel(manager, "Seaside Inn", true);
		long draft = hotel(manager, "Draft Lodge", false);
		long room = jdbc.queryForObject("""
				insert into rooms (hotel_id, type, base_price, capacity, total_count, created_at)
				values (?, 'Deluxe', 4000.00, 2, 5, now()) returning id""", Long.class, onSale);
		jdbc.update("""
				insert into inventory (room_id, date, total_count, booked_count, reserved_count, surge_factor, closed)
				select ?, d::date, 5, 1, 1, 1.20, false
				from generate_series(date '2026-03-10', date '2026-03-16', interval '1 day') d""", room);

		migrateTo("11");
		long booking = jdbc.queryForObject("""
				insert into bookings (user_id, room_id, check_in, check_out, rooms_count, status, amount,
				                      hold_expires_at, created_at)
				values (?, ?, '2026-03-10', '2026-03-12', 1, 'CANCELLED', 8000.00, now(), now()) returning id""",
				Long.class, guest, room);
		jdbc.update("""
				insert into payments (booking_id, provider_session_id, checkout_url, status, amount, currency,
				                      payment_intent_id, refund_id, created_at)
				values (?, 'cs_test_1', 'https://checkout.example/1', 'REFUNDED', 8000.00, 'inr', 'pi_1', 're_1', now())""",
				booking);
		jdbc.update("""
				insert into outbox_events (aggregate_id, type, payload, created_at)
				values (?, 'BookingCancelled', '{"bookingId": 1}', now())""", booking);

		migrateTo(null);

		assertThat(Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().info()
			.pending()).as("migrations left to apply").isEmpty();
		assertThat(jdbc.queryForList("select time_zone from hotels", String.class)).containsOnly("UTC");
		assertThat(jdbc.queryForObject("select approval_status from hotels where id = ?", String.class, onSale))
			.as("a hotel on sale before approval existed was approved").isEqualTo("APPROVED");
		assertThat(jdbc.queryForObject("select approval_status from hotels where id = ?", String.class, draft))
			.isEqualTo("PENDING");
		assertThat(jdbc.queryForList("""
				select total_count || '/' || booked_count || '/' || reserved_count || '/' || surge_factor
				       || '/' || total_overridden
				from inventory where room_id = ?""", String.class, room))
			.hasSize(7)
			.containsOnly("5/1/1/1.20/false");
		assertThat(jdbc.queryForObject("select refunded_amount from payments", BigDecimal.class))
			.as("a refund made before partial refunds existed refunded everything").isEqualByComparingTo("8000.00");
		assertThat(jdbc.queryForObject("select count(*) from outbox_events where published_at is null", Long.class))
			.isOne();
	}

	@Test
	void theUpgradedSchemaMatchesOneMigratedFromEmpty() {
		migrateTo("3");
		migrateTo(null);
		String upgraded = schema();

		createAnEmptyDatabaseAgain();
		migrateTo(null);

		assertThat(upgraded).isEqualTo(schema());
	}

	private void createAnEmptyDatabaseAgain() {
		try {
			createAnEmptyDatabase();
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private long hotel(long owner, String name, boolean active) {
		return jdbc.queryForObject("""
				insert into hotels (owner_id, name, city, amenities, active, created_at)
				values (?, ?, 'Goa', '{wifi}', ?, now()) returning id""", Long.class, owner, name, active);
	}

	/** Migrates to {@code version}, or to the latest when null, as the app does at startup. */
	private void migrateTo(String version) {
		var config = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
		if (version != null) {
			config.target(MigrationVersion.fromVersion(version));
		}
		config.load().migrate();
	}


	/** Columns, constraints and indexes of the public schema, in a stable order. */
	private String schema() {
		return String.join("\n", jdbc.queryForList("""
				select table_name || '.' || column_name || ' ' || data_type || ' ' || is_nullable
				       || ' ' || coalesce(column_default, '')
				from information_schema.columns where table_schema = 'public'
				union all
				select conrelid::regclass || ' ' || conname || ' ' || pg_get_constraintdef(oid)
				from pg_constraint where connamespace = 'public'::regnamespace
				union all
				select indexdef from pg_indexes where schemaname = 'public'
				order by 1""", String.class));
	}

}
