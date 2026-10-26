package com.roomkeeper.hotel;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRepository extends JpaRepository<Room, Long> {

	List<Room> findByHotelIdOrderById(Long hotelId);

	Optional<Room> findByIdAndHotelId(Long id, Long hotelId);

	boolean existsByHotelIdAndTypeIgnoreCase(Long hotelId, String type);

	boolean existsByHotelIdAndTypeIgnoreCaseAndIdNot(Long hotelId, String type, Long id);

}
