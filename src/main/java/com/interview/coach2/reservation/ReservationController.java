package com.interview.coach2.reservation;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
	private final CompanyRepository companies;

	public ReservationController(ReservationService service, BoothRepository booths,
	                             VisitorRepository visitors, CompanyRepository companies) {
		this.service = service;
		this.booths = booths;
		this.visitors = visitors;
		this.companies = companies;
	}

	/**
	 * 운영시간·행사일을 함께 내려준다. 예약 화면이 "마감된 시간"을 빈 칸으로 그리려면
	 * 가용 슬롯 목록만으로는 부족하고 하루 전체의 틀을 알아야 한다.
	 * 예약 페이지에 공개되는 정보이므로 숨길 이유도 없다.
	 */
	public record BoothView(Long id, String companyName, String boothNo, String note,
	                        LocalDate eventDate, LocalTime openFrom, LocalTime openTo,
	                        int slotMinutes, int capacity, boolean approvalRequired, BoothKind kind,
	                        boolean unlimited) {
	}

	/**
	 * approvalToken은 합격자 한 사람을 가리키는 개별 링크에서만 온다. 없으면 합격자 전용
	 * 부스는 이름을 명단과 대조해 가려낸다. 어느 쪽이든 이름·연락처는 명단의 값이 쓰이고,
	 * 학교·전공·학년은 명단에 없으므로 신청서에서 받는다.
	 *
	 * agreed는 개인정보 수집·이용 동의다. 화면의 required만으로는 막을 수 없으므로
	 * 서버가 다시 본다.
	 *
	 * boolean이 아니라 Boolean이다. Jackson 3(부트 4)은 FAIL_ON_NULL_FOR_PRIMITIVES가
	 * 기본으로 켜져 있어, 이 필드를 빼고 보내면 역직렬화가 통째로 실패한다 —
	 * 그러면 "동의해 주세요" 대신 사유 없는 400이 나가고 화면은 이유를 말하지 못한다.
	 */
	public record BookRequest(Long boothId, Instant startTime, String name, String phone,
	                          String school, String major, String standing, Boolean agreed,
	                          String approvalToken) {
	}

	public record BookResponse(Long reservationId, String token, Instant startTime,
	                           int slotMinutes, String companyName, String boothNo) {
	}

	/**
	 * kind·capacity는 '무엇을 잡은 자리인가'에 답한다. 부스번호와 기업명만으로는
	 * 기업 설명회인지 1:1 면접인지 그룹 면접인지 구분되지 않아, 내 예약 화면이
	 * 무엇을 신청했는지 말해주지 못했다.
	 */
	public record ReservationView(Long id, Long boothId, String companyName, String boothNo,
	                              BoothKind kind, int capacity,
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

	// ── 참여 기업 · 채용정보(JD) ──────────────────────────────────

	/** 목록 카드용. 긴 JD 항목은 싣지 않는다 — 목록에서 읽지도 않는데 응답만 몇 배가 된다. */
	public record CompanyCard(Long id, String name, String category, String summary) {
	}

	/** 기업 상세. sessions는 그 기업이 연 면접·상담이며, 예약 화면으로 넘어가는 통로다. */
	public record CompanyDetail(Long id, String name, String category, String summary, String about,
	                            String industry, String founded, String revenue, String employees,
	                            String homepage, String headcount, String hiring, String roles,
	                            String duties, String requirements, String majors, String benefits,
	                            String talent, List<BoothView> sessions) {
	}

	@GetMapping("/companies")
	public List<CompanyCard> companies() {
		return companies.findByActiveTrueOrderByNameAsc().stream()
			.map(c -> new CompanyCard(c.getId(), c.getName(), c.getCategory(), c.getSummary()))
			.toList();
	}

	@GetMapping("/companies/{companyId}")
	public CompanyDetail company(@PathVariable Long companyId) {
		Company c = companies.findById(companyId)
			.filter(Company::isActive)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "기업을 찾을 수 없습니다"));

		// 부스와는 회사명으로 이어져 있다. 아직 부스를 열지 않은 기업이면 빈 목록이 나간다 —
		// 소개만 보고 나중에 예약하러 오는 것도 정상적인 흐름이다.
		//
		// 기업 설명회는 여기 싣지 않는다. 시간표가 따로 있고, 이 자리는 '이 기업의 면접·상담을
		// 언제 잡을 수 있나'에 답하는 곳이다.
		List<BoothView> sessions = service.activeBooths().stream()
			.filter(b -> c.getName().equals(b.getCompanyName()))
			.filter(b -> b.getKind() == BoothKind.INTERVIEW)
			.map(ReservationController::toView)
			.toList();

		return new CompanyDetail(c.getId(), c.getName(), c.getCategory(), c.getSummary(),
			c.getAbout(), c.getIndustry(), c.getFounded(), c.getRevenue(), c.getEmployees(),
			c.getHomepage(), c.getHeadcount(), c.getHiring(), c.getRoles(), c.getDuties(),
			c.getRequirements(), c.getMajors(), c.getBenefits(), c.getTalent(), sessions);
	}

	@PostMapping("/reservations")
	public BookResponse book(@RequestBody BookRequest request) {
		ReservationService.BookResult result = service.book(
			request.boothId(), request.startTime(),
			new ReservationService.Applicant(request.name(), request.phone(), request.school(),
				request.major(), request.standing(), Boolean.TRUE.equals(request.agreed())),
			request.approvalToken());
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
				booth == null ? null : booth.getKind(),
				booth == null ? 0 : booth.getCapacity(),
				r.getStartTime(), r.getSlotMinutes(), r.getStatus());
		}).toList();
	}

	/** 개별 안내 링크가 가리키는 것. 화면이 '누구로', '어느 기업 면접을' 잡는지 보여주는 데 쓴다. */
	public record ApprovalView(String name, String phoneMasked, Long boothId,
	                           String companyName, String boothNo) {
	}

	/**
	 * 링크는 문자로 돌아다니다 남의 손에 들어갈 수 있다. 그래서 연락처는 가려서 내려준다 —
	 * 본인은 앞뒤 몇 자리로 자기 번호를 알아보고, 주운 사람은 온전한 번호를 얻지 못한다.
	 */
	/**
	 * 신청 링크가 가리키는 부스. 화면이 그 부스 하나만 보여주는 데 쓴다.
	 *
	 * 예약을 여는 열쇠는 아니다 — 합격자 전용 부스는 링크가 있든 없든 이름을 명단과
	 * 대조해 가려낸다. 이 링크는 '어느 기업인지'까지 데려다주는 지름길일 뿐이다.
	 *
	 * 부스가 무엇인지만 알려준다 — 합격자 명단은 절대 내보내지 않는다. 링크 하나로 명단을
	 * 열 수 있으면 그 링크를 받은 사람 전원이 서로의 이름과 번호를 갖게 된다.
	 */
	public record ApplyView(Long boothId, String companyName, String boothNo, String note) {
	}

	@GetMapping("/apply/{applyToken}")
	public ApplyView apply(@PathVariable String applyToken) {
		Booth booth = service.boothByApplyToken(applyToken);
		return new ApplyView(booth.getId(), booth.getCompanyName(), booth.getBoothNo(),
			booth.getNote());
	}

	@GetMapping("/approvals/{token}")
	public ApprovalView approval(@PathVariable String token) {
		Approval approval = service.approvalByToken(token);
		Booth booth = service.booth(approval.getBoothId());
		return new ApprovalView(approval.getName(), PhoneNumbers.mask(approval.getPhone()),
			booth.getId(), booth.getCompanyName(), booth.getBoothNo());
	}

	@DeleteMapping("/reservations/{reservationId}")
	public void cancel(@PathVariable Long reservationId, @RequestParam String token) {
		service.cancel(reservationId, token);
	}

	// ── 부스 담당자 ──────────────────────────────────────────────
	// 읽기 전용이다. 토큰은 자기 부스의 예약을 보여줄 뿐 아무것도 바꾸지 못한다.
	// 예약자 연락처가 실리므로 토큰이 곧 접근 권한이다 — 공개 목록에는 나가지 않는다.

	/** bookedAt은 신청이 들어온 시각이다 — 담당자가 선착순을 확인할 때 이것 말고는 근거가 없다. */
	public record StaffReservation(Instant startTime, int slotMinutes,
	                               String visitorName, String visitorPhone,
	                               String visitorSchool, String visitorMajor,
	                               String visitorStanding, Instant bookedAt) {
	}

	/**
	 * kind는 이 명단이 무엇의 신청자인가를 말한다. 토큰 하나가 부스 하나를 가리키므로
	 * 여기 실린 사람들은 전부 같은 종류다 — 그래서 사람마다가 아니라 화면 제목에 적는다.
	 * unlimited로 대신 판단하지 않는다. 그건 '좌석이 있나'지 '무엇을 하는 자리인가'가 아니다.
	 */
	public record StaffView(String companyName, String boothNo, String note,
	                        LocalDate eventDate, LocalTime openFrom, LocalTime openTo,
	                        int slotMinutes, int capacity, BoothKind kind,
	                        boolean active, boolean unlimited,
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
				v == null ? "-" : v.getName(), v == null ? "-" : v.getPhone(),
				v == null ? null : v.getSchool(), v == null ? null : v.getMajor(),
				v == null ? null : v.getStanding(), r.getCreatedAt());
		}).toList();

		return new StaffView(booth.getCompanyName(), booth.getBoothNo(), booth.getNote(),
			booth.getEventDate(), booth.getOpenFrom(), booth.getOpenTo(),
			booth.getSlotMinutes(), booth.getCapacity(), booth.getKind(),
			booth.isActive(), booth.isUnlimited(), rows);
	}

	// unlimited는 kind로도 알 수 있지만 화면이 다시 계산하게 두지 않는다 — '설명회는 무제한'은
	// 서버가 정하는 규칙이고, 그 규칙이 바뀔 때 고칠 곳이 한 군데여야 한다.
	static BoothView toView(Booth b) {
		return new BoothView(b.getId(), b.getCompanyName(), b.getBoothNo(), b.getNote(),
			b.getEventDate(), b.getOpenFrom(), b.getOpenTo(), b.getSlotMinutes(), b.getCapacity(),
			b.isApprovalRequired(), b.getKind(), b.isUnlimited());
	}
}
