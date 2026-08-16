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

	/**
	 * 한 슬롯이 받는 인원. 1이면 1:1 면접·상담, 5면 그룹면접처럼 여러 명이 같은 시간에 들어온다.
	 * 좌석 배정은 Reservation.seatNo가 맡고, 정원 초과는 DB 유니크 제약이 직접 막는다.
	 */
	// DEFAULT를 DDL에 직접 박는다. 그냥 NOT NULL로 두면 이미 행이 있는 운영 테이블에
	// ALTER ... ADD COLUMN ... NOT NULL 이 실패하는데, ddl-auto=update는 그 실패를
	// 로그만 남기고 넘어간다 — 컬럼 없이 뜬 앱은 헬스체크는 통과하면서 예약 쿼리만 깨진다.
	@Column(columnDefinition = "integer default 1 not null")
	private int capacity = 1;

	/**
	 * 면접·상담인가 기업 설명회인가. 설명회 시간표와 예약 화면이 서로 다른 목록을 그린다.
	 *
	 * 이 값이 생기기 전에 만들어진 부스는 전부 면접·상담이었으므로 그것을 기본값으로 둔다.
	 */
	// capacity·approvalRequired와 같은 이유로 DDL에 DEFAULT를 박는다 — 행이 있는 테이블에서
	// ADD COLUMN NOT NULL 이 실패해도 ddl-auto=update는 로그만 남기고 넘어간다.
	@Enumerated(EnumType.STRING)
	@Column(columnDefinition = "varchar(20) default 'INTERVIEW' not null")
	private BoothKind kind = BoothKind.INTERVIEW;

	@Column(nullable = false)
	private boolean active = true;

	/**
	 * 켜면 서류 합격자만 예약할 수 있다(1:1 면접). 끄면 누구나(그룹 상담).
	 *
	 * 정원으로 대신 판단하지 않는다. 정원 1이 곧 1:1이긴 하지만, '몇 명이 들어오나'와
	 * '아무나 들어와도 되나'는 다른 질문이다. 나중에 합격자만 받는 그룹 상담이 생겨도
	 * 이 값 하나만 켜면 된다.
	 */
	// capacity와 같은 이유로 DEFAULT를 DDL에 박는다 — 행이 있는 운영 테이블에서
	// ADD COLUMN NOT NULL 이 실패해도 ddl-auto=update는 로그만 남기고 넘어간다.
	@Column(columnDefinition = "boolean default false not null")
	private boolean approvalRequired = false;

	/**
	 * 부스 담당자용 링크의 열쇠. 주최측이 관리자 화면에서 복사해 각 참가 기업에 전달한다.
	 * 이 토큰으로는 자기 부스의 예약만 볼 수 있고 설정은 바꿀 수 없다 —
	 * 담당자에게 ADMIN_SECRET을 주면 남의 기업 예약자 연락처까지 전부 열린다.
	 * 공개 응답(BoothView)에는 절대 실리지 않는다.
	 */
	@Column(unique = true)
	private String staffToken;

	/**
	 * 기업 설명회는 인원 제한이 없다. 여럿이 함께 듣는 ZOOM 웨비나라 좌석이라는 것이 없고,
	 * 요청도 '무제한'이다. capacity 값은 남아 있지만 설명회에서는 아무도 읽지 않는다.
	 *
	 * ponytail: 부스마다 켜고 끄는 스위치를 따로 두지 않고 종류로 판단한다. 설명회 중
	 * 하나만 정원을 걸고 싶어지면 그때 컬럼을 하나 넣으면 된다 — 지금 필요한 규칙은
	 * '설명회는 무제한' 한 줄뿐이고, 그 한 줄을 두 곳에 적지 않는 것이 더 중요하다.
	 */
	public boolean isUnlimited() {
		return kind == BoothKind.BRIEFING;
	}

	protected Booth() {
	}

	/** 정원을 안 주면 1:1이다 — 이 시스템이 처음부터 받아온 형태이므로 그것을 기본값으로 둔다. */
	public Booth(String companyName, String boothNo, String note,
	             LocalDate eventDate, LocalTime openFrom, LocalTime openTo, int slotMinutes) {
		this(companyName, boothNo, note, eventDate, openFrom, openTo, slotMinutes, 1);
	}

	public Booth(String companyName, String boothNo, String note,
	             LocalDate eventDate, LocalTime openFrom, LocalTime openTo,
	             int slotMinutes, int capacity) {
		if (eventDate == null) {
			throw new IllegalArgumentException("행사일이 필요하다");
		}
		validateHours(openFrom, openTo, slotMinutes);
		validateCapacity(capacity);

		this.companyName = required(companyName, "회사명은 비어 있을 수 없다");
		this.boothNo = required(boothNo, "부스번호는 비어 있을 수 없다");
		this.note = trimToNull(note);
		this.eventDate = eventDate;
		this.openFrom = openFrom;
		this.openTo = openTo;
		this.slotMinutes = slotMinutes;
		this.capacity = capacity;
		this.active = true;
		this.staffToken = newToken();
	}

	/** 이 기능이 생기기 전에 만들어진 부스를 위한 보충. 관리자가 링크를 보러 올 때 채운다. */
	public void ensureStaffToken() {
		if (staffToken == null || staffToken.isBlank()) {
			this.staffToken = newToken();
		}
	}

	private static String newToken() {
		return java.util.UUID.randomUUID().toString().replace("-", "");
	}

	public void updateSchedule(LocalDate eventDate, LocalTime openFrom, LocalTime openTo,
	                          int slotMinutes, int capacity) {
		if (eventDate == null) {
			throw new IllegalArgumentException("행사일이 필요하다");
		}
		validateHours(openFrom, openTo, slotMinutes);
		validateCapacity(capacity);
		this.eventDate = eventDate;
		this.openFrom = openFrom;
		this.openTo = openTo;
		this.slotMinutes = slotMinutes;
		// 이미 그 정원만큼 찬 슬롯이 있어도 줄이는 것을 막지 않는다. 기존 예약은 그대로 두고
		// 새 예약만 막히는 편이, 이미 확정된 사람을 밀어내는 것보다 낫다.
		this.capacity = capacity;
	}

	/** 이미 잡힌 예약은 건드리지 않는다. 켜는 순간부터 새 예약만 합격자로 제한된다. */
	public void setApprovalRequired(boolean required) {
		this.approvalRequired = required;
	}

	public void setKind(BoothKind kind) {
		if (kind == null) {
			throw new IllegalArgumentException("부스 종류가 필요하다");
		}
		this.kind = kind;
	}

	public void updateInfo(String companyName, String boothNo, String note) {
		this.companyName = required(companyName, "회사명은 비어 있을 수 없다");
		this.boothNo = required(boothNo, "부스번호는 비어 있을 수 없다");
		this.note = trimToNull(note);
	}

	public void deactivate() {
		this.active = false;
	}

	public void activate() {
		this.active = true;
	}

	/**
	 * 앞뒤 공백을 떼고 받는다. 관리자가 실수로 " a2"를 입력하면 부스번호 정렬이
	 * 무너지고(공백이 영문자보다 작다) 예약 확인서에도 공백이 그대로 찍힌다.
	 * 화면에서 막을 수도 있지만, 값이 들어오는 문은 API 하나뿐이므로 여기서 막는다.
	 */
	private static String required(String value, String message) {
		String trimmed = value == null ? "" : value.trim();
		if (trimmed.isEmpty()) {
			throw new IllegalArgumentException(message);
		}
		return trimmed;
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
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

	private static void validateCapacity(int capacity) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("정원은 1명 이상이어야 한다");
		}
	}
}
