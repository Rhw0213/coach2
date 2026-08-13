package com.interview.coach2.reservation;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SlotsTest {

	private static Coach coach(LocalTime from, LocalTime to, int slotMinutes, DayOfWeek... days) {
		return new Coach("코치", null, from, to, slotMinutes, EnumSet.copyOf(List.of(days)));
	}

	@Test
	void 근무시간을_슬롯길이로_나눈다() {
		Coach c = coach(LocalTime.of(9, 0), LocalTime.of(12, 0), 60, DayOfWeek.MONDAY);
		LocalDate monday = LocalDate.of(2026, 8, 17);

		List<Instant> slots = Slots.forDate(c, monday);

		assertThat(slots).containsExactly(
			monday.atTime(9, 0).atZone(Slots.ZONE).toInstant(),
			monday.atTime(10, 0).atZone(Slots.ZONE).toInstant(),
			monday.atTime(11, 0).atZone(Slots.ZONE).toInstant());
	}

	@Test
	void 슬롯이_근무종료를_넘기면_만들지_않는다() {
		// 09:00~10:30에 45분 슬롯 → 09:00, 09:45. 10:30 자리는 11:15가 되어 넘친다.
		Coach c = coach(LocalTime.of(9, 0), LocalTime.of(10, 30), 45, DayOfWeek.MONDAY);
		LocalDate monday = LocalDate.of(2026, 8, 17);

		assertThat(Slots.forDate(c, monday)).hasSize(2);
	}

	@Test
	void 근무요일이_아니면_슬롯이_없다() {
		Coach c = coach(LocalTime.of(9, 0), LocalTime.of(18, 0), 60, DayOfWeek.MONDAY);
		LocalDate tuesday = LocalDate.of(2026, 8, 18);

		assertThat(Slots.forDate(c, tuesday)).isEmpty();
	}

	@Test
	void 자정_직전_근무는_되돌아가지_않는다() {
		// LocalTime.plusMinutes를 그대로 썼다면 23:00 + 60분 = 00:00이 되어
		// 종료조건이 무너지고 무한루프나 엉뚱한 슬롯이 생긴다.
		Coach c = coach(LocalTime.of(22, 0), LocalTime.of(23, 59), 60, DayOfWeek.MONDAY);
		LocalDate monday = LocalDate.of(2026, 8, 17);

		assertThat(Slots.forDate(c, monday)).containsExactly(
			monday.atTime(22, 0).atZone(Slots.ZONE).toInstant());
	}

	@Test
	void 슬롯이_근무시간보다_길면_비어_있다() {
		Coach c = coach(LocalTime.of(9, 0), LocalTime.of(9, 30), 60, DayOfWeek.MONDAY);
		assertThat(Slots.forDate(c, LocalDate.of(2026, 8, 17))).isEmpty();
	}
}
