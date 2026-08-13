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
import java.time.Instant;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정적 HTML이 실제로 읽는 필드를 고정한다.
 *
 * index.html은 부스의 openFrom/openTo/slotMinutes/eventDate로 하루 전체의 시간 레일을
 * 그린다(마감된 시간을 빈 칸으로 남기려면 가용 슬롯 목록만으로는 부족하다).
 * 이 필드가 응답에서 빠지면 화면은 조용히 비어버리고 서버 테스트는 전부 통과한다.
 * 그 간극을 여기서 막는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicApiContractTest {

	@LocalServerPort
	int port;

	@Autowired BoothRepository booths;
	@Autowired ReservationRepository reservations;
	@Autowired VisitorRepository visitors;

	private final HttpClient http = HttpClient.newHttpClient();

	@BeforeEach
	void setUp() {
		reservations.deleteAll();
		visitors.deleteAll();
		booths.deleteAll();
	}

	private HttpResponse<String> get(String path) throws Exception {
		return http.send(
			HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
			HttpResponse.BodyHandlers.ofString());
	}

	private Booth saveBooth() {
		return booths.save(new Booth("동해기업", "A-12", "백엔드 개발자 모집",
			Slots.today().plusDays(7), LocalTime.of(10, 0), LocalTime.of(17, 0), 30));
	}

	@Test
	void 공개_부스_응답이_화면이_읽는_필드를_모두_담는다() throws Exception {
		Booth booth = saveBooth();

		HttpResponse<String> res = get("/api/booths");

		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(res.body())
			.contains("\"companyName\":\"동해기업\"")
			.contains("\"boothNo\":\"A-12\"")
			.contains("\"slotMinutes\":30")
			.contains("\"openFrom\":\"10:00:00\"")
			.contains("\"openTo\":\"17:00:00\"")
			.contains("\"eventDate\":\"" + booth.getEventDate() + "\"");
	}

	@Test
	void 예약_응답은_현장에서_찾아갈_정보를_담는다() throws Exception {
		Booth booth = saveBooth();
		Instant slot = Slots.forBooth(booth).get(0);

		HttpResponse<String> res = http.send(
			HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/reservations"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("""
					{"boothId":%d,"startTime":"%s","name":"홍길동","phone":"010-1111-2222"}
					""".formatted(booth.getId(), slot)))
				.build(),
			HttpResponse.BodyHandlers.ofString());

		// 예약 확인 화면이 부스번호를 보여주지 못하면 방문자가 현장에서 찾아갈 수 없다.
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(res.body())
			.contains("\"boothNo\":\"A-12\"")
			.contains("\"companyName\":\"동해기업\"")
			.contains("\"token\":");
	}

	@Test
	void 공개_응답은_예약자_개인정보를_담지_않는다() throws Exception {
		Booth booth = saveBooth();
		Visitor visitor = visitors.save(new Visitor("홍길동", "01011112222"));
		reservations.save(new Reservation(booth.getId(), visitor.getId(),
			Slots.forBooth(booth).get(0), 30));

		String slots = get("/api/booths/" + booth.getId() + "/slots").body();

		assertThat(slots).doesNotContain("홍길동").doesNotContain("01011112222");
	}

	@Test
	void 관리자_API는_시크릿_없이_열리지_않는다() throws Exception {
		assertThat(get("/api/admin/booths").statusCode()).isEqualTo(401);
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
