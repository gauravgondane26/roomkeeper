package com.roomkeeper.hotel;

import com.roomkeeper.common.MapperDefaults;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = MapperDefaults.class)
public interface RoomMapper {

	// Reading the id of the lazy hotel doesn't load it
	@Mapping(target = "hotelId", source = "hotel.id")
	RoomResponse toResponse(Room room);

}
