package com.roomkeeper.booking;

import java.time.Clock;
import java.util.List;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Looking up the guests a booking names: one query however many there are. */
class GuestServiceTest {

	private final GuestRepository repository = mock(GuestRepository.class);

	private final GuestService service = new GuestService(repository, mock(GuestMapper.class), Clock.systemUTC());

	@Test
	void findsEveryGuestInOneQueryInTheOrderAsked() {
		Guest asha = saved(1L, "Asha");
		Guest vikram = saved(2L, "Vikram");
		List<Long> ids = List.of(2L, 1L);
		when(repository.findByIdInAndUserIdAndRemovedAtIsNull(ids, 7L)).thenReturn(List.of(asha, vikram));

		assertThat(service.findAllOwned(7L, ids)).containsExactly(vikram, asha);

		verify(repository).findByIdInAndUserIdAndRemovedAtIsNull(ids, 7L);
		verifyNoMoreInteractions(repository);
	}

	@Test
	void namesTheFirstGuestThatIsntTheUsers() {
		// 3 is someone else's or was removed, and so is 4
		List<Long> ids = List.of(1L, 3L, 4L);
		when(repository.findByIdInAndUserIdAndRemovedAtIsNull(ids, 7L)).thenReturn(List.of(saved(1L, "Asha")));

		assertThatThrownBy(() -> service.findAllOwned(7L, ids)).isInstanceOfSatisfying(ApiException.class, ex -> {
			assertThat(ex.type()).isEqualTo(ProblemType.NOT_FOUND);
			assertThat(ex.getMessage()).isEqualTo("Guest 3 not found");
		});
	}

	private static Guest saved(long id, String name) {
		Guest guest = new Guest(7L, name, 30, Gender.OTHER);
		ReflectionTestUtils.setField(guest, "id", id);
		return guest;
	}

}
