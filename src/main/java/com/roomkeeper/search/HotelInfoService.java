package com.roomkeeper.search;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;
import com.roomkeeper.hotel.Hotel;
import com.roomkeeper.hotel.HotelRepository;
import com.roomkeeper.hotel.RoomRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The public view of a hotel. Hotels that aren't on sale don't exist as far as guests can tell. */
@Service
public class HotelInfoService {

	private final HotelRepository hotels;

	private final RoomRepository rooms;

	private final HotelInfoMapper mapper;

	public HotelInfoService(HotelRepository hotels, RoomRepository rooms, HotelInfoMapper mapper) {
		this.hotels = hotels;
		this.rooms = rooms;
		this.mapper = mapper;
	}

	@Transactional(readOnly = true)
	public HotelInfoResponse get(long hotelId) {
		Hotel hotel = hotels.findById(hotelId)
			.filter(Hotel::isActive)
			.orElseThrow(() -> new ApiException(ProblemType.NOT_FOUND, "Hotel " + hotelId + " not found"));
		return mapper.toInfo(hotel, rooms.findByHotelIdOrderById(hotelId));
	}

}
