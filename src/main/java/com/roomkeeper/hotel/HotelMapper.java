package com.roomkeeper.hotel;

import com.roomkeeper.common.MapperDefaults;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = MapperDefaults.class)
public interface HotelMapper {

	HotelResponse toResponse(Hotel hotel);

	/** {@code reviewer} is who {@code hotel.reviewedBy} is, or null. */
	@Mapping(target = "id", source = "hotel.id")
	@Mapping(target = "reviewedBy", source = "reviewer")
	HotelReviewResponse toReview(Hotel hotel, HotelReviewResponse.Reviewer reviewer);

}
