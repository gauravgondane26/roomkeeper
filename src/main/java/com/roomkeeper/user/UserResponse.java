package com.roomkeeper.user;

/** What the API shows about a user. Never includes the password hash. */
public record UserResponse(Long id, String email, String name, Role role) {
}
