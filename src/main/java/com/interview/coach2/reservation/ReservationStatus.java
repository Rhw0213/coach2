package com.interview.coach2.reservation;

public enum ReservationStatus {
	/** 예약 확정. 슬롯을 점유한다. */
	RESERVED,
	/** 취소됨. 슬롯을 놓아준다 — 같은 슬롯을 다시 예약할 수 있다. */
	CANCELLED
}
