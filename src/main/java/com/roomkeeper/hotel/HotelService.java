package com.roomkeeper.hotel;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;
import com.roomkeeper.inventory.InventoryChangedEvent;
import com.roomkeeper.inventory.InventoryService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Hotel admin. A manager can only see and change the hotels they own. */
@Service
public class HotelService {

	private final HotelRepository hotels;

	private final RoomRepository rooms;

	private final InventoryService inventory;

	private final HotelMapper mapper;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	public HotelService(HotelRepository hotels, RoomRepository rooms, InventoryService inventory, HotelMapper mapper,
			ApplicationEventPublisher events, Clock clock) {
		this.hotels = hotels;
		this.rooms = rooms;
		this.inventory = inventory;
		this.mapper = mapper;
		this.events = events;
		this.clock = clock;
	}

	@Transactional
	public HotelResponse create(long ownerId, HotelRequest request) {
		Hotel hotel = new Hotel(ownerId, request.name().strip(), request.city().strip(), request.timeZone(),
				normalize(request.amenities()), clock.instant());
		return mapper.toResponse(hotels.save(hotel));
	}

	@Transactional(readOnly = true)
	public List<HotelResponse> listOwned(long ownerId) {
		return hotels.findByOwnerIdOrderById(ownerId).stream().map(mapper::toResponse).toList();
	}

	@Transactional(readOnly = true)
	public HotelResponse get(long ownerId, long hotelId) {
		return mapper.toResponse(findOwned(ownerId, hotelId));
	}

	@Transactional
	public HotelResponse update(long ownerId, long hotelId, HotelRequest request) {
		Hotel hotel = findOwned(ownerId, hotelId);
		String previousCity = hotel.getCity();
		hotel.changeDetails(request.name().strip(), request.city().strip(), request.timeZone(),
				normalize(request.amenities()));
		// A move leaves stale results in the old city's searches too
		events.publishEvent(new HotelChangedEvent(hotelId, new HashSet<>(List.of(previousCity, hotel.getCity()))));
		return mapper.toResponse(hotel);
	}

	/**
	 * Puts an approved hotel on sale: every room type gets a year of inventory from the
	 * hotel's today. Activating again is safe and tops the year up.
	 */
	@Transactional
	public HotelResponse activate(long ownerId, long hotelId) {
		Hotel hotel = findOwned(ownerId, hotelId);
		if (hotel.getApprovalStatus() != ApprovalStatus.APPROVED) {
			throw new ApiException(ProblemType.HOTEL_NOT_APPROVED,
					"Hotel " + hotelId + " can go on sale once an admin approves it (it is "
							+ hotel.getApprovalStatus() + ")");
		}
		List<Room> hotelRooms = rooms.findByHotelIdOrderById(hotelId);
		if (hotelRooms.isEmpty()) {
			throw new ApiException(ProblemType.CONFLICT, "Add at least one room type before activating the hotel");
		}
		hotel.activate();
		LocalDate today = hotel.today(clock);
		hotelRooms.forEach(room -> inventory.openYear(room.getId(), room.getTotalCount(), today));
		events.publishEvent(InventoryChangedEvent.fromNight(hotelId, hotel.getCity(), today));
		return mapper.toResponse(hotel);
	}

	/**
	 * Takes the hotel off sale: guests can't find or book it, while bookings it already has
	 * carry on. Activating puts it back. Deactivating an inactive hotel changes nothing.
	 */
	@Transactional
	public HotelResponse deactivate(long ownerId, long hotelId) {
		Hotel hotel = findOwned(ownerId, hotelId);
		if (hotel.isActive()) {
			hotel.deactivate();
			events.publishEvent(HotelChangedEvent.of(hotel));
		}
		return mapper.toResponse(hotel);
	}

	/** Only a hotel that has never had a booking: bookings are history it keeps. */
	@Transactional
	public void delete(long ownerId, long hotelId) {
		Hotel hotel = findOwned(ownerId, hotelId);
		if (rooms.hotelHasBookings(hotelId)) {
			throw hasBookings(hotelId);
		}
		try {
			hotels.delete(hotel);
			hotels.flush();
		}
		catch (DataIntegrityViolationException ex) {
			// A booking came in after the check; the foreign key from bookings caught it
			throw hasBookings(hotelId);
		}
		events.publishEvent(HotelChangedEvent.of(hotel));
	}

	private static ApiException hasBookings(long hotelId) {
		return new ApiException(ProblemType.HAS_BOOKINGS,
				"Hotel " + hotelId + " has bookings, so it can't be deleted; take it off sale instead");
	}

	/** The hotel, if it exists and the caller owns it: 404 or 403 otherwise. */
	public Hotel findOwned(long ownerId, long hotelId) {
		Hotel hotel = hotels.findById(hotelId)
			.orElseThrow(() -> new ApiException(ProblemType.NOT_FOUND, "Hotel " + hotelId + " not found"));
		if (!hotel.getOwnerId().equals(ownerId)) {
			throw new ApiException(ProblemType.FORBIDDEN, "You don't manage hotel " + hotelId);
		}
		return hotel;
	}

	/** Trimmed, without duplicates, in the order given. */
	private static List<String> normalize(List<String> amenities) {
		if (amenities == null) {
			return List.of();
		}
		return List.copyOf(new LinkedHashSet<>(amenities.stream().map(String::strip).toList()));
	}

}
