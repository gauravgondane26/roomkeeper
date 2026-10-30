package com.roomkeeper.booking;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.InvalidFieldException;
import com.roomkeeper.common.error.ProblemType;
import com.roomkeeper.common.metrics.BusinessMetrics;
import com.roomkeeper.hotel.Hotel;
import com.roomkeeper.hotel.HotelService;
import com.roomkeeper.hotel.Room;
import com.roomkeeper.hotel.RoomRepository;
import com.roomkeeper.inventory.Inventory;
import com.roomkeeper.inventory.InventoryChangedEvent;
import com.roomkeeper.inventory.InventoryRepository;
import com.roomkeeper.pricing.PriceQuote;
import com.roomkeeper.pricing.PricingChain;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A guest's bookings: holding rooms for a stay, and what happens to the hold after. */
@Service
public class BookingService {

	static final int MAX_NIGHTS = 30;

	static final int DEFAULT_PAGE_SIZE = 20;

	private final BookingRepository bookings;

	private final RoomRepository rooms;

	private final HotelService hotels;

	private final GuestService guests;

	private final InventoryRepository inventory;

	private final PricingChain pricing;

	private final BookingMapper mapper;

	private final BookingProperties properties;

	private final ApplicationEventPublisher events;

	private final BusinessMetrics metrics;

	private final Clock clock;

	public BookingService(BookingRepository bookings, RoomRepository rooms, HotelService hotels, GuestService guests,
			InventoryRepository inventory, PricingChain pricing, BookingMapper mapper, BookingProperties properties,
			ApplicationEventPublisher events, BusinessMetrics metrics, Clock clock) {
		this.bookings = bookings;
		this.rooms = rooms;
		this.hotels = hotels;
		this.guests = guests;
		this.inventory = inventory;
		this.pricing = pricing;
		this.mapper = mapper;
		this.properties = properties;
		this.events = events;
		this.metrics = metrics;
		this.clock = clock;
	}

	/**
	 * Holds the rooms on every night of the stay, at the price the rules give now, until the
	 * hold runs out. Sold out if any night is closed or short of rooms.
	 */
	@Transactional
	public BookingResponse init(long userId, BookingInitRequest request) {
		// Hotels that aren't on sale don't exist as far as guests can tell
		Room room = rooms.findById(request.roomId())
			.filter(found -> found.getHotel().isActive())
			.orElseThrow(() -> new ApiException(ProblemType.NOT_FOUND, "Room " + request.roomId() + " not found"));
		Hotel hotel = room.getHotel();
		LocalDate lastNight = lastNightOf(request, hotel);
		int count = request.rooms();

		// Locked until commit, in date order like every other writer, so two requests for the last
		// room can't both see it free, and two overlapping stays can't deadlock each other
		List<Inventory> nights = inventory.lockNights(room.getId(), request.checkIn(), lastNight);
		if (!Inventory.canSellStay(nights, request.checkIn(), request.checkOut(), count)) {
			throw new SoldOutException(room.getId(), count, request.checkIn(), request.checkOut());
		}
		// Priced before the hold, as the guest saw it
		PriceQuote quote = pricing.quote(nights.stream().map(night -> night.toNight(room.getBasePrice())).toList(),
				count);
		nights.forEach(night -> night.hold(count));

		Instant now = clock.instant();
		Booking booking = bookings.save(new Booking(userId, room, request.checkIn(), request.checkOut(), count,
				quote.total(), now.plus(properties.holdDuration()), now));
		events.publishEvent(InventoryChangedEvent.sale(hotel.getId(), hotel.getCity(), request.checkIn(), lastNight,
				Inventory.anySoldOut(nights)));
		metrics.bookingCreated();
		return mapper.toResponse(booking, now);
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

	/** The stay's last night, after checking the stay is 1 to {@value #MAX_NIGHTS} nights from the hotel's today. */
	private LocalDate lastNightOf(BookingInitRequest request, Hotel hotel) {
		if (request.checkIn().isBefore(hotel.today(clock))) {
			throw new InvalidFieldException("checkIn", "must not be in the past");
		}
		long nights = ChronoUnit.DAYS.between(request.checkIn(), request.checkOut());
		if (nights < 1) {
			throw new InvalidFieldException("checkOut", "must be after checkIn");
		}
		if (nights > MAX_NIGHTS) {
			throw new InvalidFieldException("checkOut", "must be at most " + MAX_NIGHTS + " nights after checkIn");
		}
		return request.checkOut().minusDays(1);
	}

}
