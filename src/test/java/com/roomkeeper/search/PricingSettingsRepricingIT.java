package com.roomkeeper.search;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import com.roomkeeper.pricing.PricingProperties;
import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.MutableClock;
import com.roomkeeper.testsupport.MutableClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Stored cheapest prices were worked out with the pricing settings of the time; a deploy
 * that changes them re-prices every hotel on sale, once. Prices made wrong by hand here
 * stand in for prices worked out with older settings.
 */
@Import(MutableClockConfig.class)
class PricingSettingsRepricingIT extends AbstractIntegrationTest {

	private static final String TUESDAY = "2026-03-10";

	private static final String FRIDAY = "2026-03-13";

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PricingProperties settings;

	@Autowired
	PricingSettingsRepricer repricer;

	private long hotelId;

	@BeforeEach
	void openAHotel() {
		// The check run at startup has recorded the settings
		await().atMost(Duration.ofSeconds(30)).until(() -> settings.fingerprint().equals(storedFingerprint()));
		clock.setInstant(Instant.parse("2026-03-10T09:00:00Z"));
		String manager = signUpAndLogIn(aUser().asHotelManager().build());
		hotelId = activeHotel(manager, aHotel().build(), aRoom().pricedAt("4000").withTotalCount(10).build());
	}

	@Test
	void newPricingSettingsRepriceEveryHotelOnSaleAndAreRecorded() {
		pricesWorkedOutWithOlderSettings();
		jdbc.update("update pricing_settings set fingerprint = 'rules=0;older settings'");

		assertThat(repricer.repriceIfChanged()).isPositive();

		assertThat(minPrice(TUESDAY)).isEqualByComparingTo("4000.00");
		assertThat(minPrice(FRIDAY)).as("weekend").isEqualByComparingTo("4600.00");
		assertThat(storedFingerprint()).isEqualTo(settings.fingerprint());
	}

	@Test
	void aDatabaseThatNeverRecordedItsSettingsIsRepricedToo() {
		pricesWorkedOutWithOlderSettings();
		jdbc.update("delete from pricing_settings");

		repricer.repriceIfChanged();

		assertThat(minPrice(TUESDAY)).isEqualByComparingTo("4000.00");
		assertThat(storedFingerprint()).isEqualTo(settings.fingerprint());
	}

	@Test
	void unchangedSettingsRepriceNothing() {
		pricesWorkedOutWithOlderSettings();

		assertThat(repricer.repriceIfChanged()).isZero();

		assertThat(minPrice(TUESDAY)).isEqualByComparingTo("1.00");
	}

	private void pricesWorkedOutWithOlderSettings() {
		jdbc.update("update hotel_min_price set min_price = 1 where hotel_id = ?", hotelId);
	}

	private String storedFingerprint() {
		return jdbc.queryForList("select fingerprint from pricing_settings", String.class).stream().findFirst().orElse(null);
	}

	private BigDecimal minPrice(String date) {
		return jdbc.queryForObject("select min_price from hotel_min_price where hotel_id = ? and date = ?::date",
				BigDecimal.class, hotelId, date);
	}

}
