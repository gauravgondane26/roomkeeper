package com.roomkeeper.hotel;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HotelRepository extends JpaRepository<Hotel, Long> {

	List<Hotel> findByOwnerIdOrderById(Long ownerId);

	/** Hotels on sale. */
	List<Hotel> findByActiveTrueOrderById();

	/** One page of the hotels in a review status, in the page's order. */
	Page<Hotel> findByApprovalStatus(ApprovalStatus status, Pageable pageable);

}
