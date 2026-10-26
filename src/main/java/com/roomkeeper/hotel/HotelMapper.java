package com.roomkeeper.hotel;

import com.roomkeeper.common.MapperDefaults;
import org.mapstruct.Mapper;

@Mapper(config = MapperDefaults.class)
public interface HotelMapper {

	HotelResponse toResponse(Hotel hotel);

	HotelReviewResponse toReview(Hotel hotel);

}
