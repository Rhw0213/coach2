package com.interview.coach2.reservation;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 웹 계층까지 통과하는 동시성. ConcurrentBookingTest는 service.book()을 직접 부르므로
 * 톰캣 스레드·요청 매핑·JSON 직렬화·요청 하나가 커넥션을 쥐는 시간이 검증에서 빠진다.
 *
 * 행사 예상 부하는 동시접속 50명이다. 커넥션 풀은 기본값 10개(maximumPoolSize)이고
 * 톰캣 스레드는 200개다 — 스레드가 커넥션보다 스무 배 많으므로, 요청 하나가 커넥션을
 * 오래 쥐는 경로가 생기면 51번째부터 connection-timeout 30초를 기다리다 500이 나간다.
 * 그 간극을 여기서 못박는다.
 *
 * 시간은 재되 단정하지 않는다. CI 러너의 속도로 임계값을 정하면 코드가 멀쩡해도
 * 빨간불이 뜨고, 그 빨간불은 결국 무시된다. 단정하는 것은 '500이 없다'와 '정원이 지켜진다'뿐이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrentHttpBookingTest {

	private static final int PEOPLE = 50;
	private static final int CAPACITY = 10;

	@LocalServerPort
	int port;

	@Autowired BoothRepository booths;
	@Autowired ReservationRepository reservations;
	@Autowired VisitorRepository visitors;

	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(java.time.Duration.ofSeconds(10))
		.build();

	private record Call(int status, long millis) {
	}

	@Test
	void 오십_명이_동시에_예약해도_500은_없고_정원은_지켜진다() throws Exception {
		reservations.deleteAll();
		visitors.deleteAll();
		booths.deleteAll();

		Booth booth = booths.save(new Booth("동해기업", "A-12", null,
			Slots.today().plusDays(7), LocalTime.of(10, 0), LocalTime.of(17, 0), 30, CAPACITY));
		Instant slot = Slots.forBooth(booth).get(0);

		ExecutorService pool = Executors.newFixedThreadPool(PEOPLE);
		CountDownLatch ready = new CountDownLatch(PEOPLE);
		CountDownLatch go = new CountDownLatch(1);
		ConcurrentLinkedQueue<Call> calls = new ConcurrentLinkedQueue<>();
		ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();

		List<Future<?>> futures = new ArrayList<>();
		for (int i = 0; i < PEOPLE; i++) {
			final int n = i;
			futures.add(pool.submit(() -> {
				ready.countDown();
				go.await();
				try {
					// 실제 방문자가 하는 순서 그대로 — 목록을 받고, 남은 시간을 보고, 신청한다.
					calls.add(get("/api/booths"));
					calls.add(get("/api/booths/" + booth.getId() + "/slots"));
					calls.add(post("/api/reservations", """
						{"boothId":%d,"startTime":"%s","name":"참가자%d","phone":"010%08d",
						 "school":"건국대","major":"컴퓨터공학","standing":"4학년","agreed":true}
						""".formatted(booth.getId(), slot, n, n)));
				} catch (Exception e) {
					failures.add(e.getClass().getSimpleName() + ": " + e.getMessage());
				}
				return null;
			}));
		}

		ready.await(30, TimeUnit.SECONDS);
		go.countDown();
		for (Future<?> f : futures) {
			f.get(120, TimeUnit.SECONDS);
		}
		pool.shutdown();

		List<Call> all = new ArrayList<>(calls);
		List<Long> times = all.stream().map(Call::millis).sorted().toList();
		long booked = all.stream().filter(c -> c.status() == 200).count();

		System.out.printf("### 동시 %d명 / 요청 %d건 · p50=%dms p95=%dms max=%dms · 상태코드 %s%n",
			PEOPLE, all.size(), times.get(times.size() / 2), times.get((int) (times.size() * 0.95)),
			times.get(times.size() - 1),
			all.stream().collect(java.util.stream.Collectors.groupingBy(Call::status,
				java.util.stream.Collectors.counting())));

		assertThat(failures).as("연결 자체가 실패한 요청").isEmpty();
		assertThat(all).as("5xx가 하나라도 있으면 커넥션 풀이나 스레드가 무너진 것이다")
			.noneMatch(c -> c.status() >= 500);
		// 목록·슬롯 조회 2건은 언제나 200이므로, 예약 성공은 (전체 200 - 100)건이다.
		assertThat(booked - PEOPLE * 2L).as("정원을 넘겨 들어간 예약").isEqualTo(CAPACITY);
		assertThat(reservations.countByBoothIdAndStartTimeAndStatus(
			booth.getId(), slot, ReservationStatus.RESERVED)).isEqualTo(CAPACITY);
	}

	private Call get(String path) throws Exception {
		long t = System.nanoTime();
		HttpResponse<String> res = http.send(
			HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
			HttpResponse.BodyHandlers.ofString());
		return new Call(res.statusCode(), (System.nanoTime() - t) / 1_000_000);
	}

	private Call post(String path, String json) throws Exception {
		long t = System.nanoTime();
		HttpResponse<String> res = http.send(
			HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json))
				.build(),
			HttpResponse.BodyHandlers.ofString());
		return new Call(res.statusCode(), (System.nanoTime() - t) / 1_000_000);
	}
}
