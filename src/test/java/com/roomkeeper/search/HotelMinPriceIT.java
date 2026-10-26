package com.roomkeeper.search;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.testsupport.MutableClockConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * hotel_min_price holds each night's cheapest bookable room, priced by the full rule chain,
 * and follows every change to the hotel's rooms and inventory.
 */
@Import(MutableClockConfig.class)
class HotelMinPriceIT extends AbstractIntegrationTest {

	private static final String TUESDAY = "2026-03-10";

	private static final String WEDNESDAY = "2026-03-11";

	private static final String FRIDAY = "2026-03-13";

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcTemplate jdbc;

	private String manager;

	private long hotelId;

	private long deluxe;

	@BeforeEach
	void activateAHotelWithTwoRoomTypes() {
		clock.setInstant(Instant.parse("2026-03-10T09:00:00Z"));
		manager = signUpAndLogIn(aUser().asHotelManager().build());
		hotelId = createHotel(manager, aHotel().build());
		deluxe = createRoom(manager, hotelId, aRoom().ofType("Deluxe").pricedAt("4000").withTotalCount(5).build());
		createRoom(manager, hotelId, aRoom().ofType("Suite").pricedAt("7000").withTotalCount(2).build());
	}

	@Test
	void anInactiveHotelHasNoPrices() {
		assertThat(nightsPriced()).isZero();
	}

	@Test
	void activationPricesEveryNightWithTheFullRuleChain() {
		activate();

		assertThat(nightsPriced()).isEqualTo(365);
		assertThat(minPrice(TUESDAY)).isEqualByComparingTo("4000.00");
		assertThat(minPrice(FRIDAY)).as("weekend").isEqualByComparingTo("4600.00");
		assertThat(minPrice("2026-12-25")).as("a Friday holiday").isEqualByComparingTo("5750.00");
	}

	@Test
	void closingTheCheapestRoomsNightLeavesTheNextCheapest() {
		activate();

		patchInventory(deluxe, Map.of("from", WEDNESDAY, "to", WEDNESDAY, "closed", true));

		assertThat(minPrice(WEDNESDAY)).isEqualByComparingTo("7000.00");
		assertThat(minPrice(TUESDAY)).isEqualByComparingTo("4000.00");
	}

	@Test
	void aNightWithNothingToSellHasNoPrice() {
		activate();
		long suite = roomIdOf("Suite");

		patchInventory(deluxe, Map.of("from", WEDNESDAY, "to", WEDNESDAY, "closed", true));
		patchInventory(suite, Map.of("from", WEDNESDAY, "to", WEDNESDAY, "totalCount", 0));

		assertThat(priceRow(WEDNESDAY)).isEmpty();
	}

	@Test
	void surgeCountsTowardsTheCheapestPrice() {
		activate();

		patchInventory(deluxe, Map.of("from", WEDNESDAY, "to", WEDNESDAY, "surgeFactor", 2));

		assertThat(minPrice(WEDNESDAY)).isEqualByComparingTo("7000.00");
	}

	@Test
	void renamingARoomTypeOrChangingItsCapacityDoesNotRepriceTheHotel() {
		activate();
		// A marker no real recalculation would produce
		jdbc.update("update hotel_min_price set min_price = 1.00 where hotel_id = ? and date = ?", hotelId,
				LocalDate.parse(TUESDAY));

		api(manager).contentType(ContentType.JSON)
			.body(aRoom().ofType("Deluxe Sea View").pricedAt("4000").sleeping(3).withTotalCount(5).build())
			.when().put("/api/v1/admin/hotels/{hotelId}/rooms/{roomId}", hotelId, deluxe)
			.then().statusCode(200);

		assertThat(minPrice(TUESDAY)).isEqualByComparingTo("1.00");
	}

	@Test
	void changingARoomsBasePriceRepricesItsNights() {
		activate();

		api(manager).contentType(ContentType.JSON)
			.body(aRoom().ofType("Deluxe").pricedAt("3500").withTotalCount(5).build())
			.when().put("/api/v1/admin/hotels/{hotelId}/rooms/{roomId}", hotelId, deluxe)
			.then().statusCode(200);

		assertThat(minPrice(TUESDAY)).isEqualByComparingTo("3500.00");
		assertThat(minPrice("2027-03-09")).isEqualByComparingTo("3500.00");
	}

	@Test
	void addingACheaperRoomToAnActiveHotelLowersThePrice() {
		activate();

		createRoom(manager, hotelId, aRoom().ofType("Standard").pricedAt("2500").build());

		assertThat(minPrice(TUESDAY)).isEqualByComparingTo("2500.00");
	}

	@Test
	void deletingTheCheapestRoomRaisesThePrice() {
		activate();

		api(manager)
			.when().delete("/api/v1/admin/hotels/{hotelId}/rooms/{roomId}", hotelId, deluxe)
			.then().statusCode(204);

		assertThat(minPrice(TUESDAY)).isEqualByComparingTo("7000.00");
	}

	private void activate() {
		activateHotel(manager, hotelId);
	}

	private void patchInventory(long roomId, Map<String, Object> body) {
		api(manager).contentType(ContentType.JSON).body(body)
			.when().patch("/api/v1/admin/inventory/rooms/{roomId}", roomId)
			.then().statusCode(200);
	}

	private long roomIdOf(String type) {
		return jdbc.queryForObject("select id from rooms where hotel_id = ? and type = ?", Long.class, hotelId, type);
	}

	private int nightsPriced() {
		return jdbc.queryForObject("select count(*) from hotel_min_price where hotel_id = ?", Integer.class, hotelId);
	}

	private BigDecimal minPrice(String night) {
		List<BigDecimal> row = priceRow(night);
		assertThat(row).as("a price for " + night).hasSize(1);
		return row.getFirst();
	}

	private List<BigDecimal> priceRow(String night) {
		return jdbc.queryForList("select min_price from hotel_min_price where hotel_id = ? and date = ?",
				BigDecimal.class, hotelId, LocalDate.parse(night));
	}

}
