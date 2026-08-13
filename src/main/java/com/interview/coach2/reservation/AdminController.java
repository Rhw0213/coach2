package com.interview.coach2.reservation;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 관리자 API. 인증은 {@link AdminAuthInterceptor}가 /api/admin/** 전체에 걸어둔다.
 * 여기 응답에는 예약자 이름·연락처가 들어가므로 공개 뷰와 분리한다.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

	private final ReservationService service;
	private final CoachRepository coaches;
	private final CustomerRepository customers;

	public AdminController(ReservationService service, CoachRepository coaches,
	                       CustomerRepository customers) {
		this.service = service;
		this.coaches = coaches;
		this.customers = customers;
	}

	public record CoachRequest(String name, String title,
	                           @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime availableFrom,
	                           @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime availableTo,
	                           Integer slotMinutes, List<String> availableDays) {
	}

	public record CoachView(Long id, String name, String title, LocalTime availableFrom,
	                        LocalTime availableTo, int slotMinutes, List<String> availableDays,
	                        boolean active) {
	}

	public record AdminReservationView(Long id, Long coachId, String coachName, Instant startTime,
	                                   int slotMinutes, String customerName, String customerPhone) {
	}

	@GetMapping("/coaches")
	public List<CoachView> listCoaches() {
		return coaches.findAll().stream().map(AdminController::toView).toList();
	}

	@PostMapping("/coaches")
	@ResponseStatus(HttpStatus.CREATED)
	public CoachView createCoach(@RequestBody CoachRequest request) {
		Coach coach = new Coach(request.name(), request.title(),
			required(request.availableFrom(), "availableFrom"),
			required(request.availableTo(), "availableTo"),
			required(request.slotMinutes(), "slotMinutes"),
			parseDays(request.availableDays()));
		return toView(coaches.save(coach));
	}

	@PatchMapping("/coaches/{coachId}")
	@Transactional
	public CoachView updateCoach(@PathVariable Long coachId, @RequestBody CoachRequest request) {
		Coach coach = coaches.findById(coachId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "코치를 찾을 수 없습니다"));
		coach.updateSchedule(
			required(request.availableFrom(), "availableFrom"),
			required(request.availableTo(), "availableTo"),
			required(request.slotMinutes(), "slotMinutes"),
			parseDays(request.availableDays()));
		return toView(coach);
	}

	@PatchMapping("/coaches/{coachId}/active")
	@Transactional
	public CoachView setActive(@PathVariable Long coachId, @RequestParam boolean active) {
		Coach coach = coaches.findById(coachId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "코치를 찾을 수 없습니다"));
		// 비활성화해도 이미 잡힌 예약은 그대로 둔다. 새 예약만 막힌다.
		if (active) {
			coach.activate();
		} else {
			coach.deactivate();
		}
		return toView(coach);
	}

	@GetMapping("/reservations")
	public List<AdminReservationView> reservationsOn(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		List<Reservation> list = service.reservationsOn(date);

		Map<Long, Coach> coachById = coaches.findAllById(
				list.stream().map(Reservation::getCoachId).distinct().toList())
			.stream().collect(Collectors.toMap(Coach::getId, c -> c, (a, b) -> a));
		Map<Long, Customer> customerById = customers.findAllById(
				list.stream().map(Reservation::getCustomerId).distinct().toList())
			.stream().collect(Collectors.toMap(Customer::getId, c -> c, (a, b) -> a));

		return list.stream().map(r -> {
			Coach coach = coachById.get(r.getCoachId());
			Customer customer = customerById.get(r.getCustomerId());
			return new AdminReservationView(
				r.getId(), r.getCoachId(), coach == null ? "-" : coach.getName(),
				r.getStartTime(), r.getSlotMinutes(),
				customer == null ? "-" : customer.getName(),
				customer == null ? "-" : customer.getPhone());
		}).toList();
	}

	private static CoachView toView(Coach c) {
		return new CoachView(c.getId(), c.getName(), c.getTitle(), c.getAvailableFrom(),
			c.getAvailableTo(), c.getSlotMinutes(),
			c.availableDaySet().stream().map(Enum::name).toList(), c.isActive());
	}

	private static Set<DayOfWeek> parseDays(List<String> days) {
		if (days == null || days.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "availableDays는 하루 이상이어야 합니다");
		}
		try {
			return days.stream().map(d -> DayOfWeek.valueOf(d.trim().toUpperCase(Locale.ROOT)))
				.collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
		} catch (IllegalArgumentException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
				"availableDays는 MONDAY..SUNDAY 여야 합니다");
		}
	}

	private static <T> T required(T value, String field) {
		if (value == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + "이(가) 필요합니다");
		}
		return value;
	}
}
