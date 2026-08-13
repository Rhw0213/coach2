package com.interview.coach2.reservation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

	// Between은 양끝을 포함한다. 하루 범위를 [자정, 다음날 자정)으로 잡아야 하므로
	// 끝은 LessThan을 쓴다 — 이름은 길지만 경계에서 틀리지 않는다.
	List<Reservation> findByCoachIdAndStatusAndStartTimeGreaterThanEqualAndStartTimeLessThan(
		Long coachId, ReservationStatus status, Instant from, Instant toExclusive);

	List<Reservation> findByStatusAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTime(
		ReservationStatus status, Instant from, Instant toExclusive);

	List<Reservation> findByCustomerIdOrderByStartTimeDesc(Long customerId);

	boolean existsByCustomerIdAndStartTimeAndStatus(
		Long customerId, Instant startTime, ReservationStatus status);

	boolean existsByCoachIdAndStartTimeAndStatus(
		Long coachId, Instant startTime, ReservationStatus status);
}
