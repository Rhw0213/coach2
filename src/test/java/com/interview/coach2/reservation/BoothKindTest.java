package com.interview.coach2.reservation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 기업 설명회와 면접·상담은 화면이 서로 다른 목록을 그린다. 종류를 잘못 나누면
 * 설명회가 예약 시간표에 섞여 나오거나, 기업 페이지에서 면접이 사라진다.
 */
@SpringBootTest
class BoothKindTest {

	@Autowired AdminController admin;
	@Autowired ReservationController publicApi;
	@Autowired ReservationService service;
	@Autowired BoothRepository booths;
	@Autowired CompanyRepository companies;
	@Autowired ReservationRepository reservations;
	@Autowired VisitorRepository visitors;

	@BeforeEach
	void setUp() {
		reservations.deleteAll();
		visitors.deleteAll();
		booths.deleteAll();
		companies.deleteAll();
	}

	private AdminController.BoothRequest request(String no, BoothKind kind) {
		return new AdminController.BoothRequest("동해기업", no, null,
			Slots.today().plusDays(7), LocalTime.of(10, 0), LocalTime.of(17, 0),
			30, 1, false, kind);
	}

	/** 이 값이 생기기 전에 만들어진 부스는 전부 면접·상담이었다. 안 보내면 그것이어야 한다. */
	@Test
	void 종류를_안_보내면_면접이다() {
		AdminController.AdminBoothView view = admin.createBooth(request("a1", null));

		assertThat(view.kind()).isEqualTo(BoothKind.INTERVIEW);
	}

	@Test
	void 설명회로_만들_수_있다() {
		assertThat(admin.createBooth(request("a2", BoothKind.BRIEFING)).kind())
			.isEqualTo(BoothKind.BRIEFING);
	}

	/** 종류를 모르는 화면이 부스를 수정했다고 해서 설명회가 면접으로 바뀌면 안 된다. */
	@Test
	void 수정_요청에_종류가_없으면_그대로_둔다() {
		Long id = admin.createBooth(request("a3", BoothKind.BRIEFING)).id();

		AdminController.AdminBoothView after = admin.updateBooth(id, request("a3", null));

		assertThat(after.kind()).isEqualTo(BoothKind.BRIEFING);
	}

	/** 기업 페이지의 '면접·상담 예약'에 설명회가 섞이면 학생이 엉뚱한 자리를 잡는다. */
	@Test
	void 기업_상세의_세션에는_설명회가_섞이지_않는다() {
		AdminController.AdminCompanyView company = admin.createCompany(
			new AdminController.CompanyRequest("동해기업", null, null, null, null, null, null,
				null, null, null, null, null, null, null, null, null, null));
		admin.createBooth(request("a1", BoothKind.INTERVIEW));
		admin.createBooth(request("a2", BoothKind.BRIEFING));

		assertThat(publicApi.company(company.id()).sessions())
			.singleElement()
			.satisfies(s -> assertThat(s.boothNo()).isEqualTo("a1"));
	}

	/** 공개 부스 목록은 둘 다 준다 — 화면이 종류를 보고 나눈다. */
	@Test
	void 공개_부스_목록은_종류를_함께_준다() {
		admin.createBooth(request("a1", BoothKind.INTERVIEW));
		admin.createBooth(request("a2", BoothKind.BRIEFING));

		assertThat(publicApi.booths())
			.extracting(ReservationController.BoothView::kind)
			.containsExactlyInAnyOrder(BoothKind.INTERVIEW, BoothKind.BRIEFING);
	}

	// ── 인원 무제한 ──────────────────────────────────────────
	// 설명회는 ZOOM 웨비나라 좌석이 없다. 정원 1로 만들어도 몇 명이든 들어와야 한다.

	private static ReservationService.Applicant who(String name, String phone) {
		return new ReservationService.Applicant(name, phone, "건국대", "컴퓨터공학", "4학년", true);
	}

	@Test
	void 설명회는_정원과_무관하게_계속_신청된다() {
		Booth briefing = booths.findById(
			admin.createBooth(request("b1", BoothKind.BRIEFING)).id()).orElseThrow();
		Instant slot = Slots.forBooth(briefing).get(0);

		for (int i = 1; i <= 7; i++) {
			service.book(briefing.getId(), slot, who("참가자" + i, "0101111000" + i), null);
		}

		assertThat(reservations.countByBoothIdAndStartTimeAndStatus(
			briefing.getId(), slot, ReservationStatus.RESERVED)).isEqualTo(7);
	}

	/** 정원이 있는 부스였다면 첫 한 명에 닫혔을 슬롯이다. 닫히면 뒤에 온 사람이 못 신청한다. */
	@Test
	void 설명회_슬롯은_신청이_들어와도_닫히지_않는다() {
		Booth briefing = booths.findById(
			admin.createBooth(request("b2", BoothKind.BRIEFING)).id()).orElseThrow();
		Instant slot = Slots.forBooth(briefing).get(0);

		service.book(briefing.getId(), slot, who("홍길동", "01011112222"), null);

		assertThat(service.availableSlots(briefing.getId())).contains(slot);
	}

	/** 무제한이어도 한 사람이 같은 설명회를 두 번 차지하지는 못한다 — 사람 쪽 제약은 그대로다. */
	@Test
	void 같은_사람은_같은_설명회를_두_번_신청하지_못한다() {
		Booth briefing = booths.findById(
			admin.createBooth(request("b3", BoothKind.BRIEFING)).id()).orElseThrow();
		List<Instant> slots = Slots.forBooth(briefing);

		service.book(briefing.getId(), slots.get(0), who("홍길동", "01011112222"), null);

		assertThatThrownBy(() ->
			service.book(briefing.getId(), slots.get(1), who("홍길동", "01011112222"), null))
			.isInstanceOf(ResponseStatusException.class);
	}

	/**
	 * 설명회도 누가 신청했는지 남아야 한다. 면접은 되는데 설명회만 빠지면
	 * 기업이 명단 없이 웨비나를 열게 된다.
	 */
	@Test
	void 설명회_신청자도_담당자_화면과_관리자_목록에_나온다() {
		Booth briefing = booths.findById(
			admin.createBooth(request("b4", BoothKind.BRIEFING)).id()).orElseThrow();
		Instant slot = Slots.forBooth(briefing).get(0);

		service.book(briefing.getId(), slot, new ReservationService.Applicant(
			"홍길동", "01011112222", "건국대학교", "컴퓨터공학과", "4학년", true), null);

		assertThat(publicApi.staffView(briefing.getStaffToken()).reservations())
			.singleElement()
			.satisfies(r -> {
				assertThat(r.visitorName()).isEqualTo("홍길동");
				assertThat(r.visitorPhone()).isEqualTo("01011112222");
				assertThat(r.visitorSchool()).isEqualTo("건국대학교");
				assertThat(r.visitorMajor()).isEqualTo("컴퓨터공학과");
				assertThat(r.visitorStanding()).isEqualTo("4학년");
			});

		assertThat(admin.reservationsOn(briefing.getEventDate()))
			.singleElement()
			.satisfies(r -> {
				assertThat(r.boothNo()).isEqualTo("b4");
				assertThat(r.visitorName()).isEqualTo("홍길동");
				assertThat(r.visitorSchool()).isEqualTo("건국대학교");
			});
	}

	/**
	 * 설명회 신청은 좌석을 잡지 않는다(seatNo=0). 그런데 정원 계산이 건수만 세면,
	 * 신청이 남은 채로 부스를 면접으로 되돌리는 순간 실제로는 빈 시각이 영구히 마감된다.
	 * 원인이 DB에 흔적을 남기지 않아 관리자가 왜 안 되는지 알 방법도 없다.
	 */
	@Test
	void 신청이_남은_설명회를_면접으로_되돌려도_그_시각이_마감되지_않는다() {
		Long id = admin.createBooth(request("b5", BoothKind.BRIEFING)).id();
		Booth briefing = booths.findById(id).orElseThrow();
		Instant slot = Slots.forBooth(briefing).get(0);

		for (int i = 1; i <= 3; i++) {
			service.book(id, slot, who("참가자" + i, "0102222000" + i), null);
		}

		// 정원 1짜리 면접으로 되돌린다.
		admin.updateBooth(id, request("b5", BoothKind.INTERVIEW));

		assertThat(service.availableSlots(id)).as("좌석 없는 신청이 슬롯을 막으면 안 된다").contains(slot);
		service.book(id, slot, who("면접자", "01099998888"), null);

		assertThat(reservations.countByBoothIdAndStartTimeAndStatusAndSeatNoGreaterThan(
			id, slot, ReservationStatus.RESERVED, 0)).isEqualTo(1);
	}

	/** 화면이 '인원 제한 없음'을 스스로 계산하지 않도록 서버가 알려준다. */
	@Test
	void 공개_부스_응답이_무제한_여부를_담는다() {
		admin.createBooth(request("a1", BoothKind.INTERVIEW));
		admin.createBooth(request("a2", BoothKind.BRIEFING));

		assertThat(publicApi.booths())
			.filteredOn(b -> b.kind() == BoothKind.BRIEFING)
			.singleElement()
			.satisfies(b -> assertThat(b.unlimited()).isTrue());
		assertThat(publicApi.booths())
			.filteredOn(b -> b.kind() == BoothKind.INTERVIEW)
			.singleElement()
			.satisfies(b -> assertThat(b.unlimited()).isFalse());
	}
}
