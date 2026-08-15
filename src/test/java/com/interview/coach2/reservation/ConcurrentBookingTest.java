package com.interview.coach2.reservation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 진짜로 동시에 때려본다. 단일 스레드 테스트는 '검사 후 저장' 사이의 창을 재현하지 못한다.
 *
 * 박람회 예약은 마감 직전에 몰린다. 인기 있는 기업의 한 슬롯에 수십 명이 같은 순간
 * 달려드는 것이 정상 시나리오이므로, 그 상황에서 정원이 정확히 지켜지는지를 못박아둔다.
 *
 * 여기서 잡고 싶은 실패는 두 가지다.
 *   1. 정원 초과 — 행사 당일 되돌릴 수 없다.
 *   2. ResponseStatusException이 아닌 예외 — 사용자에게 500이 나가는 경우.
 *      특히 커넥션 풀 고갈(book()의 트랜잭션이 열린 채 REQUIRES_NEW가 두 번째 커넥션을
 *      요구한다)이 여기서 드러난다.
 */
@SpringBootTest
class ConcurrentBookingTest {

	@Autowired ReservationService service;
	@Autowired BoothRepository booths;
	@Autowired VisitorRepository visitors;
	@Autowired ReservationRepository reservations;

	private Instant slot;

	@BeforeEach
	void setUp() {
		reservations.deleteAll();
		visitors.deleteAll();
		booths.deleteAll();
		slot = Slots.today().plusDays(7).atTime(11, 0).atZone(Slots.ZONE).toInstant();
	}

	private Booth booth(int capacity) {
		return booths.save(new Booth("동해기업", "A-12", null,
			Slots.today().plusDays(7), LocalTime.of(10, 0), LocalTime.of(17, 0), 30, capacity));
	}

	/** 서로 다른 사람이어야 한다 — 같은 번호면 visitorBoothKey에 걸려 경합이 아니라 중복이 된다. */
	private static String phone(int n) {
		return String.format("010%08d", n);
	}

	private record Result(int ok, int rejected, List<String> unexpected) {
	}

	/** N명이 같은 순간에 같은 슬롯을 노린다. 래치로 출발을 맞춰 진짜 경합을 만든다. */
	private Result stampede(Long boothId, int people) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(Math.min(people, 64));
		CountDownLatch ready = new CountDownLatch(people);
		CountDownLatch go = new CountDownLatch(1);
		AtomicInteger ok = new AtomicInteger();
		AtomicInteger rejected = new AtomicInteger();
		ConcurrentLinkedQueue<String> unexpected = new ConcurrentLinkedQueue<>();

		List<Future<?>> futures = new ArrayList<>();
		for (int i = 0; i < people; i++) {
			final int n = i;
			futures.add(pool.submit(() -> {
				ready.countDown();
				go.await();
				try {
					service.book(boothId, slot, "참가자" + n, phone(n));
					ok.incrementAndGet();
				} catch (ResponseStatusException e) {
					// 정원이 찼다는 정상적인 거절.
					rejected.incrementAndGet();
				} catch (Throwable t) {
					// 사용자에게 500이 나가는 경우. 하나라도 있으면 실패로 본다.
					unexpected.add(t.getClass().getSimpleName() + ": " + t.getMessage());
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
		return new Result(ok.get(), rejected.get(), new ArrayList<>(unexpected));
	}

	@Test
	void 정원_1인_슬롯에_40명이_동시에_달려들어도_한_명만_들어간다() throws Exception {
		Booth b = booth(1);

		Result r = stampede(b.getId(), 40);

		assertThat(r.unexpected()).as("사용자에게 500이 나간 요청").isEmpty();
		assertThat(r.ok()).as("성공한 예약 수").isEqualTo(1);
		assertThat(r.rejected()).isEqualTo(39);
		assertThat(reservations.countByBoothIdAndStartTimeAndStatus(
			b.getId(), slot, ReservationStatus.RESERVED)).isEqualTo(1);
	}

	@Test
	void 정원_5인_슬롯에_60명이_동시에_달려들어도_정확히_다섯_명만_들어간다() throws Exception {
		Booth b = booth(5);

		Result r = stampede(b.getId(), 60);

		assertThat(r.unexpected()).as("사용자에게 500이 나간 요청").isEmpty();
		assertThat(r.ok()).as("성공한 예약 수 — 이 값이 5를 넘으면 정원이 뚫린 것이다").isEqualTo(5);
		assertThat(r.rejected()).isEqualTo(55);
		assertThat(reservations.countByBoothIdAndStartTimeAndStatus(
			b.getId(), slot, ReservationStatus.RESERVED)).isEqualTo(5);
	}

	/**
	 * 같은 전화번호로 동시에 첫 예약이 들어오는 경우. Visitor의 unique phone에 걸린 뒤
	 * findOrCreateVisitor가 재조회로 복구하는 경로를 실제로 밟는다.
	 * 이 경로가 깨지면 한 사람이 서로 다른 Visitor 행 두 개로 갈려 세 유니크 제약이 전부 무력해진다.
	 */
	@Test
	void 같은_번호로_동시에_들어와도_사람은_하나로_합쳐진다() throws Exception {
		Booth b = booth(5);
		int people = 20;

		ExecutorService pool = Executors.newFixedThreadPool(16);
		CountDownLatch go = new CountDownLatch(1);
		AtomicInteger ok = new AtomicInteger();
		ConcurrentLinkedQueue<String> unexpected = new ConcurrentLinkedQueue<>();

		List<Future<?>> futures = new ArrayList<>();
		for (int i = 0; i < people; i++) {
			futures.add(pool.submit(() -> {
				go.await();
				try {
					service.book(b.getId(), slot, "홍길동", "010-1111-2222");
					ok.incrementAndGet();
				} catch (ResponseStatusException e) {
					// 이미 잡았다는 정상 거절.
				} catch (Throwable t) {
					unexpected.add(t.getClass().getSimpleName() + ": " + t.getMessage());
				}
				return null;
			}));
		}
		go.countDown();
		for (Future<?> f : futures) {
			f.get(120, TimeUnit.SECONDS);
		}
		pool.shutdown();

		assertThat(unexpected).as("사용자에게 500이 나간 요청").isEmpty();
		assertThat(visitors.findAll()).as("같은 번호는 한 사람이어야 한다").hasSize(1);
		// 한 사람은 한 부스를 한 번만 — 정원이 5여도 혼자 5석을 먹지 못한다.
		assertThat(ok.get()).isEqualTo(1);
		assertThat(reservations.countByBoothIdAndStartTimeAndStatus(
			b.getId(), slot, ReservationStatus.RESERVED)).isEqualTo(1);
	}
}
