package com.interview.coach2.reservation;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 코치와 그 근무 조건. 가용 시간은 관리자가 일괄 설정한다.
 *
 * 슬롯 테이블은 없다. 슬롯은 (근무시작, 근무종료, 슬롯길이, 요일)에서 그때그때 계산한다 —
 * 미리 만들어두면 근무시간을 바꿀 때마다 미래 슬롯을 재생성해야 하고, 그 재생성이
 * 기존 예약과 어긋날 수 있다.
 */
@Entity
@Getter
public class Coach {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	/** 표시용 직함. 없어도 된다. */
	private String title;

	@Column(nullable = false)
	private LocalTime availableFrom;

	@Column(nullable = false)
	private LocalTime availableTo;

	@Column(nullable = false)
	private int slotMinutes;

	/** DayOfWeek 이름 CSV. 예: "MONDAY,TUESDAY". 별도 테이블을 만들 만큼의 값이 아니다. */
	@Column(nullable = false)
	private String availableDays;

	@Column(nullable = false)
	private boolean active = true;

	protected Coach() {
	}

	public Coach(String name, String title, LocalTime availableFrom, LocalTime availableTo,
	             int slotMinutes, Set<DayOfWeek> availableDays) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name은 비어 있을 수 없다");
		}
		if (slotMinutes <= 0) {
			throw new IllegalArgumentException("slotMinutes는 양수여야 한다");
		}
		if (!availableFrom.isBefore(availableTo)) {
			throw new IllegalArgumentException("availableFrom은 availableTo보다 빨라야 한다");
		}
		if (availableDays == null || availableDays.isEmpty()) {
			throw new IllegalArgumentException("availableDays는 하루 이상이어야 한다");
		}
		this.name = name;
		this.title = title;
		this.availableFrom = availableFrom;
		this.availableTo = availableTo;
		this.slotMinutes = slotMinutes;
		this.availableDays = availableDays.stream().map(Enum::name).collect(Collectors.joining(","));
		this.active = true;
	}

	public Set<DayOfWeek> availableDaySet() {
		return Arrays.stream(availableDays.split(","))
			.filter(s -> !s.isBlank())
			.map(DayOfWeek::valueOf)
			.collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
	}

	public void updateSchedule(LocalTime from, LocalTime to, int slotMinutes, Set<DayOfWeek> days) {
		if (!from.isBefore(to)) {
			throw new IllegalArgumentException("availableFrom은 availableTo보다 빨라야 한다");
		}
		if (slotMinutes <= 0) {
			throw new IllegalArgumentException("slotMinutes는 양수여야 한다");
		}
		if (days == null || days.isEmpty()) {
			throw new IllegalArgumentException("availableDays는 하루 이상이어야 한다");
		}
		this.availableFrom = from;
		this.availableTo = to;
		this.slotMinutes = slotMinutes;
		this.availableDays = days.stream().map(Enum::name).collect(Collectors.joining(","));
	}

	public void deactivate() {
		this.active = false;
	}

	public void activate() {
		this.active = true;
	}
}
