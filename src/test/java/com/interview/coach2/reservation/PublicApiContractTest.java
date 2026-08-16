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
	@Autowired ApprovalRepository approvals;

	private final HttpClient http = HttpClient.newHttpClient();

	@BeforeEach
	void setUp() {
		reservations.deleteAll();
		visitors.deleteAll();
		approvals.deleteAll();
		booths.deleteAll();
	}

	private HttpResponse<String> get(String path) throws Exception {
		return http.send(
			HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
			HttpResponse.BodyHandlers.ofString());
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
					{"boothId":%d,"startTime":"%s","name":"홍길동","phone":"010-1111-2222",
					 "school":"건국대","major":"컴퓨터공학","standing":"4학년","agreed":true}
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

	/**
	 * 체크박스는 화면의 required만으로 지켜지지 않는다 — curl 한 줄이면 그대로 넘어온다.
	 * 동의 없이 들어온 요청이 통과하면 개인정보를 받을 근거 없이 저장하게 된다.
	 */
	@Test
	void 개인정보_동의_없이는_예약되지_않는다() throws Exception {
		Booth booth = saveBooth();

		HttpResponse<String> res = post("/api/reservations", """
			{"boothId":%d,"startTime":"%s","name":"홍길동","phone":"010-1111-2222",
			 "school":"건국대","major":"컴퓨터공학","standing":"4학년","agreed":false}
			""".formatted(booth.getId(), Slots.forBooth(booth).get(0)));

		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(reservations.count()).isZero();
		// 동의 전에는 사람도 만들지 않는다. 이름·연락처가 남으면 저장한 것이 없다고 할 수 없다.
		assertThat(visitors.count()).isZero();
	}

	@Test
	void 학교_전공_학년이_없으면_예약되지_않는다() throws Exception {
		Booth booth = saveBooth();

		HttpResponse<String> res = post("/api/reservations", """
			{"boothId":%d,"startTime":"%s","name":"홍길동","phone":"010-1111-2222","agreed":true}
			""".formatted(booth.getId(), Slots.forBooth(booth).get(0)));

		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(reservations.count()).isZero();
	}

	/** 기업 담당자가 명단에서 읽는 값이다. 받아만 두고 안 내려주면 받은 의미가 없다. */
	@Test
	void 담당자_화면이_학교_전공_학년을_받는다() throws Exception {
		Booth booth = saveBooth();
		post("/api/reservations", """
			{"boothId":%d,"startTime":"%s","name":"홍길동","phone":"010-1111-2222",
			 "school":"건국대","major":"컴퓨터공학","standing":"4학년","agreed":true}
			""".formatted(booth.getId(), Slots.forBooth(booth).get(0)));

		HttpResponse<String> res = get("/api/staff/" + booth.getStaffToken());

		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(res.body())
			.contains("\"visitorSchool\":\"건국대\"")
			.contains("\"visitorMajor\":\"컴퓨터공학\"")
			.contains("\"visitorStanding\":\"4학년\"");
	}

	@Test
	void 공개_응답은_예약자_개인정보를_담지_않는다() throws Exception {
		Booth booth = saveBooth();
		Visitor visitor = visitors.save(new Visitor("홍길동", "01011112222"));
		reservations.save(new Reservation(booth.getId(), visitor.getId(),
			Slots.forBooth(booth).get(0), 30, 1));

		String slots = get("/api/booths/" + booth.getId() + "/slots").body();

		assertThat(slots).doesNotContain("홍길동").doesNotContain("01011112222");
	}

	@Test
	void 관리자_API는_시크릿_없이_열리지_않는다() throws Exception {
		assertThat(get("/api/admin/booths").statusCode()).isEqualTo(401);
		assertThat(get("/api/admin/reservations?date=" + Slots.today()).statusCode()).isEqualTo(401);
	}

	/** 안내 페이지의 시간표가 읽는 필드다. 빠지면 화면은 조용히 뼈대로 물러나고 서버는 초록불이다. */
	@Test
	void 남은_자리_응답이_시각과_자리수를_담는다() throws Exception {
		Booth group = booths.save(new Booth("동해기업", "A-12", null,
			Slots.today().plusDays(7), LocalTime.of(10, 0), LocalTime.of(17, 0), 30, 5));
		Instant slot = Slots.forBooth(group).get(0);
		post("/api/reservations", """
			{"boothId":%d,"startTime":"%s","name":"홍길동","phone":"010-1111-2222",
					 "school":"건국대","major":"컴퓨터공학","standing":"4학년","agreed":true}
			""".formatted(group.getId(), slot));

		HttpResponse<String> res = get("/api/booths/" + group.getId() + "/availability");

		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(res.body())
			.contains("\"startTime\":\"" + slot + "\"")
			.contains("\"remaining\":4")
			.contains("\"capacity\":5");
	}

	@Test
	void 이름과_연락처로_예약을_조회한다() throws Exception {
		Booth booth = saveBooth();
		post("/api/reservations", """
			{"boothId":%d,"startTime":"%s","name":"홍길동","phone":"010-1111-2222",
					 "school":"건국대","major":"컴퓨터공학","standing":"4학년","agreed":true}
			""".formatted(booth.getId(), Slots.forBooth(booth).get(0)));

		HttpResponse<String> res = post("/api/reservations/lookup", """
			{"name":"홍길동","phone":"01011112222"}
			""");

		// my.html이 읽는 두 가지 — 취소에 쓸 토큰과, 목록에 그릴 부스 정보.
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(res.body())
			.contains("\"token\":")
			.contains("\"companyName\":\"동해기업\"")
			.contains("\"boothNo\":\"A-12\"");
	}

	/** 연락처만으로는 열리지 않아야 한다 — 번호는 비밀이 아니다. */
	@Test
	void 연락처가_맞아도_이름이_다르면_열리지_않는다() throws Exception {
		Booth booth = saveBooth();
		post("/api/reservations", """
			{"boothId":%d,"startTime":"%s","name":"홍길동","phone":"010-1111-2222",
					 "school":"건국대","major":"컴퓨터공학","standing":"4학년","agreed":true}
			""".formatted(booth.getId(), Slots.forBooth(booth).get(0)));

		HttpResponse<String> res = post("/api/reservations/lookup", """
			{"name":"김철수","phone":"010-1111-2222"}
			""");

		assertThat(res.statusCode()).isEqualTo(404);
		assertThat(res.body()).doesNotContain("token");
	}

	/** 링크가 문자로 돌아다니다 남의 손에 들어가도 온전한 연락처를 넘겨주면 안 된다. */
	@Test
	void 합격자_링크_조회는_연락처를_가려서_준다() throws Exception {
		Booth gated = saveBooth();
		gated.setApprovalRequired(true);
		booths.save(gated);
		Approval approved = approvals.save(new Approval(gated.getId(), "홍길동", "01012345678"));

		HttpResponse<String> res = get("/api/approvals/" + approved.getToken());

		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(res.body())
			.contains("\"name\":\"홍길동\"")
			.contains("\"companyName\":\"동해기업\"")
			.contains("010****5678")
			.doesNotContain("01012345678");
	}

	@Test
	void 없는_링크는_404다() throws Exception {
		assertThat(get("/api/approvals/아무거나").statusCode()).isEqualTo(404);
	}

	/** 공개 부스 목록이 이 값을 안 주면 예약 화면이 어느 부스가 합격자 전용인지 모른다. */
	@Test
	void 공개_부스_응답이_합격자_전용_여부를_담는다() throws Exception {
		Booth gated = saveBooth();
		gated.setApprovalRequired(true);
		booths.save(gated);

		assertThat(get("/api/booths").body()).contains("\"approvalRequired\":true");
	}

	@Test
	void 합격자_전용_부스는_링크_없이_예약되지_않는다() throws Exception {
		Booth gated = saveBooth();
		gated.setApprovalRequired(true);
		booths.save(gated);

		HttpResponse<String> res = post("/api/reservations", """
			{"boothId":%d,"startTime":"%s","name":"홍길동","phone":"010-1111-2222",
					 "school":"건국대","major":"컴퓨터공학","standing":"4학년","agreed":true}
			""".formatted(gated.getId(), Slots.forBooth(gated).get(0)));

		assertThat(res.statusCode()).isEqualTo(403);
	}

	@Test
	void 정적_화면이_서비스된다() throws Exception {
		// 셋 다 실제로 서빙되지 않으면 배포는 성공해도 사용자는 아무것도 못 본다.
		for (String page : new String[]{
				"/index.html", "/about.html", "/company.html", "/briefing.html", "/docs.html",
				"/book.html", "/my.html", "/admin.html", "/booth.html",
				"/app.css", "/nav.css", "/app.js", "/favicon.svg"}) {
			assertThat(get(page).statusCode()).as(page).isEqualTo(200);
		}
	}
}
