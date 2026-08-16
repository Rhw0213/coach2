package com.interview.coach2.reservation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

	List<Reservation> findByBoothIdAndStatus(Long boothId, ReservationStatus status);

	/** 부스를 지울 때 딸린 예약을 걷어내는 용도. 상태를 가리지 않는다. */
	List<Reservation> findByBoothId(Long boothId);

	// Between은 양끝을 포함한다. 하루 범위를 [자정, 다음날 자정)으로 잡아야 하므로
	// 끝은 LessThan을 쓴다 — 이름은 길지만 경계에서 틀리지 않는다.
	List<Reservation> findByStatusAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTime(
		ReservationStatus status, Instant from, Instant toExclusive);

	List<Reservation> findByVisitorIdOrderByStartTimeDesc(Long visitorId);

	boolean existsByVisitorIdAndStartTimeAndStatus(
		Long visitorId, Instant startTime, ReservationStatus status);

	/** 그 슬롯에 걸린 예약 건수. 좌석을 차지했는지는 가리지 않는다. */
	long countByBoothIdAndStartTimeAndStatus(
		Long boothId, Instant startTime, ReservationStatus status);

	/**
	 * 그 슬롯에서 실제로 좌석을 차지한 건수. 정원이 1명이 아닐 수 있으므로 '있냐'가 아니라
	 * '몇 명이냐'를 묻는다.
	 *
	 * seatNo > 0 인 것만 센다. 인원 제한이 없는 기업 설명회 신청은 seatNo=0(슬롯키 NULL)이라
	 * 좌석을 점유하지 않는데, 그것까지 세면 설명회를 면접으로 바꾸는 순간 신청 건수가
	 * 정원을 넘겨 실제로는 빈 시각이 영구히 마감된다.
	 */
	long countByBoothIdAndStartTimeAndStatusAndSeatNoGreaterThan(
		Long boothId, Instant startTime, ReservationStatus status, int seatNoExclusive);

	boolean existsByVisitorIdAndBoothIdAndStatus(
		Long visitorId, Long boothId, ReservationStatus status);
}
