package com.roomkeeper.hotel;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;

public class RoomTypeExistsException extends ApiException {

	public RoomTypeExistsException(String type) {
		super(ProblemType.ROOM_TYPE_EXISTS, "This hotel already has a room type called " + type);
	}

}
