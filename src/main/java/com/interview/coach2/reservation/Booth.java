package com.interview.coach2.reservation;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 취업박람회의 부스 하나. 운영시간·슬롯길이는 관리자가 설정한다.
 *
 * 슬롯 테이블은 없다. 슬롯은 (행사일, 운영시작, 운영종료, 슬롯길이)에서 그때그때 계산한다 —
 * 미리 만들어두면 운영시간을 바꿀 때마다 미래 슬롯을 재생성해야 하고,
 * 그 재생성이 이미 잡힌 예약과 어긋날 수 있다.
 *
 * 행사일을 부스마다 들고 있는 이유: 하루짜리 행사라 값 하나면 충분한데, 그것만을 위해
 * Event 테이블을 만들 이유가 없다. 환경변수로 빼면 날짜를 바꿀 때마다 재배포해야 하고,
 * 부스에 두면 관리자 화면에서 바로 고칠 수 있으며 나중에 이틀짜리 행사도 그냥 된다.
 */
@Entity
@Getter
public class Booth {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 참가 기업명. 예약자가 부스를 고르는 기준. */
	@Column(nullable = false)
	private String companyName;

	/** 현장에서 찾아갈 표식. 예: "A-12". 예약 확인서에 그대로 박힌다. */
	@Column(nullable = false)
	private String boothNo;

	/** 모집 분야 등 한 줄 소개. 없어도 된다. */
	private String note;

	@Column(nullable = false)
	private LocalDate eventDate;

	@Column(nullable = false)
	private LocalTime openFrom;

	@Column(nullable = false)
	private LocalTime openTo;

	@Column(nullable = false)
	private int slotMinutes;

	@Column(nullable = false)
	private boolean active = true;

	protected Booth() {
	}

	public Booth(String companyName, String boothNo, String note,
	             LocalDate eventDate, LocalTime openFrom, LocalTime openTo, int slotMinutes) {
		if (companyName == null || companyName.isBlank()) {
			throw new IllegalArgumentException("회사명은 비어 있을 수 없다");
		}
		if (boothNo == null || boothNo.isBlank()) {
			throw new IllegalArgumentException("부스번호는 비어 있을 수 없다");
		}
		if (eventDate == null) {
			throw new IllegalArgumentException("행사일이 필요하다");
		}
		validateHours(openFrom, openTo, slotMinutes);

		this.companyName = companyName;
		this.boothNo = boothNo;
		this.note = note;
		this.eventDate = eventDate;
		this.openFrom = openFrom;
		this.openTo = openTo;
		this.slotMinutes = slotMinutes;
		this.active = true;
	}

	public void updateSchedule(LocalDate eventDate, LocalTime openFrom, LocalTime openTo, int slotMinutes) {
		if (eventDate == null) {
			throw new IllegalArgumentException("행사일이 필요하다");
		}
		validateHours(openFrom, openTo, slotMinutes);
		this.eventDate = eventDate;
		this.openFrom = openFrom;
		this.openTo = openTo;
		this.slotMinutes = slotMinutes;
	}

	public void updateInfo(String companyName, String boothNo, String note) {
		if (companyName == null || companyName.isBlank()) {
			throw new IllegalArgumentException("회사명은 비어 있을 수 없다");
		}
		if (boothNo == null || boothNo.isBlank()) {
			throw new IllegalArgumentException("부스번호는 비어 있을 수 없다");
		}
		this.companyName = companyName;
		this.boothNo = boothNo;
		this.note = note;
	}

	public void deactivate() {
		this.active = false;
	}

	public void activate() {
		this.active = true;
	}

	private static void validateHours(LocalTime from, LocalTime to, int slotMinutes) {
		if (from == null || to == null) {
			throw new IllegalArgumentException("운영시간이 필요하다");
		}
		if (!from.isBefore(to)) {
			throw new IllegalArgumentException("운영 시작은 종료보다 빨라야 한다");
		}
		if (slotMinutes <= 0) {
			throw new IllegalArgumentException("슬롯 길이는 양수여야 한다");
		}
	}
}
