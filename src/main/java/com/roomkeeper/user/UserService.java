package com.roomkeeper.user;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

	private final UserRepository users;

	private final UserMapper mapper;

	public UserService(UserRepository users, UserMapper mapper) {
		this.users = users;
		this.mapper = mapper;
	}

	@Transactional(readOnly = true)
	public UserResponse get(long userId) {
		return mapper.toResponse(find(userId));
	}

	@Transactional
	public UserResponse updateProfile(long userId, UpdateProfileRequest request) {
		User user = find(userId);
		if (request.name() != null) {
			user.changeName(request.name().strip());
		}
		return mapper.toResponse(user);
	}

	private User find(long userId) {
		// A valid token for a user that no longer exists
		return users.findById(userId)
			.orElseThrow(() -> new ApiException(ProblemType.NOT_FOUND, "User not found"));
	}

}
