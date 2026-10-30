package com.roomkeeper.search;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executor;

import com.roomkeeper.common.metrics.BusinessMetrics;
import com.roomkeeper.inventory.InventoryChangedEvent;
import com.roomkeeper.pricing.Night;
import com.roomkeeper.pricing.PricingChain;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
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
 * while the first is still held, which starves the pool under concurrent writes. At most
 * one recompute per hotel waits ({@link PendingRecomputes}): later sales merge into it, so
 * a burst of bookings at one hotel queues one recompute rather than one per booking.
 */
@Component
class HotelMinPriceUpdater {

	private static final Logger log = LoggerFactory.getLogger(HotelMinPriceUpdater.class);

	/** Stands in for "no end" in a date range; Postgres dates go far beyond it. */
	private static final LocalDate NO_END = LocalDate.of(9999, 12, 31);

	private final PendingRecomputes pending = new PendingRecomputes();

	private final Executor repricing;

	private final BusinessMetrics metrics;

	private final JdbcClient jdbc;

	private final JdbcTemplate batch;

	private final PricingChain pricing;

	private final EntityManager entityManager;

	private final TransactionTemplate transaction;

	private final SearchCacheVersions searchVersions;

	HotelMinPriceUpdater(@Qualifier(RepricingConfig.EXECUTOR) Executor repricing, BusinessMetrics metrics,
			JdbcClient jdbc, JdbcTemplate batch, PricingChain pricing, EntityManager entityManager,
			PlatformTransactionManager transactions, SearchCacheVersions searchVersions) {
		this.repricing = repricing;
		this.metrics = metrics;
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
		recompute(event.hotelId(), event.from(), event.to());
	}

	/**
	 * After a sale commits, makes sure a recompute of its hotel is waiting: the first sale
	 * schedules one, and later sales merge into it until it starts.
	 */
	@TransactionalEventListener(condition = "#event.sale()")
	void onSale(InventoryChangedEvent event) {
		if (!pending.add(event)) {
			return;
		}
		try {
			repricing.execute(() -> recomputeWaiting(event.hotelId()));
		}
		catch (TaskRejectedException ex) {
			// The booking has committed, so this mustn't fail it. Forgetting the waiting recompute
			// lets the hotel's next sale schedule another; until then its prices may be stale.
			pending.take(event.hotelId());
			metrics.repricingDropped();
			log.warn("Repricing queue full; dropped hotel {}'s recompute", event.hotelId());
		}
	}

	/**
	 * Recomputes the nights the hotel's waiting sales changed. Evicts the city's cached
	 * searches afterwards if a sale emptied or freed up a night, so a search can't cache the
	 * old price again between the sale's commit and the recompute.
	 */
	private void recomputeWaiting(long hotelId) {
		PendingRecomputes.Recompute recompute = pending.take(hotelId);
		try {
			transaction.executeWithoutResult(status -> recompute(hotelId, recompute.from(), recompute.to()));
			if (recompute.evictSearches()) {
				searchVersions.bump(recompute.city());
			}
		}
		catch (RuntimeException ex) {
			log.error("Could not recompute hotel {}'s cheapest prices; they stay stale until its nights change again",
					hotelId, ex);
		}
	}

	/** Re-prices the hotel's nights from {@code from} to {@code until} (null for no end). */
	private void recompute(long hotelId, LocalDate from, LocalDate until) {
		// One recalculation per hotel at a time; the lock is released when the transaction ends.
		// The key is the hotel id: nothing else in the app takes advisory locks.
		jdbc.sql("select pg_advisory_xact_lock(:hotelId)").param("hotelId", hotelId).query().singleRow();
		// Changes made through JPA may still be pending; the query below must see them
		entityManager.flush();
		LocalDate to = until != null ? until : NO_END;

		Map<LocalDate, BigDecimal> cheapest = new TreeMap<>();
		for (SellableNight night : sellableNights(hotelId, from, to)) {
			BigDecimal price = pricing.priceOf(night.toNight());
			cheapest.merge(night.date(), price, BigDecimal::min);
		}

		jdbc.sql("delete from hotel_min_price where hotel_id = :hotelId and date between :from and :to")
			.param("hotelId", hotelId)
			.param("from", from)
			.param("to", to)
			.update();
		batch.batchUpdate("insert into hotel_min_price (hotel_id, date, min_price) values (?, ?, ?)",
				cheapest.entrySet().stream()
					.map(entry -> new Object[] { hotelId, entry.getKey(), entry.getValue() })
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
