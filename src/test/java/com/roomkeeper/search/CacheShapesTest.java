package com.roomkeeper.search;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CacheShapesTest {

	record Room(String type, BigDecimal price) {
	}

	record Info(long id, String name, List<Room> rooms) {
	}

	record SameInfo(long id, String name, List<SameRoom> rooms) {
	}

	record SameRoom(String type, BigDecimal price) {
	}

	record RenamedField(long id, String title, List<Room> rooms) {
	}

	record AddedField(long id, String name, List<Room> rooms, String city) {
	}

	record ChangedType(long id, String name, Room rooms) {
	}

	record RoomWithBeds(String type, BigDecimal price, int beds) {
	}

	record NestedChange(long id, String name, List<RoomWithBeds> rooms) {
	}

	@Test
	void isEightHexDigits() {
		assertThat(CacheShapes.of(Info.class)).matches("[0-9a-f]{8}");
	}

	@Test
	void typesThatSerializeAlikeHaveTheSameShape() {
		assertThat(CacheShapes.of(SameInfo.class)).isEqualTo(CacheShapes.of(Info.class));
	}

	@Test
	void anyChangeToWhatIsStoredChangesTheShape() {
		assertThat(List.of(RenamedField.class, AddedField.class, ChangedType.class, NestedChange.class))
			.extracting(CacheShapes::of)
			.doesNotContain(CacheShapes.of(Info.class))
			.doesNotHaveDuplicates();
	}

	@Test
	void theCachedResponsesHaveShapes() {
		assertThat(CacheShapes.of(HotelInfoResponse.class)).isNotEqualTo(CacheShapes.of(HotelSearchResponse.class));
	}

}
