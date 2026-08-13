package com.interview.coach2.reservation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SlotsTest {

	private static final LocalDate DAY = LocalDate.of(2026, 9, 15);

	private static Booth booth(LocalTime from, LocalTime to, int slotMinutes) {
		return new Booth("동해기업", "A-12", null, DAY, from, to, slotMinutes);
	}

	@Test
	void 운영시간을_슬롯길이로_나눈다() {
		List<Instant> slots = Slots.forBooth(booth(LocalTime.of(9, 0), LocalTime.of(12, 0), 60));

		assertThat(slots).containsExactly(
			DAY.atTime(9, 0).atZone(Slots.ZONE).toInstant(),
			DAY.atTime(10, 0).atZone(Slots.ZONE).toInstant(),
			DAY.atTime(11, 0).atZone(Slots.ZONE).toInstant());
	}

	@Test
	void 슬롯이_운영종료를_넘기면_만들지_않는다() {
		// 09:00~10:30에 45분 슬롯 → 09:00, 09:45. 다음 자리는 11:15가 되어 넘친다.
		assertThat(Slots.forBooth(booth(LocalTime.of(9, 0), LocalTime.of(10, 30), 45))).hasSize(2);
	}

	@Test
	void 짧은_슬롯도_정확히_나눈다() {
		// 박람회 부스는 20~30분 단위가 흔하다.
		assertThat(Slots.forBooth(booth(LocalTime.of(10, 0), LocalTime.of(11, 0), 20))).hasSize(3);
	}

	@Test
	void 자정_직전_운영은_되돌아가지_않는다() {
		// LocalTime.plusMinutes를 그대로 썼다면 23:00 + 60분 = 00:00이 되어
		// 종료조건이 무너지고 무한루프나 엉뚱한 슬롯이 생긴다.
		assertThat(Slots.forBooth(booth(LocalTime.of(22, 0), LocalTime.of(23, 59), 60)))
			.containsExactly(DAY.atTime(22, 0).atZone(Slots.ZONE).toInstant());
	}

	@Test
	void 슬롯이_운영시간보다_길면_비어_있다() {
		assertThat(Slots.forBooth(booth(LocalTime.of(9, 0), LocalTime.of(9, 30), 60))).isEmpty();
	}
}
