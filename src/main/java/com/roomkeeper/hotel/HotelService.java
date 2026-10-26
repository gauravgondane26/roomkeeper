package com.roomkeeper.hotel;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;
import com.roomkeeper.inventory.InventoryChangedEvent;
import com.roomkeeper.inventory.InventoryService;
import org.springframework.context.ApplicationEventPublisher;
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
		hotel.changeDetails(request.name().strip(), request.city().strip(), request.timeZone(),
				normalize(request.amenities()));
		events.publishEvent(new HotelChangedEvent(hotelId));
		return mapper.toResponse(hotel);
	}

	/**
	 * Puts the hotel on sale: every room type gets a year of inventory from the hotel's
	 * today. Activating again is safe and tops the year up.
	 */
	@Transactional
	public HotelResponse activate(long ownerId, long hotelId) {
		Hotel hotel = findOwned(ownerId, hotelId);
		List<Room> hotelRooms = rooms.findByHotelIdOrderById(hotelId);
		if (hotelRooms.isEmpty()) {
			throw new ApiException(ProblemType.CONFLICT, "Add at least one room type before activating the hotel");
		}
		hotel.activate();
		LocalDate today = hotel.today(clock);
		hotelRooms.forEach(room -> inventory.openYear(room.getId(), room.getTotalCount(), today));
		events.publishEvent(InventoryChangedEvent.fromNight(hotelId, today));
		return mapper.toResponse(hotel);
	}

	@Transactional
	public void delete(long ownerId, long hotelId) {
		hotels.delete(findOwned(ownerId, hotelId));
		events.publishEvent(new HotelChangedEvent(hotelId));
	}

	/** The hotel, if it exists and the caller owns it: 404 or 403 otherwise. */
	Hotel findOwned(long ownerId, long hotelId) {
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
