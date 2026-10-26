package com.roomkeeper.search;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.roomkeeper.inventory.InventoryChangedEvent;
import com.roomkeeper.pricing.Night;
import com.roomkeeper.pricing.PricingChain;
import jakarta.persistence.EntityManager;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps hotel_min_price in step with inventory: when a hotel's nights change, re-prices
 * them in the same transaction and stores each night's cheapest bookable room.
 */
@Component
class HotelMinPriceUpdater {

	/** Stands in for "no end" in a date range; Postgres dates go far beyond it. */
	private static final LocalDate NO_END = LocalDate.of(9999, 12, 31);

	private final JdbcClient jdbc;

	private final JdbcTemplate batch;

	private final PricingChain pricing;

	private final EntityManager entityManager;

	HotelMinPriceUpdater(JdbcClient jdbc, JdbcTemplate batch, PricingChain pricing, EntityManager entityManager) {
		this.jdbc = jdbc;
		this.batch = batch;
		this.pricing = pricing;
		this.entityManager = entityManager;
	}

	@EventListener
	@Transactional(propagation = Propagation.MANDATORY)
	void onInventoryChanged(InventoryChangedEvent event) {
		// Changes made through JPA may still be pending; the query below must see them
		entityManager.flush();
		LocalDate to = event.to() != null ? event.to() : NO_END;

		Map<LocalDate, BigDecimal> cheapest = new TreeMap<>();
		for (SellableNight night : sellableNights(event.hotelId(), event.from(), to)) {
			BigDecimal price = pricing.priceOf(night.toNight());
			cheapest.merge(night.date(), price, BigDecimal::min);
		}

		jdbc.sql("delete from hotel_min_price where hotel_id = :hotelId and date between :from and :to")
			.param("hotelId", event.hotelId())
			.param("from", event.from())
			.param("to", to)
			.update();
		batch.batchUpdate("insert into hotel_min_price (hotel_id, date, min_price) values (?, ?, ?)",
				cheapest.entrySet().stream()
					.map(entry -> new Object[] { event.hotelId(), entry.getKey(), entry.getValue() })
					.toList());
	}

	/** Each room type's open nights in the range that still have a room to sell. */
	private List<SellableNight> sellableNights(long hotelId, LocalDate from, LocalDate to) {
		return jdbc.sql("""
				select i.date, r.base_price, i.surge_factor, i.total_count,
				       i.booked_count + i.reserved_count as sold_count
				from inventory i
				join rooms r on r.id = i.room_id
				where r.hotel_id = :hotelId and i.date between :from and :to
				  and not i.closed and i.booked_count + i.reserved_count < i.total_count""")
			.param("hotelId", hotelId)
			.param("from", from)
			.param("to", to)
			.query(SellableNight.class)
			.list();
	}

	private record SellableNight(LocalDate date, BigDecimal basePrice, BigDecimal surgeFactor, int totalCount,
			int soldCount) {

		Night toNight() {
			return new Night(date, basePrice, surgeFactor, totalCount, soldCount);
		}

	}

}
