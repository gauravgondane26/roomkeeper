package com.roomkeeper.booking;

import com.roomkeeper.common.MapperDefaults;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = MapperDefaults.class, uses = GuestMapper.class)
public interface BookingMapper {

	@Mapping(target = "hotelId", source = "room.hotel.id")
	@Mapping(target = "hotelName", source = "room.hotel.name")
	@Mapping(target = "roomId", source = "room.id")
	@Mapping(target = "roomType", source = "room.type")
	@Mapping(target = "rooms", source = "roomsCount")
	BookingResponse toResponse(Booking booking);

}
