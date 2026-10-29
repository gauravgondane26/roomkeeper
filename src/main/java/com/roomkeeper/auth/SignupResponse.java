package com.roomkeeper.auth;

/** Signup's answer, the same for every request that passes validation. */
public record SignupResponse(String message) {

	static final SignupResponse ACCEPTED = new SignupResponse(
			"Thanks. If this address can be used, you can log in now; we've emailed you.");

}
