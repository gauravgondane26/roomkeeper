package com.roomkeeper.booking;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;
import com.roomkeeper.common.metrics.BusinessMetrics;
import com.roomkeeper.hotel.HotelService;
import com.roomkeeper.hotel.Room;
import com.roomkeeper.hotel.RoomRepository;
import com.roomkeeper.inventory.Inventory;
import com.roomkeeper.inventory.InventoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** A guest's bookings: holding rooms for a stay, and what happens to the hold after. */
@Service
public class BookingService {

	static final int DEFAULT_PAGE_SIZE = 20;

	private final BookingRepository bookings;

	private final RoomRepository rooms;

	private final HotelService hotels;

	private final GuestService guests;

	private final InventoryRepository inventory;

	private final Holds holds;

	private final BookingMapper mapper;

	private final BookingProperties properties;

	private final BusinessMetrics metrics;

	private final TransactionTemplate transaction;

	private final Clock clock;

	BookingService(BookingRepository bookings, RoomRepository rooms, HotelService hotels, GuestService guests,
			InventoryRepository inventory, Holds holds, BookingMapper mapper, BookingProperties properties,
			BusinessMetrics metrics, PlatformTransactionManager transactions, Clock clock) {
		this.bookings = bookings;
		this.rooms = rooms;
		this.hotels = hotels;
		this.guests = guests;
		this.inventory = inventory;
		this.holds = holds;
		this.mapper = mapper;
		this.properties = properties;
		this.metrics = metrics;
		this.transaction = new TransactionTemplate(transactions);
		this.clock = clock;
	}

	/**
	 * Holds the rooms on every night of the stay, at the price the rules give now, until the
	 * hold runs out. Sold out if any night is closed or short of rooms.
	 * <p>
	 * Pessimistic (the default), the stay's nights are locked while the hold is made, so
	 * holds for the same nights wait for each other. Optimistic, they don't wait: each reads
	 * the nights unlocked, and one that finds at commit that another hold changed them first
	 * starts over, up to {@code optimisticAttempts} times, then answers 503 booking-busy.
	 * Either way the database's check constraint refuses an oversold night.
	 */
	public BookingResponse init(long userId, BookingInitRequest request) {
		if (properties.locking() == BookingProperties.Locking.PESSIMISTIC) {
			return transaction.execute(status -> hold(userId, request, true));
		}
		for (int attempt = 1;; attempt++) {
			try {
				return transaction.execute(status -> hold(userId, request, false));
			}
			catch (ObjectOptimisticLockingFailureException ex) {
				if (attempt >= properties.optimisticAttempts()) {
					throw new ApiException(ProblemType.BOOKING_BUSY,
							"Too many guests are booking these nights at once; try again");
				}
				metrics.optimisticRetry();
				backOff(attempt);
			}
		}
	}

	/**
	 * One attempt at holding the stay, in a transaction. Locked, the nights can't change
	 * under it. Unlocked (optimistic), its writes fail at commit if another hold changed one
	 * of the nights after it read them, and nothing it did is kept.
	 */
	private BookingResponse hold(long userId, BookingInitRequest request, boolean lock) {
		// Hotels that aren't on sale don't exist as far as guests can tell
		Room room = rooms.findById(request.roomId())
			.filter(found -> found.getHotel().isActive())
			.orElseThrow(() -> new ApiException(ProblemType.NOT_FOUND, "Room " + request.roomId() + " not found"));
		LocalDate lastNight = holds.lastNightOf(request.checkIn(), request.checkOut(), room.getHotel());
		int count = request.rooms();

		// Locked until commit, in date order like every other writer, so two requests for the last
		// room can't both see it free, and two overlapping stays can't deadlock each other
		List<Inventory> nights = lock ? inventory.lockNights(room.getId(), request.checkIn(), lastNight)
				: inventory.findByRoomIdAndDateBetweenOrderByDate(room.getId(), request.checkIn(), lastNight);
		if (!Inventory.canSellStay(nights, request.checkIn(), request.checkOut(), count)) {
			throw new SoldOutException(room.getId(), count, request.checkIn(), request.checkOut());
		}
		Booking booking = holds.hold(userId, room, request.checkIn(), request.checkOut(), count, nights);
		return mapper.toResponse(booking, clock.instant());
	}

	/**
	 * A random pause of up to 2^attempt ms (at most 50), so holds that collided spread out
	 * instead of colliding again in step.
	 */
	private static void backOff(int attempt) {
		try {
			Thread.sleep(ThreadLocalRandom.current().nextLong(1, Math.min(50, 1L << attempt) + 1));
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new ApiException(ProblemType.BOOKING_BUSY, "Interrupted while waiting to try the hold again");
		}
	}

	@Transactional(readOnly = true)
	public BookingResponse get(long userId, long bookingId) {
		return mapper.toResponse(findOwned(userId, bookingId), clock.instant());
	}

	@Transactional(readOnly = true)
	public BookingStatusResponse status(long userId, long bookingId) {
		return mapper.toStatus(findOwned(userId, bookingId), clock.instant());
	}

	/** The user's bookings, newest first. */
	@Transactional(readOnly = true)
	public BookingPageResponse list(long userId, BookingPageRequest request) {
		return page(request, pageable -> bookings.findByUserId(userId, pageable));
	}

	/** The bookings at a hotel the manager owns, newest first: 404 or 403 for anyone else's. */
	@Transactional(readOnly = true)
	public BookingPageResponse listForHotel(long ownerId, long hotelId, BookingPageRequest request) {
		hotels.findOwned(ownerId, hotelId);
		return page(request, pageable -> bookings.findByRoomHotelId(hotelId, pageable));
	}

	/** One page of the bookings the query finds, newest first. */
	private BookingPageResponse page(BookingPageRequest request, Function<Pageable, Page<Booking>> query) {
		int page = request.page() != null ? request.page() : 0;
		int size = request.size() != null ? request.size() : DEFAULT_PAGE_SIZE;
		Page<Booking> found = query.apply(
				PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
		Instant now = clock.instant();
		return new BookingPageResponse(found.map(booking -> mapper.toResponse(booking, now)).getContent(), page, size,
				found.getTotalElements(), found.getTotalPages());
	}

	/** Puts the user's saved guests on their booking, in place of any already on it, while the rooms are held. */
	@Transactional
	public BookingResponse putGuests(long userId, long bookingId, BookingGuestsRequest request) {
		// Locked, so the expiry job can't release the rooms halfway through
		Booking booking = bookings.lockOwned(bookingId, userId)
			.orElseThrow(() -> new BookingNotFoundException(bookingId));
		// One query for every guest, while the booking is locked
		List<Guest> staying = guests.findAllOwned(userId, new LinkedHashSet<>(request.guestIds()));
		Instant now = clock.instant();
		booking.putGuests(staying, now);
		return mapper.toResponse(booking, now);
	}

	/** The user's booking: 404 if it doesn't exist or is someone else's. */
	private Booking findOwned(long userId, long bookingId) {
		return bookings.findByIdAndUserId(bookingId, userId)
			.orElseThrow(() -> new BookingNotFoundException(bookingId));
	}

}
