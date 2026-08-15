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
import static org.assertj.core.api.Assertions.catchThrowable;

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
		// 부스당 1건 제한이 있으므로 같은 사람인지 확인하려면 다른 부스를 잡아야 한다.
		Booth other = booths.save(new Booth("서해기업", "B-03", null,
			booth.getEventDate(), LocalTime.of(10, 0), LocalTime.of(17, 0), 30));

		String token = service.book(booth.getId(), slot, "홍길동", "010-1234-5678").visitorToken();
		String again = service.book(other.getId(), slot.plusSeconds(1800), "홍길동", "01012345678")
			.visitorToken();

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

		writer.insert(new Reservation(booth.getId(), a.getId(), slot, 30, 1));

		assertThatThrownBy(() -> writer.insert(new Reservation(booth.getId(), b.getId(), slot, 30, 1)))
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
	void 같은_부스를_두_번_예약할_수_없다() {
		service.book(booth.getId(), slot, "홍길동", "01011112222");

		// 시간이 달라도 같은 부스면 막는다 — 한 사람이 한 기업의 시간대를 여러 개 선점하지 못한다.
		assertThatThrownBy(() ->
			service.book(booth.getId(), slot.plusSeconds(1800), "홍길동", "01011112222"))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(e -> assertStatus(e, HttpStatus.CONFLICT));
	}

	@Test
	void 사전검사를_우회해도_DB가_같은_부스_중복을_거절한다() {
		Visitor v = writer.insertVisitor(new Visitor("홍길동", "01011112222"));
		writer.insert(new Reservation(booth.getId(), v.getId(), slot, 30, 1));

		assertThatThrownBy(() -> writer.insert(
			new Reservation(booth.getId(), v.getId(), slot.plusSeconds(1800), 30, 1)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 취소하면_그_부스를_다시_예약할_수_있다() {
		ReservationService.BookResult first =
			service.book(booth.getId(), slot, "홍길동", "01011112222");

		service.cancel(first.reservation().getId(), first.visitorToken());

		// visitorBoothKey도 NULL이 되어야 본인이 시간을 바꿔 다시 잡을 수 있다.
		ReservationService.BookResult again =
			service.book(booth.getId(), slot.plusSeconds(1800), "홍길동", "01011112222");
		assertThat(again.reservation().getStatus()).isEqualTo(ReservationStatus.RESERVED);
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

	// ── 그룹면접(정원 5) ────────────────────────────────────────
	// 정원이 1인 위 테스트들이 그대로 통과해야 한다 — 1:1은 정원 1인 특수한 경우일 뿐이다.

	/** 정원 5짜리 부스. 슬롯은 위 setUp의 것과 같은 시각을 쓴다(부스가 다르므로 충돌하지 않는다). */
	private Booth groupBooth() {
		return booths.save(new Booth("동해기업 그룹면접", "G-01", "그룹면접",
			booth.getEventDate(), LocalTime.of(10, 0), LocalTime.of(17, 0), 30, 5));
	}

	@Test
	void 정원이_5명이면_같은_슬롯에_다섯_명이_들어간다() {
		Booth group = groupBooth();

		for (int i = 1; i <= 5; i++) {
			service.book(group.getId(), slot, "참가자" + i, "0101111000" + i);
		}

		assertThat(reservations.countByBoothIdAndStartTimeAndStatus(
			group.getId(), slot, ReservationStatus.RESERVED)).isEqualTo(5);
	}

	@Test
	void 정원을_넘는_여섯_번째는_거절된다() {
		Booth group = groupBooth();
		for (int i = 1; i <= 5; i++) {
			service.book(group.getId(), slot, "참가자" + i, "0101111000" + i);
		}

		assertThatThrownBy(() -> service.book(group.getId(), slot, "여섯째", "01099998888"))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(e -> assertStatus(e, HttpStatus.CONFLICT));
	}

	/**
	 * 사전검사가 없어도 DB가 정원을 지키는지 본다. 좌석 번호가 유니크 키에 들어가므로
	 * 같은 좌석을 두 번 넣으려는 시도는 제약에 막힌다 — 이게 동시 요청의 진짜 방어선이다.
	 * 이 테스트가 깨지면 그룹 슬롯에 정원을 넘겨 사람이 들어갈 수 있다.
	 */
	@Test
	void 사전검사를_우회해도_DB가_같은_좌석을_두_번_주지_않는다() {
		Booth group = groupBooth();
		Visitor a = writer.insertVisitor(new Visitor("가", "01011110001"));
		Visitor b = writer.insertVisitor(new Visitor("나", "01011110002"));

		writer.insert(new Reservation(group.getId(), a.getId(), slot, 30, 3));

		assertThatThrownBy(() -> writer.insert(
			new Reservation(group.getId(), b.getId(), slot, 30, 3)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 정원이_찬_슬롯은_예약_가능_목록에서_빠진다() {
		Booth group = groupBooth();

		assertThat(service.availableSlots(group.getId())).contains(slot);

		for (int i = 1; i <= 4; i++) {
			service.book(group.getId(), slot, "참가자" + i, "0101111000" + i);
		}
		// 4/5는 아직 자리가 있다 — 한 명이라도 찼다고 슬롯을 닫아버리면 그룹면접이 성립하지 않는다.
		assertThat(service.availableSlots(group.getId())).contains(slot);

		service.book(group.getId(), slot, "참가자5", "01011110005");
		assertThat(service.availableSlots(group.getId())).doesNotContain(slot);
	}

	@Test
	void 그룹_예약을_취소하면_그_자리가_다시_열린다() {
		Booth group = groupBooth();
		ReservationService.BookResult first =
			service.book(group.getId(), slot, "참가자1", "01011110001");
		for (int i = 2; i <= 5; i++) {
			service.book(group.getId(), slot, "참가자" + i, "0101111000" + i);
		}
		assertThat(service.availableSlots(group.getId())).doesNotContain(slot);

		service.cancel(first.reservation().getId(), first.visitorToken());

		assertThat(service.availableSlots(group.getId())).contains(slot);
		service.book(group.getId(), slot, "대기자", "01077776666");
		assertThat(reservations.countByBoothIdAndStartTimeAndStatus(
			group.getId(), slot, ReservationStatus.RESERVED)).isEqualTo(5);
	}

	/**
	 * 1번 좌석 키가 예전 형식과 글자 그대로 같아야 한다. 이 성질이 깨지면 이 기능 이전에
	 * 저장된 예약 행과 새 예약이 서로 다른 키를 갖게 되어 같은 슬롯에 두 명이 들어간다.
	 * 운영 DB를 손대지 않고 배포할 수 있는 근거가 이것이므로 테스트로 못박아둔다.
	 */
	@Test
	void 일번_좌석은_예전_키_형식을_그대로_쓴다() {
		assertThat(new Reservation(7L, 9L, slot, 30, 1).getBoothSlotKey())
			.isEqualTo("7@" + slot.toEpochMilli());
		assertThat(new Reservation(7L, 9L, slot, 30, 2).getBoothSlotKey())
			.isEqualTo("7@" + slot.toEpochMilli() + "#2");
	}

	@Test
	void 정원은_1명_미만일_수_없다() {
		assertThatThrownBy(() -> new Booth("영정원", "X-00", null,
			booth.getEventDate(), LocalTime.of(10, 0), LocalTime.of(17, 0), 30, 0))
			.isInstanceOf(IllegalArgumentException.class);
	}

	// ── 남은 자리 수 ──────────────────────────────────────────────

	/**
	 * 이게 없으면 안내 페이지의 시간표가 정원 5인 부스에서 다섯 명이 찰 때까지 꿈쩍하지 않아,
	 * 방문자는 자기 신청이 반영됐는지 알 수 없다.
	 */
	@Test
	void 슬롯마다_남은_자리_수를_돌려준다() {
		Booth group = groupBooth();
		service.book(group.getId(), slot, "참가자1", "01011110001");
		service.book(group.getId(), slot, "참가자2", "01011110002");

		List<ReservationService.SlotSeats> open = service.openSlots(group.getId());

		assertThat(open).filteredOn(s -> s.startTime().equals(slot))
			.singleElement()
			.satisfies(s -> {
				assertThat(s.remaining()).isEqualTo(3);
				assertThat(s.capacity()).isEqualTo(5);
			});
		// 예약이 없는 슬롯은 정원 그대로다.
		assertThat(open).filteredOn(s -> !s.startTime().equals(slot))
			.allSatisfy(s -> assertThat(s.remaining()).isEqualTo(5));
	}

	@Test
	void 정원이_다_차면_그_슬롯은_빠진다() {
		Booth group = groupBooth();
		for (int i = 1; i <= 5; i++) {
			service.book(group.getId(), slot, "참가자" + i, "0101111000" + i);
		}

		assertThat(service.openSlots(group.getId()))
			.noneMatch(s -> s.startTime().equals(slot));
	}

	/** 두 메서드가 같은 규칙을 따로 갖고 있으면 화면과 예약 목록이 언젠가 어긋난다. */
	@Test
	void 예약_가능_시각은_남은_자리_목록과_일치한다() {
		Booth group = groupBooth();
		service.book(group.getId(), slot, "참가자1", "01011110001");

		assertThat(service.availableSlots(group.getId()))
			.isEqualTo(service.openSlots(group.getId()).stream()
				.map(ReservationService.SlotSeats::startTime).toList());
	}

	// ── 이름 + 연락처 본인 확인 ────────────────────────────────────

	@Test
	void 이름과_연락처로_내_예약을_찾는다() {
		String token = service.book(booth.getId(), slot, "홍길동", "010-1234-5678").visitorToken();

		Visitor found = service.visitorByNameAndPhone("홍길동", "010-1234-5678");

		assertThat(found.getToken()).isEqualTo(token);
		assertThat(service.reservationsOf(found.getId())).hasSize(1);
	}

	@Test
	void 연락처는_표기가_달라도_같은_사람으로_찾는다() {
		service.book(booth.getId(), slot, "홍길동", "010-1234-5678");

		assertThat(service.visitorByNameAndPhone("홍길동", "01012345678").getName())
			.isEqualTo("홍길동");
	}

	@Test
	void 이름의_공백과_대소문자는_무시한다() {
		service.book(booth.getId(), slot, "Hong Gildong", "01012345678");

		// 띄어쓰기나 대소문자가 어긋난다고 본인이 자기 예약을 못 찾으면 안 된다.
		assertThat(service.visitorByNameAndPhone("honggildong", "01012345678")).isNotNull();
	}

	@Test
	void 이름이_다르면_찾지_못한다() {
		service.book(booth.getId(), slot, "홍길동", "01012345678");

		assertThatThrownBy(() -> service.visitorByNameAndPhone("김철수", "01012345678"))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(e -> assertStatus(e, HttpStatus.NOT_FOUND));
	}

	/**
	 * 번호가 아예 없을 때와 이름만 어긋날 때가 구분되면, 번호만 아는 사람이
	 * "이 사람이 박람회에 왔는가"를 확인할 수 있게 된다. 두 경우의 응답이 같아야 한다.
	 */
	@Test
	void 없는_번호와_이름_불일치는_구분되지_않는다() {
		service.book(booth.getId(), slot, "홍길동", "01012345678");

		Throwable wrongName = catchThrowable(
			() -> service.visitorByNameAndPhone("김철수", "01012345678"));
		Throwable noSuchPhone = catchThrowable(
			() -> service.visitorByNameAndPhone("김철수", "01099998888"));

		assertStatus(wrongName, HttpStatus.NOT_FOUND);
		assertStatus(noSuchPhone, HttpStatus.NOT_FOUND);
		assertThat(wrongName.getMessage()).isEqualTo(noSuchPhone.getMessage());
	}

	@Test
	void 연락처가_비어_있으면_400이다() {
		assertThatThrownBy(() -> service.visitorByNameAndPhone("홍길동", "번호아님"))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
	}

	/** 예약이 아직 없는 사람은 찾히되 빈 목록이 나온다 — 예약 완료 직후 취소한 경우다. */
	@Test
	void 예약이_없는_사람은_빈_목록을_돌려준다() {
		ReservationService.BookResult booked =
			service.book(booth.getId(), slot, "홍길동", "01012345678");
		service.cancel(booked.reservation().getId(), booked.visitorToken());

		Visitor found = service.visitorByNameAndPhone("홍길동", "01012345678");

		assertThat(service.reservationsOf(found.getId()))
			.allMatch(r -> r.getStatus() == ReservationStatus.CANCELLED);
	}
}
