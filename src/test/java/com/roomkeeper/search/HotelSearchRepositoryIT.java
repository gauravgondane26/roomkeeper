package com.roomkeeper.search;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.roomkeeper.testsupport.AbstractDataTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The availability query, against real Postgres. A hotel matches when at least one room
 * type has enough free rooms on every night of the stay. The stay here is Thursday 12 to
 * Sunday 15 March: the nights of the 12th, 13th, and 14th.
 */
@Import(HotelSearchRepository.class)
class HotelSearchRepositoryIT extends AbstractDataTest {

	private static final Instant NOW = Instant.parse("2026-03-10T09:00:00Z");

	private static final LocalDate CHECK_IN = LocalDate.parse("2026-03-12");

	private static final LocalDate CHECK_OUT = LocalDate.parse("2026-03-15");

	// Unique per test, so hotels left by other tests in the shared database never match
	private String city;

	private long ownerId;

	@Autowired
	HotelSearchRepository repository;

	@BeforeEach
	void createAnOwner() {
		city = "City-" + UUID.randomUUID();
		ownerId = insertManager();
	}

	@Test
	void findsAHotelWithARoomFreeEveryNight() {
		long hotel = activeHotel("UTC");
		openNights(insertRoom(hotel), 5);

		assertThat(searchIds(1)).containsExactly(hotel);
	}

	@Test
	void skipsAHotelWithOneSoldOutNight() {
		long hotel = activeHotel("UTC");
		long room = insertRoom(hotel);
		openNights(room, 5);
		setBooked(room, LocalDate.parse("2026-03-13"), 5);

		assertThat(searchIds(1)).isEmpty();
	}

	@Test
	void anyOneRoomTypeFreeEveryNightIsEnough() {
		long hotel = activeHotel("UTC");
		long deluxe = insertRoom(hotel);
		long suite = insertRoom(hotel);
		openNights(deluxe, 5);
		openNights(suite, 2);
		setBooked(deluxe, LocalDate.parse("2026-03-13"), 5);

		assertThat(searchIds(1)).containsExactly(hotel);
	}

	@Test
	void theSameRoomTypeMustBeFreeEveryNight() {
		// Each night has a free room, but switching rooms mid-stay isn't offered
		long hotel = activeHotel("UTC");
		long deluxe = insertRoom(hotel);
		long suite = insertRoom(hotel);
		openNights(deluxe, 5);
		openNights(suite, 2);
		setBooked(deluxe, LocalDate.parse("2026-03-14"), 5);
		setBooked(suite, LocalDate.parse("2026-03-12"), 2);

		assertThat(searchIds(1)).isEmpty();
	}

	@Test
	void needsEnoughRoomsForTheParty() {
		long hotel = activeHotel("UTC");
		long room = insertRoom(hotel);
		openNights(room, 5);
		setBooked(room, LocalDate.parse("2026-03-13"), 3);

		assertThat(searchIds(2)).containsExactly(hotel);
		assertThat(searchIds(3)).isEmpty();
	}

	@Test
	void skipsClosedNights() {
		long hotel = activeHotel("UTC");
		long room = insertRoom(hotel);
		openNights(room, 5);
		jdbc.update("update inventory set closed = true where room_id = ? and date = ?", room,
				LocalDate.parse("2026-03-14"));

		assertThat(searchIds(1)).isEmpty();
	}

	@Test
	void skipsInactiveHotels() {
		long hotel = insertHotel(ownerId, city, "UTC", false);
		openNights(insertRoom(hotel), 5);

		assertThat(searchIds(1)).isEmpty();
	}

	@Test
	void skipsAStayReachingPastTheInventory() {
		long hotel = activeHotel("UTC");
		long room = insertRoom(hotel);
		// Only the 12th and 13th exist
		insertNight(room, LocalDate.parse("2026-03-12"), 5);
		insertNight(room, LocalDate.parse("2026-03-13"), 5);

		assertThat(searchIds(1)).isEmpty();
	}

	@Test
	void matchesTheCityIgnoringCaseAndOnlyThatCity() {
		long hotel = activeHotel("UTC");
		openNights(insertRoom(hotel), 5);

		var upperCase = repository.search(new SearchCriteria(city.toUpperCase(), CHECK_IN, CHECK_OUT, 1), NOW, 20, 0);
		var otherCity = repository.search(new SearchCriteria(city + "-elsewhere", CHECK_IN, CHECK_OUT, 1), NOW, 20, 0);

		assertThat(upperCase).extracting(HotelMatch::id).containsExactly(hotel);
		assertThat(otherCity).isEmpty();
	}

	@Test
	void skipsAStayThatAlreadyStartedWhereTheHotelIs() {
		// 21:00 UTC on the 11th is already the 12th in Kolkata, so check-in on the 11th is in the past
		Instant lateOnThe11th = Instant.parse("2026-03-11T21:00:00Z");
		long kolkata = activeHotel("Asia/Kolkata");
		long utc = activeHotel("UTC");
		openNightsFrom(insertRoom(kolkata), "2026-03-11", 5);
		openNightsFrom(insertRoom(utc), "2026-03-11", 5);
		var stayFromThe11th = new SearchCriteria(city, LocalDate.parse("2026-03-11"), CHECK_OUT, 1);

		assertThat(repository.search(stayFromThe11th, lateOnThe11th, 20, 0)).extracting(HotelMatch::id)
			.containsExactly(utc);
	}

	@Test
	void returnsDetailsAndTheCheapestNightlyPriceOfTheStay() {
		long hotel = activeHotel("UTC");
		openNights(insertRoom(hotel), 5);
		minPrice(hotel, "2026-03-11", "1000.00"); // before the stay: ignored
		minPrice(hotel, "2026-03-12", "4000.00");
		minPrice(hotel, "2026-03-13", "3500.00");
		minPrice(hotel, "2026-03-14", "4600.00");

		HotelMatch match = repository.search(criteria(1), NOW, 20, 0).getFirst();

		assertThat(match.name()).isEqualTo("Hotel " + hotel);
		assertThat(match.city()).isEqualTo(city);
		assertThat(match.amenities()).containsExactly("wifi", "pool");
		assertThat(match.fromPricePerNight()).isEqualByComparingTo("3500.00");
	}

	@Test
	void ordersByPriceThenIdAndPages() {
		long pricey = activeHotel("UTC");
		long cheap = activeHotel("UTC");
		long middle = activeHotel("UTC");
		for (long hotel : new long[] { pricey, cheap, middle }) {
			openNights(insertRoom(hotel), 5);
		}
		minPrice(pricey, "2026-03-12", "9000.00");
		minPrice(cheap, "2026-03-12", "2000.00");
		minPrice(middle, "2026-03-12", "5000.00");

		assertThat(repository.search(criteria(1), NOW, 2, 0)).extracting(HotelMatch::id).containsExactly(cheap, middle);
		assertThat(repository.search(criteria(1), NOW, 2, 2)).extracting(HotelMatch::id).containsExactly(pricey);
		assertThat(repository.count(criteria(1), NOW)).isEqualTo(3);
	}

	private SearchCriteria criteria(int rooms) {
		return new SearchCriteria(city, CHECK_IN, CHECK_OUT, rooms);
	}

	private List<Long> searchIds(int rooms) {
		return repository.search(criteria(rooms), NOW, 20, 0).stream().map(HotelMatch::id).toList();
	}

	private long activeHotel(String timeZone) {
		return insertHotel(ownerId, city, timeZone, true);
	}

	/** Open nights from the 10th to the 20th, each with {@code total} rooms. */
	private void openNights(long roomId, int total) {
		openNightsFrom(roomId, "2026-03-10", total);
	}

	private void openNightsFrom(long roomId, String first, int total) {
		LocalDate night = LocalDate.parse(first);
		while (!night.isAfter(LocalDate.parse("2026-03-20"))) {
			insertNight(roomId, night, total);
			night = night.plusDays(1);
		}
	}

	private void minPrice(long hotelId, String night, String price) {
		jdbc.update("insert into hotel_min_price (hotel_id, date, min_price) values (?, ?, ?)", hotelId,
				LocalDate.parse(night), new BigDecimal(price));
	}

}
