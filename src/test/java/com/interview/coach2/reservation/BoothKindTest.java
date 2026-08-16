package com.interview.coach2.reservation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기업 설명회와 면접·상담은 화면이 서로 다른 목록을 그린다. 종류를 잘못 나누면
 * 설명회가 예약 시간표에 섞여 나오거나, 기업 페이지에서 면접이 사라진다.
 */
@SpringBootTest
class BoothKindTest {

	@Autowired AdminController admin;
	@Autowired ReservationController publicApi;
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
}
