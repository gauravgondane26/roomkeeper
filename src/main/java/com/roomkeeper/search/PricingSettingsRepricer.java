package com.roomkeeper.search;

import java.time.Clock;
import java.time.ZoneOffset;

import com.roomkeeper.hotel.Hotel;
import com.roomkeeper.hotel.HotelRepository;
import com.roomkeeper.inventory.InventoryChangedEvent;
import com.roomkeeper.pricing.PricingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Re-prices every hotel on sale when the pricing settings ({@code roomkeeper.pricing}, or
 * the rules version) differ from the ones hotel_min_price was worked out with, which
 * pricing_settings records. They're configuration, so they only change with a deploy:
 * checked once at startup, in the background, so a long re-pricing doesn't hold up
 * readiness.
 * <p>
 * Each hotel is re-priced from its today on, in its own transaction, through the same
 * {@link InventoryChangedEvent} a manager's change publishes: {@link HotelMinPriceUpdater}
 * recomputes and {@link HotelCacheEvictor} retires the city's cached searches. Hotels off
 * sale are left alone; putting one back on sale re-prices it. The new settings are recorded
 * only once every hotel is done, so a run cut short, or one where a hotel failed, is
 * repeated at the next start. No lock is needed: two instances starting together both
 * re-price, which is wasted work but safe, since recomputes of a hotel run one at a time.
 * <p>
 * During a rolling deploy, instances still on the old settings keep pricing sales with
 * them until they stop; those hotels are re-priced at their next sale.
 */
@Component
class PricingSettingsRepricer {

	private static final Logger log = LoggerFactory.getLogger(PricingSettingsRepricer.class);

	private final PricingProperties settings;

	private final HotelRepository hotels;

	private final ApplicationEventPublisher events;

	private final JdbcClient jdbc;

	private final TransactionTemplate transaction;

	private final Clock clock;

	PricingSettingsRepricer(PricingProperties settings, HotelRepository hotels, ApplicationEventPublisher events,
			JdbcClient jdbc, PlatformTransactionManager transactions, Clock clock) {
		this.settings = settings;
		this.hotels = hotels;
		this.events = events;
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactions);
		this.clock = clock;
	}

	@EventListener(ApplicationReadyEvent.class)
	void onStartup() {
		Thread.ofVirtual().name("pricing-settings-repricer").start(() -> {
			try {
				repriceIfChanged();
			}
			catch (RuntimeException ex) {
				log.error("Could not check the pricing settings; trying again at the next start", ex);
			}
		});
	}

	/** Re-prices every hotel on sale if the settings changed, and returns how many it re-priced. */
	int repriceIfChanged() {
		String current = settings.fingerprint();
		String recorded = jdbc.sql("select fingerprint from pricing_settings").query(String.class).optional().orElse(null);
		if (current.equals(recorded)) {
			return 0;
		}
		int repriced = 0;
		boolean failed = false;
		for (Hotel hotel : hotels.findByActiveTrueOrderById()) {
			try {
				transaction.executeWithoutResult(status -> events
					.publishEvent(InventoryChangedEvent.fromNight(hotel.getId(), hotel.getCity(), hotel.today(clock))));
				repriced++;
			}
			catch (RuntimeException ex) {
				failed = true;
				log.error("Could not re-price hotel {} for the new pricing settings", hotel.getId(), ex);
			}
		}
		if (failed) {
			log.warn("Re-priced {} hotels for the new pricing settings; the rest are tried again at the next start",
					repriced);
			return repriced;
		}
		jdbc.sql("""
				insert into pricing_settings (id, fingerprint, updated_at) values (true, :fingerprint, :now)
				on conflict (id) do update set fingerprint = excluded.fingerprint, updated_at = excluded.updated_at""")
			.param("fingerprint", current)
			.param("now", clock.instant().atOffset(ZoneOffset.UTC))
			.update();
		log.info("Pricing settings changed; re-priced {} hotels", repriced);
		return repriced;
	}

}
