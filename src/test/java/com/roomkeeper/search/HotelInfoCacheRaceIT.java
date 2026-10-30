package com.roomkeeper.search;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.roomkeeper.testsupport.AbstractIntegrationTest;
import com.roomkeeper.testsupport.RaceTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static com.roomkeeper.testsupport.HotelBuilder.aHotel;
import static com.roomkeeper.testsupport.RoomBuilder.aRoom;
import static com.roomkeeper.testsupport.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * A guest's read of hotel info can load the old details just before a manager's change
 * and reach the cache just after the change evicted it. That read mustn't leave the old
 * details cached for the next ten minutes. The read is held between loading and caching
 * while the change commits.
 */
class HotelInfoCacheRaceIT extends AbstractIntegrationTest {

	@MockitoSpyBean
	HotelInfoMapper mapper;

	@Autowired
	StringRedisTemplate redis;

	private String manager;

	private long hotelId;

	@BeforeEach
	void openAHotel() {
		manager = signUpAndLogIn(aUser().asHotelManager().build());
		hotelId = activeHotel(manager, aHotel().named("Seaside Inn").build(), aRoom().build());
	}

	@RaceTest
	void aReadThatLoadedTheOldDetailsCantCacheThemAfterTheChange() throws Exception {
		var loaded = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var first = new AtomicBoolean(true);
		doAnswer(invocation -> {
			Object info = invocation.callRealMethod();
			if (first.getAndSet(false)) {
				loaded.countDown();
				release.await(10, TimeUnit.SECONDS);
			}
			return info;
		}).when(mapper).toInfo(any(), any());

		try (var pool = Executors.newSingleThreadExecutor()) {
			Future<String> slowRead = pool.submit(() -> info());
			assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();

			api(manager).contentType(ContentType.JSON).body(aHotel().named("Seaside Resort").build())
				.when().put("/api/v1/admin/hotels/{id}", hotelId)
				.then().statusCode(200);
			release.countDown();

			assertThat(slowRead.get(10, TimeUnit.SECONDS)).as("it started before the change").isEqualTo("Seaside Inn");
		}
		// Its cache write lands after it answered
		await().atMost(Duration.ofSeconds(5)).until(this::cachedInfoKeys, keys -> !keys.isEmpty());

		assertThat(info()).isEqualTo("Seaside Resort");
	}

	private String info() {
		return api().when().get("/api/v1/hotels/{id}/info", hotelId).then().statusCode(200).extract().path("name");
	}

	private Set<String> cachedInfoKeys() {
		return redis.keys("hotel:*:" + hotelId + ":v*");
	}

}
