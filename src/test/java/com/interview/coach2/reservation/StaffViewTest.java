package com.interview.coach2.reservation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 부스 담당자 화면의 접근 경계를 고정한다.
 *
 * 담당자 토큰은 예약자 연락처를 열어준다. 그 토큰이 공개 응답에 섞이거나,
 * 남의 부스 예약이 함께 나가면 그대로 개인정보 유출이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StaffViewTest {

	@LocalServerPort
	int port;

	@Autowired BoothRepository booths;
	@Autowired ReservationRepository reservations;
	@Autowired VisitorRepository visitors;
	@Autowired ReservationService service;

	private final HttpClient http = HttpClient.newHttpClient();

	private Booth mine;
	private Booth other;

	@BeforeEach
	void setUp() {
		reservations.deleteAll();
		visitors.deleteAll();
		booths.deleteAll();

		mine = booths.save(new Booth("삼성전자", "a1", null,
			Slots.today().plusDays(7), LocalTime.of(10, 0), LocalTime.of(17, 0), 30));
		other = booths.save(new Booth("네이버", "a2", null,
			Slots.today().plusDays(7), LocalTime.of(10, 0), LocalTime.of(17, 0), 30));

		service.book(mine.getId(), Slots.forBooth(mine).get(0), "홍길동", "01011112222");
		service.book(other.getId(), Slots.forBooth(other).get(1), "김철수", "01033334444");
	}

	private HttpResponse<String> get(String path) throws Exception {
		return http.send(
			HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
			HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void 담당자는_자기_부스_예약만_본다() throws Exception {
		HttpResponse<String> res = get("/api/staff/" + mine.getStaffToken());

		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(res.body())
			.contains("\"companyName\":\"삼성전자\"")
			.contains("홍길동")
			.contains("01011112222");

		// 남의 부스 예약자가 섞이면 개인정보 유출이다
		assertThat(res.body()).doesNotContain("김철수").doesNotContain("01033334444");
	}

	@Test
	void 공개_부스_목록은_담당자_토큰을_노출하지_않는다() throws Exception {
		String body = get("/api/booths").body();

		assertThat(body).contains("삼성전자");
		assertThat(body)
			.as("staffToken이 공개되면 누구나 예약자 연락처를 볼 수 있다")
			.doesNotContain(mine.getStaffToken())
			.doesNotContain("staffToken");
	}

	@Test
	void 잘못된_토큰은_404() throws Exception {
		assertThat(get("/api/staff/deadbeef").statusCode()).isEqualTo(404);
	}

	@Test
	void 취소된_예약은_담당자_화면에서_사라진다() throws Exception {
		ReservationService.BookResult booked =
			service.book(mine.getId(), Slots.forBooth(mine).get(2), "이영희", "01055556666");
		assertThat(get("/api/staff/" + mine.getStaffToken()).body()).contains("이영희");

		service.cancel(booked.reservation().getId(), booked.visitorToken());

		assertThat(get("/api/staff/" + mine.getStaffToken()).body()).doesNotContain("이영희");
	}

	@Test
	void 부스마다_토큰이_다르다() {
		assertThat(mine.getStaffToken())
			.isNotBlank()
			.isNotEqualTo(other.getStaffToken());
	}
}
