package com.interview.coach2.reservation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

	List<Reservation> findByBoothIdAndStatus(Long boothId, ReservationStatus status);

	// Between은 양끝을 포함한다. 하루 범위를 [자정, 다음날 자정)으로 잡아야 하므로
	// 끝은 LessThan을 쓴다 — 이름은 길지만 경계에서 틀리지 않는다.
	List<Reservation> findByStatusAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTime(
		ReservationStatus status, Instant from, Instant toExclusive);

	List<Reservation> findByVisitorIdOrderByStartTimeDesc(Long visitorId);

	boolean existsByVisitorIdAndStartTimeAndStatus(
		Long visitorId, Instant startTime, ReservationStatus status);

	// 정원이 1명이 아닐 수 있으므로 '있냐'가 아니라 '몇 명이냐'를 묻는다.
	long countByBoothIdAndStartTimeAndStatus(
		Long boothId, Instant startTime, ReservationStatus status);

	boolean existsByVisitorIdAndBoothIdAndStatus(
		Long visitorId, Long boothId, ReservationStatus status);
}
