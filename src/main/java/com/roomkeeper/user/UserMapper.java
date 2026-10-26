package com.roomkeeper.user;

import com.roomkeeper.common.MapperDefaults;
import org.mapstruct.Mapper;

@Mapper(config = MapperDefaults.class)
public interface UserMapper {

	UserResponse toResponse(User user);

}
