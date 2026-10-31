package com.roomkeeper.search;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

import com.roomkeeper.pricing.Night;
import com.roomkeeper.pricing.PricingChain;
import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.testsupport.MutableClockConfig;
import com.roomkeeper.testsupport.RaceTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.roomkeeper.testsupport.Concurrently.runTogether;
import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent changes to one hotel's inventory must all succeed and leave hotel_min_price
 * right. Two changes to different room types used to race: both deleted and re-inserted
 * the hotel's rows, and the loser failed on a duplicate key. No @Transactional here: the
 * point is real, separate transactions.
 */
@Import(MutableClockConfig.class)
class HotelMinPriceConcurrencyIT extends AbstractIntegrationTest {

	private static final int THREADS = 12;

	private static final int UPDATES = 2 * THREADS;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PricingChain pricing;

	private String manager;

	private long hotelId;

	private List<Long> roomIds;

	@BeforeEach
	void activateAHotelWithTwoRoomTypes() {
		clock.setInstant(Instant.parse("2026-03-10T09:00:00Z"));
		manager = signUpAndLogIn(aUser().asHotelManager().build());
		hotelId = createHotel(manager, aHotel().build());
		roomIds = List.of(createRoom(manager, hotelId, aRoom().ofType("Deluxe").pricedAt("4000").build()),
				createRoom(manager, hotelId, aRoom().ofType("Suite").pricedAt("5000").build()));
		activateHotel(manager, hotelId);
	}

	@RaceTest
	void concurrentChangesToOneHotelAllSucceedAndLeaveTheCheapestPricesRight() throws Exception {
		// Each thread makes two of the changes, one after the other
		List<Callable<List<Integer>>> managers = new ArrayList<>();
		for (int t = 0; t < THREADS; t++) {
			int first = t;
			managers.add(() -> List.of(changeSurge(first), changeSurge(first + THREADS)));
		}

		assertThat(runTogether(managers)).allSatisfy(statuses -> assertThat(statuses).containsOnly(200));
		assertThat(storedMinPrices()).isEqualTo(expectedMinPrices());
	}

	/** Change {@code i} of {@value #UPDATES}: a surge on one room type, alternating between the two. */
	private int changeSurge(int i) {
		// Surges from 0.50 to 2.80 make either room type the cheaper one on any night
		String surge = new BigDecimal("0.50").add(new BigDecimal("0.10").multiply(BigDecimal.valueOf(i))).toPlainString();
		return api(manager).contentType(ContentType.JSON)
			.body(Map.of("from", "2026-03-11", "to", "2026-06-30", "surgeFactor", surge))
			.when().patch("/api/v1/admin/inventory/rooms/{roomId}", roomIds.get(i % 2))
			.then().extract().statusCode();
	}

	private Map<LocalDate, BigDecimal> storedMinPrices() {
		Map<LocalDate, BigDecimal> prices = new TreeMap<>();
		jdbc.query("select date, min_price from hotel_min_price where hotel_id = ?",
				rs -> {
					prices.put(rs.getObject("date", LocalDate.class), rs.getBigDecimal("min_price"));
				}, hotelId);
		return prices;
	}

	/** What a fresh calculation from the committed inventory gives. */
	private Map<LocalDate, BigDecimal> expectedMinPrices() {
		Map<LocalDate, BigDecimal> prices = new TreeMap<>();
		jdbc.query("""
				select i.date, r.base_price, i.surge_factor, i.total_count, i.booked_count + i.reserved_count as sold
				from inventory i join rooms r on r.id = i.room_id
				where r.hotel_id = ? and not i.closed and i.booked_count + i.reserved_count < i.total_count""",
				rs -> {
					Night night = new Night(rs.getObject("date", LocalDate.class), rs.getBigDecimal("base_price"),
							rs.getBigDecimal("surge_factor"), rs.getInt("total_count"), rs.getInt("sold"));
					prices.merge(night.date(), pricing.priceOf(night), BigDecimal::min);
				}, hotelId);
		return prices;
	}

}
