package com.roomkeeper.user;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

	private final UserRepository users;

	public UserService(UserRepository users) {
		this.users = users;
	}

	@Transactional(readOnly = true)
	public User get(long userId) {
		return find(userId);
	}

	@Transactional
	public User updateProfile(long userId, UpdateProfileRequest request) {
		User user = find(userId);
		if (request.name() != null) {
			user.changeName(request.name().strip());
		}
		return user;
	}

	private User find(long userId) {
		// A valid token for a user that no longer exists
		return users.findById(userId)
			.orElseThrow(() -> new ApiException(ProblemType.NOT_FOUND, "User not found"));
	}

}
