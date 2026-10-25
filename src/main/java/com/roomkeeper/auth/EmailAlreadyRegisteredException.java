package com.roomkeeper.auth;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;

public class EmailAlreadyRegisteredException extends ApiException {

	public EmailAlreadyRegisteredException() {
		super(ProblemType.EMAIL_ALREADY_REGISTERED, "An account with this email already exists");
	}

}
