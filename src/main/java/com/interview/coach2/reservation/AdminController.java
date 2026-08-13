package com.interview.coach2.reservation;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 관리자 API. 인증은 {@link AdminAuthInterceptor}가 /api/admin/** 전체에 걸어둔다.
 * 여기 응답에는 예약자 이름·연락처가 들어가므로 공개 뷰와 분리한다.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

	private final ReservationService service;
	private final BoothRepository booths;
	private final VisitorRepository visitors;

	public AdminController(ReservationService service, BoothRepository booths,
	                       VisitorRepository visitors) {
		this.service = service;
		this.booths = booths;
		this.visitors = visitors;
	}

	// @DateTimeFormat은 @RequestBody(JSON) 바인딩에 관여하지 않는다 — Jackson이 ISO-8601을
	// 그대로 읽으므로 필요도 없다. 붙여두면 이 값이 파싱을 좌우한다고 오해하게 되므로 뺀다.
	public record BoothRequest(String companyName, String boothNo, String note,
	                           LocalDate eventDate, LocalTime openFrom, LocalTime openTo,
	                           Integer slotMinutes) {
	}

	public record AdminBoothView(Long id, String companyName, String boothNo, String note,
	                             LocalDate eventDate, LocalTime openFrom, LocalTime openTo,
	                             int slotMinutes, boolean active) {
	}

	public record AdminReservationView(Long id, Long boothId, String companyName, String boothNo,
	                                   Instant startTime, int slotMinutes,
	                                   String visitorName, String visitorPhone) {
	}

	@GetMapping("/booths")
	public List<AdminBoothView> listBooths() {
		return booths.findAll().stream().map(AdminController::toView).toList();
	}

	@PostMapping("/booths")
	@ResponseStatus(HttpStatus.CREATED)
	public AdminBoothView createBooth(@RequestBody BoothRequest request) {
		Booth booth = new Booth(request.companyName(), request.boothNo(), request.note(),
			required(request.eventDate(), "행사일"),
			required(request.openFrom(), "운영 시작"),
			required(request.openTo(), "운영 종료"),
			required(request.slotMinutes(), "슬롯 길이"));
		return toView(booths.save(booth));
	}

	@PatchMapping("/booths/{boothId}")
	@Transactional
	public AdminBoothView updateBooth(@PathVariable Long boothId, @RequestBody BoothRequest request) {
		Booth booth = booths.findById(boothId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "부스를 찾을 수 없습니다"));
		booth.updateInfo(request.companyName(), request.boothNo(), request.note());
		booth.updateSchedule(
			required(request.eventDate(), "행사일"),
			required(request.openFrom(), "운영 시작"),
			required(request.openTo(), "운영 종료"),
			required(request.slotMinutes(), "슬롯 길이"));
		return toView(booth);
	}

	@PatchMapping("/booths/{boothId}/active")
	@Transactional
	public AdminBoothView setActive(@PathVariable Long boothId, @RequestParam boolean active) {
		Booth booth = booths.findById(boothId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "부스를 찾을 수 없습니다"));
		// 중지해도 이미 잡힌 예약은 그대로 둔다. 새 예약만 막힌다.
		if (active) {
			booth.activate();
		} else {
			booth.deactivate();
		}
		return toView(booth);
	}

	@GetMapping("/reservations")
	public List<AdminReservationView> reservationsOn(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		List<Reservation> list = service.reservationsOn(date);

		Map<Long, Booth> boothById = booths.findAllById(
				list.stream().map(Reservation::getBoothId).distinct().toList())
			.stream().collect(Collectors.toMap(Booth::getId, b -> b, (a, b) -> a));
		Map<Long, Visitor> visitorById = visitors.findAllById(
				list.stream().map(Reservation::getVisitorId).distinct().toList())
			.stream().collect(Collectors.toMap(Visitor::getId, v -> v, (a, b) -> a));

		return list.stream().map(r -> {
			Booth booth = boothById.get(r.getBoothId());
			Visitor visitor = visitorById.get(r.getVisitorId());
			return new AdminReservationView(
				r.getId(), r.getBoothId(),
				booth == null ? "-" : booth.getCompanyName(),
				booth == null ? "-" : booth.getBoothNo(),
				r.getStartTime(), r.getSlotMinutes(),
				visitor == null ? "-" : visitor.getName(),
				visitor == null ? "-" : visitor.getPhone());
		}).toList();
	}

	private static AdminBoothView toView(Booth b) {
		return new AdminBoothView(b.getId(), b.getCompanyName(), b.getBoothNo(), b.getNote(),
			b.getEventDate(), b.getOpenFrom(), b.getOpenTo(), b.getSlotMinutes(), b.isActive());
	}

	private static <T> T required(T value, String field) {
		if (value == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + "이(가) 필요합니다");
		}
		return value;
	}
}
