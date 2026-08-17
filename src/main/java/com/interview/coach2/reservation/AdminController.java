package com.interview.coach2.reservation;

import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
	private final ApprovalRepository approvals;
	private final CompanyRepository companies;
	private final ReservationRepository reservations;

	public AdminController(ReservationService service, BoothRepository booths,
	                       VisitorRepository visitors, ApprovalRepository approvals,
	                       CompanyRepository companies, ReservationRepository reservations) {
		this.service = service;
		this.booths = booths;
		this.visitors = visitors;
		this.approvals = approvals;
		this.companies = companies;
		this.reservations = reservations;
	}

	// @DateTimeFormat은 @RequestBody(JSON) 바인딩에 관여하지 않는다 — Jackson이 ISO-8601을
	// 그대로 읽으므로 필요도 없다. 붙여두면 이 값이 파싱을 좌우한다고 오해하게 되므로 뺀다.
	// capacity는 안 보내면 1이다. 기존 부스 수정 요청이 이 필드를 모른 채 들어와도
	// 1:1로 조용히 바뀌지 않도록, updateBooth에서 null이면 현재 값을 유지한다.
	// approvalRequired도 capacity와 같다 — 안 보내면 지금 값을 유지한다. 이 필드를 모르는
	// 옛 화면이 부스를 수정했다고 해서 합격자 제한이 조용히 풀리면 안 된다.
	public record BoothRequest(String companyName, String boothNo, String note,
	                           LocalDate eventDate, LocalTime openFrom, LocalTime openTo,
	                           Integer slotMinutes, Integer capacity, Boolean approvalRequired,
	                           BoothKind kind) {
	}

	public record AdminBoothView(Long id, String companyName, String boothNo, String note,
	                             LocalDate eventDate, LocalTime openFrom, LocalTime openTo,
	                             int slotMinutes, int capacity, boolean active,
	                             boolean approvalRequired, BoothKind kind,
	                             long approvedCount, String staffToken, String applyToken) {
	}

	/**
	 * 하루 목록에는 1:1 면접·그룹 면접·기업 설명회가 시간 순으로 뒤섞여 들어온다.
	 * kind·capacity가 없으면 표가 그것을 구분해 적을 수 없다 — 실제로 못 적고 있었다.
	 */
	public record AdminReservationView(Long id, Long boothId, String companyName, String boothNo,
	                                   BoothKind kind, int capacity,
	                                   Instant startTime, int slotMinutes,
	                                   String visitorName, String visitorPhone,
	                                   String visitorSchool, String visitorMajor,
	                                   String visitorStanding) {
	}

	@GetMapping("/booths")
	@Transactional
	public List<AdminBoothView> listBooths() {
		List<Booth> all = booths.findAll();
		// 담당자·신청 링크 기능이 생기기 전에 만들어진 부스에는 토큰이 없다.
		// 주최측이 링크를 보러 오는 이 시점에 채운다(더티 체킹으로 저장된다).
		all.forEach(Booth::ensureTokens);
		return all.stream().map(this::toView).toList();
	}

	@PostMapping("/booths")
	@ResponseStatus(HttpStatus.CREATED)
	public AdminBoothView createBooth(@RequestBody BoothRequest request) {
		Booth booth = new Booth(request.companyName(), request.boothNo(), request.note(),
			required(request.eventDate(), "행사일"),
			required(request.openFrom(), "운영 시작"),
			required(request.openTo(), "운영 종료"),
			required(request.slotMinutes(), "슬롯 길이"),
			request.capacity() == null ? 1 : request.capacity());
		booth.setApprovalRequired(Boolean.TRUE.equals(request.approvalRequired()));
		// 안 보내면 면접·상담이다 — 이 필드를 모르는 화면이 부스를 만들어도 설명회가 되지 않는다.
		booth.setKind(request.kind() == null ? BoothKind.INTERVIEW : request.kind());
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
			required(request.slotMinutes(), "슬롯 길이"),
			request.capacity() == null ? booth.getCapacity() : request.capacity());
		if (request.approvalRequired() != null) {
			booth.setApprovalRequired(request.approvalRequired());
		}
		if (request.kind() != null) {
			booth.setKind(request.kind());
		}
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

	// ── 참여 기업 · 채용정보(JD) ──────────────────────────────────

	public record CompanyRequest(String name, String category, String summary, String about,
	                             String industry, String founded, String revenue, String employees,
	                             String homepage, String headcount, String hiring, String roles,
	                             String duties, String requirements, String majors, String benefits,
	                             String talent) {
	}

	public record AdminCompanyView(Long id, String name, String category, String summary,
	                               String about, String industry, String founded, String revenue,
	                               String employees, String homepage, String headcount,
	                               String hiring, String roles, String duties, String requirements,
	                               String majors, String benefits, String talent,
	                               boolean active, int boothCount) {
	}

	@GetMapping("/companies")
	public List<AdminCompanyView> listCompanies() {
		Map<String, Long> boothsByName = booths.findAll().stream()
			.collect(Collectors.groupingBy(Booth::getCompanyName, Collectors.counting()));
		return companies.findAll(Sort.by("name")).stream()
			.map(c -> toView(c, boothsByName.getOrDefault(c.getName(), 0L).intValue()))
			.toList();
	}

	@PostMapping("/companies")
	@ResponseStatus(HttpStatus.CREATED)
	public AdminCompanyView createCompany(@RequestBody CompanyRequest request) {
		if (request.name() == null || request.name().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "회사명이 필요합니다");
		}
		if (companies.findByName(request.name().trim()).isPresent()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "같은 이름의 기업이 이미 있습니다");
		}
		Company company = new Company(request.name());
		apply(company, request);
		return toView(companies.save(company), 0);
	}

	/**
	 * 이름이 바뀌면 그 이름을 쓰던 부스도 함께 갱신한다. 기업과 부스는 회사명 문자열로만
	 * 이어져 있어서, 여기서 끊으면 그 기업의 면접이 소개 페이지에서 사라지고
	 * 예약 화면에는 옛 이름이 남는다.
	 */
	@PatchMapping("/companies/{companyId}")
	@Transactional
	public AdminCompanyView updateCompany(@PathVariable Long companyId,
	                                      @RequestBody CompanyRequest request) {
		Company company = companies.findById(companyId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "기업을 찾을 수 없습니다"));

		if (request.name() != null && !request.name().isBlank()
				&& !request.name().trim().equals(company.getName())) {
			companies.findByName(request.name().trim()).ifPresent(other -> {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "같은 이름의 기업이 이미 있습니다");
			});
			String previous = company.rename(request.name());
			booths.findAll().stream()
				.filter(b -> previous.equals(b.getCompanyName()))
				.forEach(b -> b.updateInfo(company.getName(), b.getBoothNo(), b.getNote()));
		}
		apply(company, request);
		return toView(company, boothCount(company));
	}

	/** 내리면 소개 페이지에서 사라진다. 부스는 따로 관리한다 — 예약을 조용히 막지 않는다. */
	@PatchMapping("/companies/{companyId}/active")
	@Transactional
	public AdminCompanyView setCompanyActive(@PathVariable Long companyId,
	                                         @RequestParam boolean active) {
		Company company = companies.findById(companyId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "기업을 찾을 수 없습니다"));
		company.setActive(active);
		return toView(company, boothCount(company));
	}

	private static void apply(Company company, CompanyRequest r) {
		company.updateProfile(new Company.Profile(r.category(), r.summary(), r.about(),
			r.industry(), r.founded(), r.revenue(), r.employees(), r.homepage(), r.headcount(),
			r.hiring(), r.roles(), r.duties(), r.requirements(), r.majors(), r.benefits(),
			r.talent()));
	}

	private int boothCount(Company company) {
		return (int) booths.findAll().stream()
			.filter(b -> company.getName().equals(b.getCompanyName())).count();
	}

	private static AdminCompanyView toView(Company c, int boothCount) {
		return new AdminCompanyView(c.getId(), c.getName(), c.getCategory(), c.getSummary(),
			c.getAbout(), c.getIndustry(), c.getFounded(), c.getRevenue(), c.getEmployees(),
			c.getHomepage(), c.getHeadcount(), c.getHiring(), c.getRoles(), c.getDuties(),
			c.getRequirements(), c.getMajors(), c.getBenefits(), c.getTalent(),
			c.isActive(), boothCount);
	}

	// ── 서류 합격자 ──────────────────────────────────────────────
	// 기업이 심사한 명단을 주최측이 대신 등록한다. 부스 담당자 토큰은 읽기 전용 그대로 두었다 —
	// 여기에 쓰기를 열면 그 링크 하나가 남의 기업 명단까지 건드릴 수 있는지 따로 검사해야 한다.

	public record ApprovalRequest(String text) {
	}

	public record ApprovalView(Long id, String name, String phone, String token, Instant createdAt) {
	}

	/** 붙여넣은 명단을 읽은 결과. 몇 줄이 들어갔고 무엇이 걸렀는지 화면이 그대로 보여준다. */
	public record ApprovalImport(int added, List<String> duplicated, List<String> unreadable,
	                             List<ApprovalView> approvals) {
	}

	@GetMapping("/booths/{boothId}/approvals")
	public List<ApprovalView> listApprovals(@PathVariable Long boothId) {
		return approvals.findByBoothIdOrderByNameAsc(boothId).stream()
			.map(AdminController::toView).toList();
	}

	/**
	 * 엑셀에서 복사한 명단을 그대로 받는다. 한 줄에 이름과 번호가 있으면 된다.
	 *
	 * 이미 있는 번호는 건너뛰고 토큰을 새로 발급하지 않는다. 다시 등록했다고 링크가 바뀌면
	 * 이미 문자로 보낸 링크가 죽는다 — 명단을 두 번 붙여넣는 것은 흔한 일이다.
	 */
	@PostMapping("/booths/{boothId}/approvals")
	@Transactional
	public ApprovalImport addApprovals(@PathVariable Long boothId,
	                                   @RequestBody ApprovalRequest request) {
		Booth booth = booths.findById(boothId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "부스를 찾을 수 없습니다"));

		List<String> duplicated = new ArrayList<>();
		List<String> unreadable = new ArrayList<>();
		// 붙여넣은 명단 안에서의 중복도 잡는다. DB 조회에만 기대면 같은 트랜잭션에서 방금 넣은
		// 행이 보이느냐에 결과가 달라지고, 안 보이면 유니크 제약에 걸려 통째로 실패한다.
		Set<String> seen = new HashSet<>();
		int added = 0;

		for (ApprovalLines.Row row : ApprovalLines.parse(request.text())) {
			if (!row.ok()) {
				unreadable.add(row.raw());
				continue;
			}
			if (!seen.add(row.phone())
					|| approvals.findByBoothIdAndPhone(booth.getId(), row.phone()).isPresent()) {
				duplicated.add(row.raw());
				continue;
			}
			approvals.save(new Approval(booth.getId(), row.name(), row.phone()));
			added++;
		}

		return new ApprovalImport(added, duplicated, unreadable,
			approvals.findByBoothIdOrderByNameAsc(booth.getId()).stream()
				.map(AdminController::toView).toList());
	}

	/** 잘못 넣은 사람을 뺀다. 이미 예약했다면 그 예약은 남는다 — 확정된 약속을 조용히 지우지 않는다. */
	@DeleteMapping("/approvals/{approvalId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void removeApproval(@PathVariable Long approvalId) {
		approvals.deleteById(approvalId);
	}

	private static ApprovalView toView(Approval a) {
		return new ApprovalView(a.getId(), a.getName(), a.getPhone(), a.getToken(), a.getCreatedAt());
	}

	public record DeletedBooth(Long id, String companyName, String boothNo,
	                          int reservations, int approvals) {
	}

	/**
	 * 부스를 지운다. 시험용으로 만든 부스를 치우거나 잘못 만든 것을 되돌릴 때 쓴다.
	 *
	 * 내린 부스만 지울 수 있다. 한 번에 지우게 두면 오타 하나로 예약이 잡혀 있는 부스가
	 * 통째로 사라진다 — 내리는 순간 공개 화면에서 빠지므로, 그 사이에 무엇이 없어지는지
	 * 확인할 수 있다.
	 *
	 * 딸린 예약과 합격자 명단도 함께 지운다. 부스가 없어진 뒤에 남은 예약은 어느 기업의
	 * 무엇이었는지 알 수 없는 행이고, 합격자 명단은 그 부스를 가리키는 자격이라 의미가 없다.
	 * 무엇이 몇 건 사라졌는지 응답에 담는다 — 조용히 지우지 않는다.
	 */
	@DeleteMapping("/booths/{boothId}")
	@Transactional
	public DeletedBooth deleteBooth(@PathVariable Long boothId) {
		Booth booth = booths.findById(boothId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "부스를 찾을 수 없습니다"));
		if (booth.isActive()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
				"운영 중인 부스는 지울 수 없습니다. 먼저 부스를 내려 주세요");
		}

		List<Reservation> attached = reservations.findByBoothId(boothId);
		List<Approval> approved = approvals.findByBoothIdOrderByNameAsc(boothId);
		reservations.deleteAll(attached);
		approvals.deleteAll(approved);
		booths.delete(booth);

		return new DeletedBooth(boothId, booth.getCompanyName(), booth.getBoothNo(),
			attached.size(), approved.size());
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
				booth == null ? null : booth.getKind(),
				booth == null ? 0 : booth.getCapacity(),
				r.getStartTime(), r.getSlotMinutes(),
				visitor == null ? "-" : visitor.getName(),
				visitor == null ? "-" : visitor.getPhone(),
				visitor == null ? null : visitor.getSchool(),
				visitor == null ? null : visitor.getMajor(),
				visitor == null ? null : visitor.getStanding());
		}).toList();
	}

	private AdminBoothView toView(Booth b) {
		return new AdminBoothView(b.getId(), b.getCompanyName(), b.getBoothNo(), b.getNote(),
			b.getEventDate(), b.getOpenFrom(), b.getOpenTo(), b.getSlotMinutes(), b.getCapacity(),
			b.isActive(), b.isApprovalRequired(), b.getKind(),
			approvals.countByBoothId(b.getId()), b.getStaffToken(), b.getApplyToken());
	}

	private static <T> T required(T value, String field) {
		if (value == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + "이(가) 필요합니다");
		}
		return value;
	}
}
