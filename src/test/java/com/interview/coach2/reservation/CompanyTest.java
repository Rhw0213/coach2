package com.interview.coach2.reservation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 기업과 부스는 회사명 문자열로만 이어져 있다. 그 이음매가 끊기면 소개 페이지에서 면접이
 * 사라지고 예약 화면에는 옛 이름이 남는다 — 여기서 그 이음매만 집중해서 지킨다.
 */
@SpringBootTest
class CompanyTest {

	@Autowired AdminController admin;
	@Autowired ReservationController publicApi;
	@Autowired CompanyRepository companies;
	@Autowired BoothRepository booths;
	@Autowired ReservationRepository reservations;
	@Autowired VisitorRepository visitors;

	@BeforeEach
	void setUp() {
		reservations.deleteAll();
		visitors.deleteAll();
		booths.deleteAll();
		companies.deleteAll();
	}

	private static AdminController.CompanyRequest request(String name) {
		return new AdminController.CompanyRequest(name, "대기업", "보안 전문 기업",
			"네트워크 보안", "2000년 설립", "1,077억", "https://example.com", "각 ㅇ명",
			"개발\n영업", "보안제품 개발", "IT 관련 전공", "컴퓨터공학", "열정인");
	}

	private Booth booth(String companyName, String boothNo, int capacity) {
		return booths.save(new Booth(companyName, boothNo, null,
			Slots.today().plusDays(7), LocalTime.of(10, 0), LocalTime.of(17, 0), 30, capacity));
	}

	@Test
	void 기업_상세는_그_기업의_세션을_모두_보여준다() {
		AdminController.AdminCompanyView created = admin.createCompany(request("시큐아이"));
		booth("시큐아이", "a1", 1);
		booth("시큐아이", "a2", 5);
		booth("다른기업", "b1", 1);

		ReservationController.CompanyDetail detail = publicApi.company(created.id());

		assertThat(detail.name()).isEqualTo("시큐아이");
		assertThat(detail.talent()).isEqualTo("열정인");
		// 1:1과 그룹을 둘 다 여는 것이 이 행사의 기본 형태다.
		assertThat(detail.sessions()).hasSize(2)
			.extracting(ReservationController.BoothView::capacity)
			.containsExactlyInAnyOrder(1, 5);
	}

	@Test
	void 개명하면_그_이름을_쓰던_부스도_따라간다() {
		AdminController.AdminCompanyView created = admin.createCompany(request("세트렉아이"));
		booth("세트렉아이", "a1", 1);
		booth("세트렉아이", "a2", 5);
		booth("남의기업", "b1", 1);

		admin.updateCompany(created.id(), request("쎄트렉아이"));

		assertThat(booths.findAll())
			.extracting(Booth::getCompanyName)
			.containsExactlyInAnyOrder("쎄트렉아이", "쎄트렉아이", "남의기업");
		assertThat(publicApi.company(created.id()).sessions()).hasSize(2);
	}

	@Test
	void 같은_이름의_기업은_두_번_만들어지지_않는다() {
		admin.createCompany(request("시큐아이"));

		assertThatThrownBy(() -> admin.createCompany(request("시큐아이")))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
				.isEqualTo(HttpStatus.CONFLICT));
	}

	@Test
	void 내린_기업은_목록과_상세에서_빠진다() {
		AdminController.AdminCompanyView created = admin.createCompany(request("시큐아이"));

		admin.setCompanyActive(created.id(), false);

		assertThat(publicApi.companies()).isEmpty();
		assertThatThrownBy(() -> publicApi.company(created.id()))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND));
	}

	/** 아직 부스를 안 연 기업도 소개는 보여야 한다 — 보고 나중에 예약하러 오는 흐름이 정상이다. */
	@Test
	void 부스가_없는_기업도_소개는_열린다() {
		AdminController.AdminCompanyView created = admin.createCompany(request("아직없음"));

		assertThat(publicApi.company(created.id()).sessions()).isEmpty();
	}

	/** 빈 칸이 ""로 저장되면 화면이 '값 있음'으로 읽어 제목만 줄줄이 그린다. */
	@Test
	void 빈_칸은_널로_저장된다() {
		AdminController.AdminCompanyView view = admin.createCompany(
			new AdminController.CompanyRequest("빈칸기업", "  ", "", null, null, null, null,
				null, null, null, null, null, null));

		assertThat(view.category()).isNull();
		assertThat(view.summary()).isNull();
	}
}
