package com.roomkeeper.booking;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;
import com.roomkeeper.hotel.Room;
import com.roomkeeper.inventory.Inventory;
import com.roomkeeper.inventory.InventoryRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guests' places in line for sold-out stays. Rooms that come free are offered by
 * {@link WaitlistOffers}; here guests join, look, and leave.
 */
@Service
public class WaitlistService {

	private final WaitlistRepository entries;

	private final InventoryRepository inventory;

	private final Holds holds;

	private final Clock clock;

	WaitlistService(WaitlistRepository entries, InventoryRepository inventory, Holds holds, Clock clock) {
		this.entries = entries;
		this.inventory = inventory;
		this.holds = holds;
		this.clock = clock;
	}

	/**
	 * Puts the guest in line for the stay. Only a stay that's sold out now, with every night
	 * on sale, open, and with at least that many rooms in all: otherwise rooms coming free
	 * could never make it fit, and the guest would wait for nothing. Once per guest and stay.
	 */
	@Transactional
	public WaitlistEntryResponse join(long userId, WaitlistJoinRequest request) {
		Room room = holds.roomOnSale(request.roomId());
		LocalDate checkIn = request.checkIn();
		LocalDate checkOut = request.checkOut();
		LocalDate lastNight = holds.lastNightOf(checkIn, checkOut, room.getHotel());
		int count = request.rooms();

		List<Inventory> nights = inventory.findByRoomIdAndDateBetweenOrderByDate(room.getId(), checkIn, lastNight);
		if (Inventory.canSellStay(nights, checkIn, checkOut, count)) {
			throw new ApiException(ProblemType.STAY_AVAILABLE, "Room type " + room.getId() + " has " + count
					+ (count == 1 ? " room" : " rooms") + " free on every night of the stay: hold them instead");
		}
		boolean waitable = nights.size() == ChronoUnit.DAYS.between(checkIn, checkOut)
				&& nights.stream().noneMatch(night -> night.isClosed() || night.getTotalCount() < count);
		if (!waitable) {
			throw new ApiException(ProblemType.STAY_NOT_ON_SALE, "Some night of the stay is closed, not on sale yet,"
					+ " or has fewer than " + count + " rooms in all: freed rooms could never make it fit");
		}
		if (entries.isInLine(userId, room.getId(), checkIn, checkOut, WaitlistStatus.WAITING)) {
			throw alreadyWaiting(room);
		}
		try {
			return WaitlistEntryResponse.of(
					entries.saveAndFlush(new WaitlistEntry(userId, room, checkIn, checkOut, count, clock.instant())));
		}
		catch (DataIntegrityViolationException ex) {
			// The same guest joined for the same stay at the same moment (the unique index)
			throw alreadyWaiting(room);
		}
	}

	/** The guest's places in line, newest first. */
	@Transactional(readOnly = true)
	public List<WaitlistEntryResponse> list(long userId) {
		return entries.findByUserIdOrderByCreatedAtDescIdDesc(userId).stream().map(WaitlistEntryResponse::of).toList();
	}

	/** The guest's place in line: 404 if it doesn't exist or is someone else's. */
	@Transactional(readOnly = true)
	public WaitlistEntryResponse get(long userId, long entryId) {
		return entries.findById(entryId)
			.filter(entry -> entry.getUserId() == userId)
			.map(WaitlistEntryResponse::of)
			.orElseThrow(() -> notFound(entryId));
	}

	/** The guest gives up their place; only while still waiting. */
	@Transactional
	public void leave(long userId, long entryId) {
		entries.lockOwned(entryId, userId).orElseThrow(() -> notFound(entryId)).leave();
	}

	private static ApiException alreadyWaiting(Room room) {
		return new ApiException(ProblemType.ALREADY_WAITING,
				"You're already waiting for this stay at room type " + room.getId());
	}

	private static ApiException notFound(long entryId) {
		return new ApiException(ProblemType.NOT_FOUND, "Waitlist entry " + entryId + " not found");
	}

}
