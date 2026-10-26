package com.roomkeeper.inventory;

import java.time.LocalDate;
import java.util.List;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;
import org.springframework.http.ProblemDetail;

/** A new total is lower than the rooms already booked or held on some nights; {@code nights} lists them. */
public class TotalBelowSoldException extends ApiException {

	private final List<LocalDate> nights;

	public TotalBelowSoldException(int totalCount, List<LocalDate> nights) {
		super(ProblemType.TOTAL_BELOW_SOLD, (nights.size() == 1 ? "1 night already has" : nights.size()
				+ " nights already have") + " more than " + totalCount + " rooms booked or held");
		this.nights = List.copyOf(nights);
	}

	@Override
	public ProblemDetail toProblemDetail() {
		ProblemDetail problem = super.toProblemDetail();
		problem.setProperty("nights", nights);
		return problem;
	}

}
