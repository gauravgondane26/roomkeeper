package com.roomkeeper.search;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;
import com.roomkeeper.hotel.Hotel;
import com.roomkeeper.hotel.HotelRepository;
import com.roomkeeper.hotel.RoomRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Loads a hotel's public view from Postgres, through the hotel info cache. */
@Component
class HotelInfoReads {

	private final HotelRepository hotels;

	private final RoomRepository rooms;

	private final HotelInfoMapper mapper;

	HotelInfoReads(HotelRepository hotels, RoomRepository rooms, HotelInfoMapper mapper) {
		this.hotels = hotels;
		this.rooms = rooms;
		this.mapper = mapper;
	}

	// No version means Redis is unreachable: read without the cache
	@Cacheable(cacheNames = CacheConfig.HOTEL_INFO, key = "#hotelId + ':v' + #version", condition = "#version != null")
	@Transactional(readOnly = true)
	public HotelInfoResponse load(long hotelId, Long version) {
		Hotel hotel = hotels.findById(hotelId)
			.filter(Hotel::isActive)
			.orElseThrow(() -> new ApiException(ProblemType.NOT_FOUND, "Hotel " + hotelId + " not found"));
		return mapper.toInfo(hotel, rooms.findByHotelIdOrderById(hotelId));
	}

}
