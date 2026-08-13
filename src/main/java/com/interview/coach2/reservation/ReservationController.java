package com.interview.coach2.reservation;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 예약자용 공개 API. 로그인이 없다 — 신원은 예약 시 발급되는 불투명 토큰이다.
 * 토큰은 조회·취소에만 쓰이고 아무 권한도 주지 않는다.
 */
@RestController
@RequestMapping("/api")
public class ReservationController {

	private final ReservationService service;
	private final CoachRepository coaches;

	public ReservationController(ReservationService service, CoachRepository coaches) {
		this.service = service;
		this.coaches = coaches;
	}

	public record CoachView(Long id, String name, String title, int slotMinutes) {
	}

	public record BookRequest(Long coachId, Instant startTime, String name, String phone) {
	}

	public record BookResponse(Long reservationId, String token, Instant startTime, int slotMinutes) {
	}

	public record ReservationView(Long id, Long coachId, String coachName,
	                              Instant startTime, int slotMinutes, ReservationStatus status) {
	}

	@GetMapping("/coaches")
	public List<CoachView> coaches() {
		return service.activeCoaches().stream()
			.map(c -> new CoachView(c.getId(), c.getName(), c.getTitle(), c.getSlotMinutes()))
			.toList();
	}

	@GetMapping("/coaches/{coachId}/slots")
	public List<Instant> slots(@PathVariable Long coachId,
	                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		return service.availableSlots(coachId, date);
	}

	@PostMapping("/reservations")
	public BookResponse book(@RequestBody BookRequest request) {
		ReservationService.BookResult result = service.book(
			request.coachId(), request.startTime(), request.name(), request.phone());
		Reservation r = result.reservation();
		return new BookResponse(r.getId(), result.customerToken(), r.getStartTime(), r.getSlotMinutes());
	}

	@GetMapping("/reservations/me/{token}")
	public List<ReservationView> myReservations(@PathVariable String token) {
		return toViews(service.myReservations(token));
	}

	@DeleteMapping("/reservations/{reservationId}")
	public void cancel(@PathVariable Long reservationId, @RequestParam String token) {
		service.cancel(reservationId, token);
	}

	private List<ReservationView> toViews(List<Reservation> list) {
		Map<Long, String> names = coaches.findAllById(
				list.stream().map(Reservation::getCoachId).distinct().toList())
			.stream()
			.collect(Collectors.toMap(Coach::getId, Coach::getName, (a, b) -> a));

		return list.stream()
			.map(r -> new ReservationView(
				r.getId(), r.getCoachId(), names.getOrDefault(r.getCoachId(), "-"),
				r.getStartTime(), r.getSlotMinutes(), r.getStatus()))
			.toList();
	}
}
