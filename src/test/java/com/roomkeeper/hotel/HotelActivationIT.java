package com.roomkeeper.hotel;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.testsupport.MutableClockConfig;
import com.roomkeeper.testsupport.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

@Import(MutableClockConfig.class)
class HotelActivationIT extends AbstractIntegrationTest {

	// Late in the UTC day, so "today" is unambiguous
	private static final Instant NOW = Instant.parse("2026-03-10T22:00:00Z");

	private static final LocalDate TODAY = LocalDate.parse("2026-03-10");

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcTemplate jdbc;

	private TestUser managerUser;

	private String manager;

	private long hotelId;

	@BeforeEach
	void createAHotel() {
		clock.setInstant(NOW);
		managerUser = aUser().asHotelManager().build();
		manager = signUpAndLogIn(managerUser);
		hotelId = createHotel(manager, aHotel().build());
	}

	@Test
	void activationCreatesAYearOfInventoryRowsPerRoom() {
		long deluxe = createRoom(manager, hotelId, aRoom().ofType("Deluxe").withTotalCount(5).build());
		long suite = createRoom(manager, hotelId, aRoom().ofType("Suite").withTotalCount(2).build());

		api(manager)
			.when().patch("/api/v1/admin/hotels/{id}/activate", hotelId)
			.then()
				.statusCode(200)
				.body("active", equalTo(true));

		assertYearOfInventory(deluxe, TODAY, 5);
		assertYearOfInventory(suite, TODAY, 2);
	}

	@Test
	void activatingAgainDoesNotDuplicateRowsButTopsUpTheYear() {
		long roomId = createRoom(manager, hotelId, aRoom().build());
		activate();
		activate();
		assertThat(nightsOf(roomId)).isEqualTo(365);

		clock.advance(Duration.ofDays(1));
		manager = logIn(managerUser); // the old token expired along the way
		activate();

		// The past night stays; the window now reaches one night further
		assertThat(nightsOf(roomId)).isEqualTo(366);
		assertThat(jdbc.queryForObject("select max(date) from inventory where room_id = ?", LocalDate.class, roomId))
			.isEqualTo(TODAY.plusDays(365));
	}

	@Test
	void aHotelWithoutRoomsCannotBeActivated() {
		api(manager)
			.when().patch("/api/v1/admin/hotels/{id}/activate", hotelId)
			.then()
				.statusCode(409)
				.body("type", equalTo("urn:roomkeeper:problem:conflict"));

		api(manager)
			.when().get("/api/v1/admin/hotels/{id}", hotelId)
			.then()
				.body("active", equalTo(false));
	}

	@Test
	void onlyTheOwnerCanActivate() {
		long roomId = createRoom(manager, hotelId, aRoom().build());
		String otherManager = signUpAndLogIn(aUser().asHotelManager().build());

		api(otherManager)
			.when().patch("/api/v1/admin/hotels/{id}/activate", hotelId)
			.then()
				.statusCode(403)
				.body("type", equalTo("urn:roomkeeper:problem:forbidden"));

		assertThat(nightsOf(roomId)).isZero();
	}

	private void activate() {
		api(manager)
			.when().patch("/api/v1/admin/hotels/{id}/activate", hotelId)
			.then()
				.statusCode(200);
	}

	private int nightsOf(long roomId) {
		return jdbc.queryForObject("select count(*) from inventory where room_id = ?", Integer.class, roomId);
	}

	private void assertYearOfInventory(long roomId, LocalDate from, int totalCount) {
		var summary = jdbc.queryForMap("""
				select count(*) as nights, count(distinct date) as distinct_nights,
				       min(date) as first_night, max(date) as last_night,
				       min(total_count) as min_total, max(total_count) as max_total,
				       sum(booked_count + reserved_count) as sold,
				       min(surge_factor) as min_surge, max(surge_factor) as max_surge,
				       bool_or(closed) as any_closed
				from inventory where room_id = ?""", roomId);

		assertThat(summary.get("nights")).as("nights").isEqualTo(365L);
		assertThat(summary.get("distinct_nights")).as("distinct nights").isEqualTo(365L);
		assertThat(summary.get("first_night").toString()).as("first night").isEqualTo(from.toString());
		assertThat(summary.get("last_night").toString()).as("last night").isEqualTo(from.plusDays(364).toString());
		assertThat(summary.get("min_total")).as("total count").isEqualTo(totalCount);
		assertThat(summary.get("max_total")).as("total count").isEqualTo(totalCount);
		assertThat(summary.get("sold")).as("booked + reserved").isEqualTo(0L);
		assertThat(summary.get("min_surge")).as("surge").isEqualTo(new BigDecimal("1.00"));
		assertThat(summary.get("max_surge")).as("surge").isEqualTo(new BigDecimal("1.00"));
		assertThat(summary.get("any_closed")).as("closed").isEqualTo(false);
	}

}
