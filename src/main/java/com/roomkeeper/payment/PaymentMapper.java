package com.roomkeeper.payment;

import com.roomkeeper.common.MapperDefaults;
import org.mapstruct.Mapper;

@Mapper(config = MapperDefaults.class)
public interface PaymentMapper {

	PaymentResponse toResponse(Payment payment);

}
