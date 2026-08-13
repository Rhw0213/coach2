package com.interview.coach2.reservation;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** 코치의 근무설정에서 특정 날짜의 슬롯 시작시각을 계산한다. 저장하지 않고 매번 계산한다. */
public final class Slots {

	public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	private Slots() {
	}

	public static List<Instant> forDate(Coach coach, LocalDate date) {
		if (!coach.availableDaySet().contains(date.getDayOfWeek())) {
			return List.of();
		}
		// LocalTime.plusMinutes는 자정을 넘기면 되돌아온다(23:30 + 60분 = 00:30).
		// 그대로 루프 조건에 쓰면 종료되지 않거나 엉뚱한 슬롯이 생기므로
		// 자정 기준 '분' 정수로 계산한다.
		int startMinute = coach.getAvailableFrom().toSecondOfDay() / 60;
		int endMinute = coach.getAvailableTo().toSecondOfDay() / 60;
		int slot = coach.getSlotMinutes();

		List<Instant> out = new ArrayList<>();
		for (int m = startMinute; m + slot <= endMinute; m += slot) {
			out.add(date.atTime(LocalTime.ofSecondOfDay(m * 60L)).atZone(ZONE).toInstant());
		}
		return out;
	}

	public static LocalDate today() {
		return LocalDate.now(ZONE);
	}

	public static Instant startOfDay(LocalDate date) {
		return date.atStartOfDay(ZONE).toInstant();
	}

	public static Instant endOfDay(LocalDate date) {
		return date.plusDays(1).atStartOfDay(ZONE).toInstant();
	}
}
