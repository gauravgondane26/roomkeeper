package com.roomkeeper.inventory;

import com.roomkeeper.common.MapperDefaults;
import org.mapstruct.Mapper;

@Mapper(config = MapperDefaults.class)
public interface InventoryMapper {

	InventoryResponse toResponse(Inventory inventory);

}
