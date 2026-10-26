package com.roomkeeper.user;

public enum Role {

	GUEST,
	HOTEL_MANAGER,
	/** Reviews hotels before they go on sale. Never chosen at signup: see AdminAccountBootstrap. */
	ADMIN

}
