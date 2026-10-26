package com.roomkeeper.search;

import java.util.List;

import com.roomkeeper.common.MapperDefaults;
import com.roomkeeper.hotel.Hotel;
import com.roomkeeper.hotel.Room;
import org.mapstruct.Mapper;

@Mapper(config = MapperDefaults.class)
public interface HotelInfoMapper {

	HotelInfoResponse toInfo(Hotel hotel, List<Room> rooms);

	HotelInfoResponse.RoomInfo toRoomInfo(Room room);

}
