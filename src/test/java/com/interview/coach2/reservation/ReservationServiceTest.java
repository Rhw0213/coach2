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
import java.util.EnumSet;
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
	@Autowired CoachRepository coaches;
	@Autowired CustomerRepository customers;
	@Autowired ReservationRepository reservations;

	private Coach coach;
	private Instant slot;

	@BeforeEach
	void setUp() {
		reservations.deleteAll();
		customers.deleteAll();
		coaches.deleteAll();

		// 항상 미래이고 항상 근무요일인 날을 고른다 — 실행 시점에 따라 깨지지 않게.
		LocalDate target = Slots.today().plusDays(7);
		coach = coaches.save(new Coach("김코치", "커리어 코치",
			LocalTime.of(9, 0), LocalTime.of(12, 0), 60,
			EnumSet.of(target.getDayOfWeek())));
		slot = target.atTime(10, 0).atZone(Slots.ZONE).toInstant();
	}

	@Test
	void 예약하면_토큰이_발급된다() {
		ReservationService.BookResult result =
			service.book(coach.getId(), slot, "홍길동", "010-1234-5678");

		assertThat(result.customerToken()).isNotBlank();
		assertThat(result.reservation().getStatus()).isEqualTo(ReservationStatus.RESERVED);
		// 코치 설정이 나중에 바뀌어도 흔들리지 않도록 길이를 복사해둔다
		assertThat(result.reservation().getSlotMinutes()).isEqualTo(60);
	}

	@Test
	void 전화번호는_표기가_달라도_같은_사람이다() {
		String token = service.book(coach.getId(), slot, "홍길동", "010-1234-5678").customerToken();
		Instant other = slot.plusSeconds(3600);

		String again = service.book(coach.getId(), other, "홍길동", "01012345678").customerToken();

		assertThat(again).isEqualTo(token);
		assertThat(customers.count()).isEqualTo(1);
	}

	@Test
	void 같은_슬롯은_두_번_예약되지_않는다() {
		service.book(coach.getId(), slot, "홍길동", "01011112222");

		assertThatThrownBy(() -> service.book(coach.getId(), slot, "김철수", "01033334444"))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
				.isEqualTo(HttpStatus.CONFLICT));
	}

	/**
	 * 중복예약을 실제로 막는 것은 DB 유니크 제약이다. 서비스의 사전 검사를 우회해
	 * 두 건을 직접 밀어넣어, 애플리케이션 검사가 없어도 DB가 거절하는지 확인한다.
	 * 이 테스트가 깨지면 동시 요청에서 이중예약이 뚫린다.
	 */
	@Test
	void 사전검사를_우회해도_DB가_같은_슬롯을_거절한다() {
		Customer a = writer.insertCustomer(new Customer("홍길동", "01011112222"));
		Customer b = writer.insertCustomer(new Customer("김철수", "01033334444"));

		writer.insert(new Reservation(coach.getId(), a.getId(), slot, 60));

		assertThatThrownBy(() -> writer.insert(new Reservation(coach.getId(), b.getId(), slot, 60)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 한_사람이_같은_시각에_두_코치를_잡을_수_없다() {
		LocalDate target = LocalDate.ofInstant(slot, Slots.ZONE);
		Coach other = coaches.save(new Coach("이코치", null,
			LocalTime.of(9, 0), LocalTime.of(12, 0), 60, EnumSet.of(target.getDayOfWeek())));

		service.book(coach.getId(), slot, "홍길동", "01011112222");

		assertThatThrownBy(() -> service.book(other.getId(), slot, "홍길동", "01011112222"))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
				.isEqualTo(HttpStatus.CONFLICT));
	}

	@Test
	void 취소하면_같은_슬롯을_다시_예약할_수_있다() {
		ReservationService.BookResult first =
			service.book(coach.getId(), slot, "홍길동", "01011112222");

		service.cancel(first.reservation().getId(), first.customerToken());

		// 슬롯키가 NULL이 되어 유니크 제약에서 빠져야 다른 사람이 잡을 수 있다.
		ReservationService.BookResult second =
			service.book(coach.getId(), slot, "김철수", "01033334444");
		assertThat(second.reservation().getId()).isNotEqualTo(first.reservation().getId());
	}

	@Test
	void 예약한_슬롯은_가용목록에서_빠진다() {
		LocalDate target = LocalDate.ofInstant(slot, Slots.ZONE);
		List<Instant> before = service.availableSlots(coach.getId(), target);

		service.book(coach.getId(), slot, "홍길동", "01011112222");

		assertThat(service.availableSlots(coach.getId(), target))
			.hasSize(before.size() - 1)
			.doesNotContain(slot);
	}

	@Test
	void 슬롯_경계가_아닌_시각은_거부된다() {
		Instant offGrid = slot.plusSeconds(600); // 10:10 — 정각 슬롯이 아니다

		assertThatThrownBy(() -> service.book(coach.getId(), offGrid, "홍길동", "01011112222"))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST));
	}

	@Test
	void 지난_시간은_예약할_수_없다() {
		LocalDate past = Slots.today().minusDays(7);
		Coach pastCoach = coaches.save(new Coach("박코치", null,
			LocalTime.of(9, 0), LocalTime.of(12, 0), 60, EnumSet.of(past.getDayOfWeek())));
		Instant pastSlot = past.atTime(10, 0).atZone(Slots.ZONE).toInstant();

		assertThatThrownBy(() -> service.book(pastCoach.getId(), pastSlot, "홍길동", "01011112222"))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST));
	}

	@Test
	void 남의_토큰으로는_취소할_수_없고_존재도_알려주지_않는다() {
		ReservationService.BookResult mine =
			service.book(coach.getId(), slot, "홍길동", "01011112222");
		String otherToken = service.book(coach.getId(), slot.plusSeconds(3600), "김철수", "01033334444")
			.customerToken();

		assertThatThrownBy(() -> service.cancel(mine.reservation().getId(), otherToken))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND));

		assertThat(reservations.findById(mine.reservation().getId()))
			.get().extracting(Reservation::getStatus).isEqualTo(ReservationStatus.RESERVED);
	}

	@Test
	void 비활성_코치는_예약할_수_없다() {
		coach.deactivate();
		coaches.save(coach);

		assertThatThrownBy(() -> service.book(coach.getId(), slot, "홍길동", "01011112222"))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND));
	}
}
