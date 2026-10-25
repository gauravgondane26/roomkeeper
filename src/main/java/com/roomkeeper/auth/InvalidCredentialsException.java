package com.roomkeeper.auth;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;

/** Same message for an unknown email and a wrong password, so callers can't tell which. */
public class InvalidCredentialsException extends ApiException {

	public InvalidCredentialsException() {
		super(ProblemType.INVALID_CREDENTIALS, "Email or password is incorrect");
	}

}
