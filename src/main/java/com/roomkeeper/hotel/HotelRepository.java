package com.roomkeeper.hotel;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HotelRepository extends JpaRepository<Hotel, Long> {

	List<Hotel> findByOwnerIdOrderById(Long ownerId);

	/** Hotels on sale. */
	List<Hotel> findByActiveTrueOrderById();

	/** The review queue for a status, oldest first. */
	List<Hotel> findByApprovalStatusOrderByCreatedAtAscIdAsc(ApprovalStatus status);

}
