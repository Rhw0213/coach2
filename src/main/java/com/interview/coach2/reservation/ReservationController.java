package com.interview.coach2.reservation;

import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 방문자용 공개 API. 로그인이 없다 — 본인 확인은 예약할 때 적은 이름 + 연락처다.
 * 토큰은 그 확인을 통과한 화면이 쥐는 손잡이일 뿐 조회·취소 외에 아무 권한도 주지 않는다.
 */
@RestController
@RequestMapping("/api")
public class ReservationController {

	private final ReservationService service;
	private final BoothRepository booths;
	private final VisitorRepository visitors;

	public ReservationController(ReservationService service, BoothRepository booths,
	                             VisitorRepository visitors) {
		this.service = service;
		this.booths = booths;
		this.visitors = visitors;
	}

	/**
	 * 운영시간·행사일을 함께 내려준다. 예약 화면이 "마감된 시간"을 빈 칸으로 그리려면
	 * 가용 슬롯 목록만으로는 부족하고 하루 전체의 틀을 알아야 한다.
	 * 예약 페이지에 공개되는 정보이므로 숨길 이유도 없다.
	 */
	public record BoothView(Long id, String companyName, String boothNo, String note,
	                        LocalDate eventDate, LocalTime openFrom, LocalTime openTo,
	                        int slotMinutes, int capacity) {
	}

	public record BookRequest(Long boothId, Instant startTime, String name, String phone) {
	}

	public record BookResponse(Long reservationId, String token, Instant startTime,
	                           int slotMinutes, String companyName, String boothNo) {
	}

	public record ReservationView(Long id, Long boothId, String companyName, String boothNo,
	                              Instant startTime, int slotMinutes, ReservationStatus status) {
	}

	@GetMapping("/booths")
	public List<BoothView> booths() {
		return service.activeBooths().stream().map(ReservationController::toView).toList();
	}

	@GetMapping("/booths/{boothId}/slots")
	public List<Instant> slots(@PathVariable Long boothId) {
		return service.availableSlots(boothId);
	}

	/**
	 * /slots와 같은 슬롯 목록에 남은 자리 수를 얹은 것. 예약 화면이 쓰는 /slots는 그대로 둔다 —
	 * 응답 모양을 바꾸면 행사 열흘 전에 예약 경로까지 건드리게 된다.
	 *
	 * 안내 페이지의 시간표가 이걸 쓴다. '자리가 있나'만으로는 정원이 여럿인 부스에서
	 * 예약이 들어와도 화면이 그대로여서, 방문자가 신청이 반영됐는지 알 수 없다.
	 */
	@GetMapping("/booths/{boothId}/availability")
	public List<ReservationService.SlotSeats> availability(@PathVariable Long boothId) {
		return service.openSlots(boothId);
	}

	@PostMapping("/reservations")
	public BookResponse book(@RequestBody BookRequest request) {
		ReservationService.BookResult result = service.book(
			request.boothId(), request.startTime(), request.name(), request.phone());
		Reservation r = result.reservation();
		Booth booth = service.booth(r.getBoothId());
		return new BookResponse(r.getId(), result.visitorToken(), r.getStartTime(),
			r.getSlotMinutes(), booth.getCompanyName(), booth.getBoothNo());
	}

	@GetMapping("/reservations/me/{token}")
	public List<ReservationView> myReservations(@PathVariable String token) {
		return toViews(service.myReservations(token));
	}

	public record LookupRequest(String name, String phone) {
	}

	/**
	 * 토큰을 함께 내려준다. 취소는 여전히 토큰으로 하므로, 조회한 화면이 그것을 들고 있으면
	 * 취소 경로를 그대로 쓸 수 있다. 이름·연락처를 맞힌 사람에게만 나가므로 권한은 같다.
	 */
	public record LookupResponse(String token, List<ReservationView> reservations) {
	}

	/**
	 * 이름 + 연락처로 내 예약을 찾는다.
	 *
	 * GET이 아니라 POST다 — 연락처가 경로에 실리면 nginx 액세스 로그와 브라우저 방문기록에
	 * 평문으로 남고, 외부 링크를 타면 Referer로도 새어 나간다.
	 */
	@PostMapping("/reservations/lookup")
	public LookupResponse lookup(@RequestBody LookupRequest request) {
		Visitor visitor = service.visitorByNameAndPhone(request.name(), request.phone());
		return new LookupResponse(visitor.getToken(),
			toViews(service.reservationsOf(visitor.getId())));
	}

	/** 예약에 부스 정보를 붙인다. 부스가 지워졌더라도 목록이 통째로 사라지지는 않게 한다. */
	private List<ReservationView> toViews(List<Reservation> list) {
		Map<Long, Booth> byId = booths.findAllById(
				list.stream().map(Reservation::getBoothId).distinct().toList())
			.stream().collect(Collectors.toMap(Booth::getId, b -> b, (a, b) -> a));

		return list.stream().map(r -> {
			Booth booth = byId.get(r.getBoothId());
			return new ReservationView(r.getId(), r.getBoothId(),
				booth == null ? "-" : booth.getCompanyName(),
				booth == null ? "-" : booth.getBoothNo(),
				r.getStartTime(), r.getSlotMinutes(), r.getStatus());
		}).toList();
	}

	@DeleteMapping("/reservations/{reservationId}")
	public void cancel(@PathVariable Long reservationId, @RequestParam String token) {
		service.cancel(reservationId, token);
	}

	// ── 부스 담당자 ──────────────────────────────────────────────
	// 읽기 전용이다. 토큰은 자기 부스의 예약을 보여줄 뿐 아무것도 바꾸지 못한다.
	// 예약자 연락처가 실리므로 토큰이 곧 접근 권한이다 — 공개 목록에는 나가지 않는다.

	public record StaffReservation(Instant startTime, int slotMinutes,
	                               String visitorName, String visitorPhone) {
	}

	public record StaffView(String companyName, String boothNo, String note,
	                        LocalDate eventDate, LocalTime openFrom, LocalTime openTo,
	                        int slotMinutes, int capacity, boolean active,
	                        List<StaffReservation> reservations) {
	}

	@GetMapping("/staff/{staffToken}")
	public StaffView staffView(@PathVariable String staffToken) {
		Booth booth = service.boothByStaffToken(staffToken);
		List<Reservation> list = service.reservationsFor(booth.getId());

		Map<Long, Visitor> visitorById = visitors.findAllById(
				list.stream().map(Reservation::getVisitorId).distinct().toList())
			.stream().collect(Collectors.toMap(Visitor::getId, v -> v, (a, b) -> a));

		List<StaffReservation> rows = list.stream().map(r -> {
			Visitor v = visitorById.get(r.getVisitorId());
			return new StaffReservation(r.getStartTime(), r.getSlotMinutes(),
				v == null ? "-" : v.getName(), v == null ? "-" : v.getPhone());
		}).toList();

		return new StaffView(booth.getCompanyName(), booth.getBoothNo(), booth.getNote(),
			booth.getEventDate(), booth.getOpenFrom(), booth.getOpenTo(),
			booth.getSlotMinutes(), booth.getCapacity(), booth.isActive(), rows);
	}

	static BoothView toView(Booth b) {
		return new BoothView(b.getId(), b.getCompanyName(), b.getBoothNo(), b.getNote(),
			b.getEventDate(), b.getOpenFrom(), b.getOpenTo(), b.getSlotMinutes(), b.getCapacity());
	}
}
