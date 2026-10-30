package com.roomkeeper.search;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

import javax.sql.DataSource;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.testsupport.MutableClockConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A burst of bookings at one hotel queues one recompute of its cheapest prices, not one per
 * booking: later sales merge into the recompute already waiting. The load test found the
 * per-booking queue filling up at 1,500 holds a second and dropping recomputes. Not
 * {@code @Transactional}: the recomputes run after each booking commits.
 */
@Import(MutableClockConfig.class)
class RepricingBurstIT extends AbstractIntegrationTest {

	private static final String TUESDAY = "2026-03-10";

	private static final int BOOKINGS = 8;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	DataSource dataSource;

	@Autowired
	@Qualifier(RepricingConfig.EXECUTOR)
	ThreadPoolTaskExecutor repricing;

	private long hotelId;

	private long roomId;

	@BeforeEach
	void openAHotelWithTenRooms() {
		clock.setInstant(Instant.parse("2026-03-10T09:00:00Z"));
		String manager = signUpAndLogIn(aUser().asHotelManager().build());
		hotelId = activeHotel(manager, aHotel().build(), aRoom().pricedAt("4000").withTotalCount(10).build());
		roomId = firstRoomOf(manager, hotelId);
	}

	@Test
	void aBurstOfBookingsQueuesAFewRecomputesAndEndsAtTheRightPrice() throws Exception {
		String guest = signUpAndLogIn(aUser().build());
		ThreadPoolExecutor pool = repricing.getThreadPoolExecutor();
		long scheduledBefore = pool.getTaskCount();

		// Holding the hotel's advisory lock keeps its recomputes waiting while the bookings land
		try (Connection blocker = dataSource.getConnection()) {
			blocker.setAutoCommit(false);
			try (PreparedStatement lock = blocker.prepareStatement("select pg_advisory_xact_lock(?)")) {
				lock.setLong(1, hotelId);
				lock.execute();
			}
			for (int i = 0; i < BOOKINGS; i++) {
				idempotentApi(guest).contentType(ContentType.JSON)
					.body(Map.of("roomId", roomId, "checkIn", TUESDAY, "checkOut", "2026-03-11", "rooms", 1))
					.when().post("/api/v1/bookings/init")
					.then().statusCode(201);
			}
			// One running per repricing thread, each blocked on the lock, and one waiting for them,
			// however many bookings there were
			assertThat(pool.getTaskCount() - scheduledBefore).isLessThanOrEqualTo(3);
			blocker.rollback();
		}

		// 8 of 10 rooms held: the occupancy rule adds 20%
		await().atMost(Duration.ofSeconds(10))
			.untilAsserted(() -> assertThat(minPrice()).isEqualByComparingTo("4800.00"));
		await().atMost(Duration.ofSeconds(10)).until(() -> pool.getActiveCount() == 0 && pool.getQueue().isEmpty());
		assertThat(minPrice()).isEqualByComparingTo("4800.00");
	}

	private BigDecimal minPrice() {
		return jdbc.queryForObject("select min_price from hotel_min_price where hotel_id = ? and date = ?::date",
				BigDecimal.class, hotelId, TUESDAY);
	}

}
