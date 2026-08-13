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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정적 HTML이 실제로 읽는 필드를 고정한다.
 *
 * index.html은 코치의 availableFrom/availableTo/availableDays/slotMinutes로 하루 전체의
 * 시간 레일을 그린다(마감된 시간을 빈 칸으로 남기려면 가용 슬롯 목록만으로는 부족하다).
 * 이 필드가 응답에서 빠지면 화면은 조용히 비어버리고 서버 테스트는 전부 통과한다.
 * 그 간극을 여기서 막는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicApiContractTest {

	@LocalServerPort
	int port;

	@Autowired CoachRepository coaches;
	@Autowired ReservationRepository reservations;
	@Autowired CustomerRepository customers;

	private final HttpClient http = HttpClient.newHttpClient();

	@BeforeEach
	void setUp() {
		reservations.deleteAll();
		customers.deleteAll();
		coaches.deleteAll();
	}

	private HttpResponse<String> get(String path) throws Exception {
		return http.send(
			HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
			HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void 공개_코치_응답이_화면이_읽는_필드를_모두_담는다() throws Exception {
		coaches.save(new Coach("김코치", "커리어 코치",
			java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0), 60,
			java.util.EnumSet.of(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY)));

		HttpResponse<String> res = get("/api/coaches");

		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(res.body())
			.contains("\"slotMinutes\":60")
			.contains("\"availableFrom\":\"09:00:00\"")
			.contains("\"availableTo\":\"18:00:00\"")
			.contains("\"availableDays\":[\"MONDAY\",\"TUESDAY\"]");
	}

	@Test
	void 공개_응답은_예약자_개인정보를_담지_않는다() throws Exception {
		Coach coach = coaches.save(new Coach("김코치", null,
			java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0), 60,
			java.util.EnumSet.allOf(java.time.DayOfWeek.class)));
		Customer customer = customers.save(new Customer("홍길동", "01011112222"));
		reservations.save(new Reservation(coach.getId(), customer.getId(),
			java.time.Instant.now().plusSeconds(86_400), 60));

		String slots = get("/api/coaches/" + coach.getId() + "/slots?date=" + Slots.today().plusDays(1))
			.body();

		assertThat(slots).doesNotContain("홍길동").doesNotContain("01011112222");
	}

	@Test
	void 관리자_API는_시크릿_없이_열리지_않는다() throws Exception {
		assertThat(get("/api/admin/coaches").statusCode()).isEqualTo(401);
		assertThat(get("/api/admin/reservations?date=" + Slots.today()).statusCode()).isEqualTo(401);
	}

	@Test
	void 정적_화면이_서비스된다() throws Exception {
		// 셋 다 실제로 서빙되지 않으면 배포는 성공해도 사용자는 아무것도 못 본다.
		for (String page : new String[]{"/index.html", "/my.html", "/admin.html", "/app.css", "/app.js"}) {
			assertThat(get(page).statusCode()).as(page).isEqualTo(200);
		}
	}
}
