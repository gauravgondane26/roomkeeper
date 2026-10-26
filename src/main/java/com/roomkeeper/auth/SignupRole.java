package com.roomkeeper.auth;

import com.roomkeeper.user.Role;

/** The roles a person can choose at signup. ADMIN isn't one of them. */
public enum SignupRole {

	GUEST(Role.GUEST),
	HOTEL_MANAGER(Role.HOTEL_MANAGER);

	private final Role role;

	SignupRole(Role role) {
		this.role = role;
	}

	public Role role() {
		return role;
	}

}
