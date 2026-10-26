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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps hotel_min_price in step with inventory: when a hotel's nights change, re-prices
 * them and stores each night's cheapest bookable room.
 * <p>
 * Each recalculation first takes a per-hotel advisory lock, held until its transaction
 * ends. Recalculations for one hotel therefore run one at a time, and each starts only
 * after the previous writer committed, so it reads that writer's changes: two concurrent
 * changes to different room types can't leave a price worked out from stale data, or
 * collide re-inserting the same rows. It can't deadlock: holding the lock, it only reads
 * inventory and writes hotel_min_price, which nothing else writes.
 * <p>
 * Changes by managers and jobs are recomputed in the writer's transaction, on the writer's
 * connection, so prices are up to date when the change commits. Sales (bookings holding or
 * releasing rooms) are frequent and contend on the same nights, so they're recomputed after
 * commit on the {@link RepricingConfig} threads instead: the booking doesn't hold its
 * inventory locks, or wait behind other recomputes, for the cheapest price. They can't run
 * on the request thread after commit: a new transaction there needs a second connection
 * while the first is still held, which starves the pool under concurrent writes.
 */
@Component
class HotelMinPriceUpdater {

	/** Stands in for "no end" in a date range; Postgres dates go far beyond it. */
	private static final LocalDate NO_END = LocalDate.of(9999, 12, 31);

	private final JdbcClient jdbc;

	private final JdbcTemplate batch;

	private final PricingChain pricing;

	private final EntityManager entityManager;

	private final TransactionTemplate transaction;

	private final SearchCacheVersions searchVersions;

	HotelMinPriceUpdater(JdbcClient jdbc, JdbcTemplate batch, PricingChain pricing, EntityManager entityManager,
			PlatformTransactionManager transactions, SearchCacheVersions searchVersions) {
		this.jdbc = jdbc;
		this.batch = batch;
		this.pricing = pricing;
		this.entityManager = entityManager;
		this.transaction = new TransactionTemplate(transactions);
		this.searchVersions = searchVersions;
	}

	@EventListener(condition = "!#event.sale()")
	@Transactional(propagation = Propagation.MANDATORY)
	void onInventoryChanged(InventoryChangedEvent event) {
		recompute(event);
	}

	/**
	 * Evicts the city's cached searches afterwards if the sale emptied or freed up a night, so
	 * a search can't cache the old price again between the sale's commit and the recompute.
	 */
	@Async(RepricingConfig.EXECUTOR)
	@TransactionalEventListener(condition = "#event.sale()")
	void onSale(InventoryChangedEvent event) {
		transaction.executeWithoutResult(status -> recompute(event));
		if (event.evictSearches()) {
			searchVersions.bump(event.city());
		}
	}

	private void recompute(InventoryChangedEvent event) {
		// One recalculation per hotel at a time; the lock is released when the transaction ends.
		// The key is the hotel id: nothing else in the app takes advisory locks.
		jdbc.sql("select pg_advisory_xact_lock(:hotelId)").param("hotelId", event.hotelId()).query().singleRow();
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
