package com.roomkeeper.inventory;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import com.roomkeeper.common.JobLocks;
import com.roomkeeper.hotel.Hotel;
import com.roomkeeper.hotel.HotelRepository;
import com.roomkeeper.hotel.Room;
import com.roomkeeper.hotel.RoomRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps every room type of every hotel on sale {@link InventoryService#NIGHTS_AHEAD} nights
 * ahead of the hotel's today, and puts back any night in that year that's missing. Nights
 * that exist are left as they are.
 * <p>
 * Runs hourly (roomkeeper.inventory.roll-cron) rather than once a day, because each hotel's
 * day turns at its own midnight: a single daily run would leave hotels far from UTC without
 * their newest night for most of a day. Runs that find nothing to open change nothing.
 * Every instance schedules it; the one holding {@code lock:inventory-roll} runs it.
 */
@Component
public class InventoryRollJob {

	private static final Logger log = LoggerFactory.getLogger(InventoryRollJob.class);

	/** Shorter than the hourly interval, so a crashed instance only costs a run. */
	private static final Duration LEASE = Duration.ofMinutes(50);

	private final HotelRepository hotels;

	private final RoomRepository rooms;

	private final InventoryService inventory;

	private final ApplicationEventPublisher events;

	private final JobLocks locks;

	private final TransactionTemplate transaction;

	private final Clock clock;

	InventoryRollJob(HotelRepository hotels, RoomRepository rooms, InventoryService inventory,
			ApplicationEventPublisher events, JobLocks locks, PlatformTransactionManager transactions, Clock clock) {
		this.hotels = hotels;
		this.rooms = rooms;
		this.inventory = inventory;
		this.events = events;
		this.locks = locks;
		this.transaction = new TransactionTemplate(transactions);
		this.clock = clock;
	}

	@Scheduled(cron = "${roomkeeper.inventory.roll-cron}", zone = "UTC")
	public void run() {
		locks.runLocked("inventory-roll", LEASE, this::rollAll)
			.filter(opened -> opened > 0)
			.ifPresent(opened -> log.info("Opened {} nights of inventory", opened));
	}

	private int rollAll() {
		int opened = 0;
		for (Hotel hotel : hotels.findByActiveTrueOrderById()) {
			try {
				opened += transaction.execute(status -> roll(hotel));
			}
			catch (RuntimeException ex) {
				log.error("Could not roll inventory for hotel {}; trying again next run", hotel.getId(), ex);
			}
		}
		return opened;
	}

	/** Opens the hotel's missing nights in one transaction, reprices them, and returns how many. */
	private int roll(Hotel hotel) {
		LocalDate today = hotel.today(clock);
		List<Room> hotelRooms = rooms.findByHotelIdOrderById(hotel.getId());
		int opened = hotelRooms.stream()
			.mapToInt(room -> inventory.openYear(room.getId(), room.getTotalCount(), today))
			.sum();
		if (opened > 0) {
			events.publishEvent(InventoryChangedEvent.fromNight(hotel.getId(), hotel.getCity(), today));
		}
		return opened;
	}

}
