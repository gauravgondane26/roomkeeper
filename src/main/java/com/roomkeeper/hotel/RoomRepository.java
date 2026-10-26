package com.roomkeeper.hotel;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RoomRepository extends JpaRepository<Room, Long> {

	List<Room> findByHotelIdOrderById(Long hotelId);

	Optional<Room> findByIdAndHotelId(Long id, Long hotelId);

	boolean existsByHotelIdAndTypeIgnoreCase(Long hotelId, String type);

	boolean existsByHotelIdAndTypeIgnoreCaseAndIdNot(Long hotelId, String type, Long id);

	// SQL rather than the booking module's repository: bookings depend on hotels, not the reverse

	@Query(value = "select exists (select 1 from bookings where room_id = :roomId)", nativeQuery = true)
	boolean hasBookings(long roomId);

	@Query(value = """
			select exists (select 1 from bookings b join rooms r on r.id = b.room_id where r.hotel_id = :hotelId)""",
			nativeQuery = true)
	boolean hotelHasBookings(long hotelId);

}
