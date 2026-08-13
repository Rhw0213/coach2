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
 * 에러 응답의 '모양'을 고정한다.
 *
 * 화면은 응답 본문의 detail 필드를 읽어 사용자에게 보여준다. 서버가 아무리 친절한 사유를
 * 정해도 그게 본문에 실리지 않으면 화면에는 "요청에 실패했습니다 (409)"만 남는다.
 * 서버 테스트가 상태코드만 보면 이 간극을 절대 못 잡는다 — 실제로 못 잡아서 운영에
 * 그대로 나갔다. 여기서는 본문을 직접 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorShapeTest {

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

	private HttpResponse<String> post(String path, String json) throws Exception {
		return http.send(
			HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json))
				.build(),
			HttpResponse.BodyHandlers.ofString());
	}

	private Booth saveBooth() {
		return booths.save(new Booth("동해기업", "A-12", null,
			Slots.today().plusDays(7), LocalTime.of(10, 0), LocalTime.of(17, 0), 30));
	}

	@Test
	void 없는_부스를_예약하면_사유가_본문에_담긴다() throws Exception {
		HttpResponse<String> res = post("/api/reservations",
			"""
			{"boothId":999999,"startTime":"2099-01-01T01:00:00Z","name":"홍길동","phone":"01011112222"}
			""");

		assertThat(res.statusCode()).isEqualTo(404);
		assertThat(res.body()).contains("\"detail\":\"부스를 찾을 수 없습니다\"");
	}

	@Test
	void 슬롯이_아닌_시각은_사유가_본문에_담긴다() throws Exception {
		Booth booth = saveBooth();
		String offGrid = Slots.forBooth(booth).get(0).plusSeconds(600).toString();

		HttpResponse<String> res = post("/api/reservations",
			"""
			{"boothId":%d,"startTime":"%s","name":"홍길동","phone":"01011112222"}
			""".formatted(booth.getId(), offGrid));

		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(res.body()).contains("\"detail\":\"예약할 수 없는 시간입니다\"");
	}

	@Test
	void 중복_예약_409에도_사유가_담긴다() throws Exception {
		Booth booth = saveBooth();
		String slot = Slots.forBooth(booth).get(0).toString();
		String body = """
			{"boothId":%d,"startTime":"%s","name":"%s","phone":"%s"}
			""";

		assertThat(post("/api/reservations", body.formatted(booth.getId(), slot, "홍길동", "01011112222"))
			.statusCode()).isEqualTo(200);

		HttpResponse<String> second =
			post("/api/reservations", body.formatted(booth.getId(), slot, "김철수", "01033334444"));

		assertThat(second.statusCode()).isEqualTo(409);
		assertThat(second.body()).contains("\"detail\":\"이미 예약된 시간입니다\"");
	}

	@Test
	void 엔티티_검증_실패도_사유가_담긴다() throws Exception {
		// Booth 생성자가 던지는 IllegalArgumentException 경로
		HttpResponse<String> res = http.send(
			HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/admin/booths"))
				.header("Content-Type", "application/json")
				.header("X-Admin-Secret", "test-admin-secret")
				.POST(HttpRequest.BodyPublishers.ofString("""
					{"companyName":"","boothNo":"A-1","eventDate":"2099-01-01",
					 "openFrom":"10:00:00","openTo":"17:00:00","slotMinutes":30}
					"""))
				.build(),
			HttpResponse.BodyHandlers.ofString());

		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(res.body()).contains("\"detail\":\"회사명은 비어 있을 수 없다\"");
	}
}
