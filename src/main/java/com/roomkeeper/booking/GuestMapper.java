package com.roomkeeper.booking;

import com.roomkeeper.common.MapperDefaults;
import org.mapstruct.Mapper;

@Mapper(config = MapperDefaults.class)
public interface GuestMapper {

	GuestResponse toResponse(Guest guest);

}
