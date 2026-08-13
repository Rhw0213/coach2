package com.interview.coach2.reservation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 트랜잭션 롤백에 기대지 않는다 — 예약 INSERT는 REQUIRES_NEW로 별도 커밋되므로
 * 테스트 트랜잭션으로 감싸면 정작 검증하려는 동작이 사라진다. 대신 매번 지우고 시작한다.
 */
@SpringBootTest
class ReservationServiceTest {

	@Autowired ReservationService service;
	@Autowired ReservationWriter writer;
	@Autowired BoothRepository booths;
	@Autowired VisitorRepository visitors;
	@Autowired ReservationRepository reservations;

	private Booth booth;
	private Instant slot;

	@BeforeEach
	void setUp() {
		reservations.deleteAll();
		visitors.deleteAll();
		booths.deleteAll();

		// 항상 미래인 날을 고른다 — 실행 시점에 따라 깨지지 않게.
		LocalDate eventDate = Slots.today().plusDays(7);
		booth = booths.save(new Booth("동해기업", "A-12", "백엔드 개발자 모집",
			eventDate, LocalTime.of(10, 0), LocalTime.of(17, 0), 30));
		slot = eventDate.atTime(11, 0).atZone(Slots.ZONE).toInstant();
	}

	private static void assertStatus(Throwable e, HttpStatus expected) {
		assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(expected);
	}

	@Test
	void 예약하면_토큰이_발급된다() {
		ReservationService.BookResult result =
			service.book(booth.getId(), slot, "홍길동", "010-1234-5678");

		assertThat(result.visitorToken()).isNotBlank();
		assertThat(result.reservation().getStatus()).isEqualTo(ReservationStatus.RESERVED);
		// 부스 설정이 나중에 바뀌어도 흔들리지 않도록 길이를 복사해둔다
		assertThat(result.reservation().getSlotMinutes()).isEqualTo(30);
	}

	@Test
	void 전화번호는_표기가_달라도_같은_사람이다() {
		String token = service.book(booth.getId(), slot, "홍길동", "010-1234-5678").visitorToken();
		Instant other = slot.plusSeconds(1800);

		String again = service.book(booth.getId(), other, "홍길동", "01012345678").visitorToken();

		assertThat(again).isEqualTo(token);
		assertThat(visitors.count()).isEqualTo(1);
	}

	@Test
	void 같은_슬롯은_두_번_예약되지_않는다() {
		service.book(booth.getId(), slot, "홍길동", "01011112222");

		assertThatThrownBy(() -> service.book(booth.getId(), slot, "김철수", "01033334444"))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(e -> assertStatus(e, HttpStatus.CONFLICT));
	}

	/**
	 * 중복예약을 실제로 막는 것은 DB 유니크 제약이다. 서비스의 사전 검사를 우회해
	 * 두 건을 직접 밀어넣어, 애플리케이션 검사가 없어도 DB가 거절하는지 확인한다.
	 * 이 테스트가 깨지면 동시 요청에서 이중예약이 뚫린다.
	 */
	@Test
	void 사전검사를_우회해도_DB가_같은_슬롯을_거절한다() {
		Visitor a = writer.insertVisitor(new Visitor("홍길동", "01011112222"));
		Visitor b = writer.insertVisitor(new Visitor("김철수", "01033334444"));

		writer.insert(new Reservation(booth.getId(), a.getId(), slot, 30));

		assertThatThrownBy(() -> writer.insert(new Reservation(booth.getId(), b.getId(), slot, 30)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 한_사람이_같은_시각에_두_부스를_잡을_수_없다() {
		Booth other = booths.save(new Booth("서해기업", "B-03", null,
			booth.getEventDate(), LocalTime.of(10, 0), LocalTime.of(17, 0), 30));

		service.book(booth.getId(), slot, "홍길동", "01011112222");

		assertThatThrownBy(() -> service.book(other.getId(), slot, "홍길동", "01011112222"))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(e -> assertStatus(e, HttpStatus.CONFLICT));
	}

	@Test
	void 다른_시각이면_여러_부스를_돌_수_있다() {
		Booth other = booths.save(new Booth("서해기업", "B-03", null,
			booth.getEventDate(), LocalTime.of(10, 0), LocalTime.of(17, 0), 30));

		service.book(booth.getId(), slot, "홍길동", "01011112222");
		ReservationService.BookResult second =
			service.book(other.getId(), slot.plusSeconds(1800), "홍길동", "01011112222");

		assertThat(second.reservation().getStatus()).isEqualTo(ReservationStatus.RESERVED);
		assertThat(service.myReservations(second.visitorToken())).hasSize(2);
	}

	@Test
	void 취소하면_같은_슬롯을_다시_예약할_수_있다() {
		ReservationService.BookResult first =
			service.book(booth.getId(), slot, "홍길동", "01011112222");

		service.cancel(first.reservation().getId(), first.visitorToken());

		// 슬롯키가 NULL이 되어 유니크 제약에서 빠져야 다른 사람이 잡을 수 있다.
		ReservationService.BookResult second =
			service.book(booth.getId(), slot, "김철수", "01033334444");
		assertThat(second.reservation().getId()).isNotEqualTo(first.reservation().getId());
	}

	@Test
	void 예약한_슬롯은_가용목록에서_빠진다() {
		List<Instant> before = service.availableSlots(booth.getId());

		service.book(booth.getId(), slot, "홍길동", "01011112222");

		assertThat(service.availableSlots(booth.getId()))
			.hasSize(before.size() - 1)
			.doesNotContain(slot);
	}

	@Test
	void 슬롯_경계가_아닌_시각은_거부된다() {
		Instant offGrid = slot.plusSeconds(600); // 11:10 — 30분 격자에 없다

		assertThatThrownBy(() -> service.book(booth.getId(), offGrid, "홍길동", "01011112222"))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
	}

	@Test
	void 지난_행사일은_예약할_수_없다() {
		LocalDate past = Slots.today().minusDays(7);
		Booth pastBooth = booths.save(new Booth("과거기업", "Z-99", null,
			past, LocalTime.of(10, 0), LocalTime.of(17, 0), 30));
		Instant pastSlot = past.atTime(11, 0).atZone(Slots.ZONE).toInstant();

		assertThatThrownBy(() -> service.book(pastBooth.getId(), pastSlot, "홍길동", "01011112222"))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
	}

	@Test
	void 남의_토큰으로는_취소할_수_없고_존재도_알려주지_않는다() {
		ReservationService.BookResult mine =
			service.book(booth.getId(), slot, "홍길동", "01011112222");
		String otherToken = service.book(booth.getId(), slot.plusSeconds(1800), "김철수", "01033334444")
			.visitorToken();

		assertThatThrownBy(() -> service.cancel(mine.reservation().getId(), otherToken))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));

		assertThat(reservations.findById(mine.reservation().getId()))
			.get().extracting(Reservation::getStatus).isEqualTo(ReservationStatus.RESERVED);
	}

	@Test
	void 중지된_부스는_예약할_수_없다() {
		booth.deactivate();
		booths.save(booth);

		assertThatThrownBy(() -> service.book(booth.getId(), slot, "홍길동", "01011112222"))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
	}
}
