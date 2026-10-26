package com.roomkeeper.user;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class UserMapperTest {

	private final UserMapper mapper = Mappers.getMapper(UserMapper.class);

	@Test
	void mapsAUserToItsApiResponse() {
		User user = new User("asha@example.com", "{bcrypt}secret-hash", "Asha Rao", Role.HOTEL_MANAGER,
				Instant.parse("2026-01-15T10:00:00Z"));
		ReflectionTestUtils.setField(user, "id", 42L);

		UserResponse response = mapper.toResponse(user);

		assertThat(response).isEqualTo(new UserResponse(42L, "asha@example.com", "Asha Rao", Role.HOTEL_MANAGER));
	}

}
