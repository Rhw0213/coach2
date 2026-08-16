package com.interview.coach2.reservation;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;

/**
 * 한 부스의 한 시간슬롯에 대한 예약. 부스는 슬롯당 1명을 받는다.
 *
 * ── 중복예약을 막는 방식 ──────────────────────────────────────────
 * 애플리케이션 검사만으로는 동시 요청을 막지 못한다(검사와 저장 사이가 벌어진다).
 * 그래서 DB 유니크 제약이 진짜 방어선이고, 서비스의 사전 검사는 흔한 경우에
 * 친절한 메시지를 주기 위한 것일 뿐이다.
 *
 * 정원이 여러 명인 부스(그룹면접 5명)도 같은 방식으로 막는다. 좌석 번호를 키에 넣어
 * 부스@시각(#좌석) 을 유니크로 두면, '세고 나서 넣는' 경합 자체가 생기지 않는다 —
 * 서비스는 1번 좌석부터 넣어 보고 제약에 막히면 다음 번호로 넘어갈 뿐이고,
 * 좌석 문자열이 정원 개수만큼만 존재하므로 정원 초과는 타이밍과 무관하게 불가능하다.
 * 부모 행 비관락을 쓰면 이 성질을 애플리케이션 코드로 옮겨오게 되어, 락 로직에 버그가
 * 나는 순간 DB가 받아줄 최후의 방어선이 사라진다. 그래서 락을 쓰지 않았다.
 *
 * 제약을 '부분 유니크 인덱스(WHERE status=RESERVED)'로 만들지 않았다. 그건 Postgres
 * 전용이라 부팅 시 raw JDBC로 따로 만들어야 하고, H2로 도는 테스트에서는 조용히
 * 사라져서 정작 가장 중요한 로직이 검증되지 않는다.
 *
 * 대신 취소하면 NULL이 되는 슬롯키 컬럼에 평범한 유니크 제약을 건다.
 * Postgres도 H2도 유니크 인덱스 안의 NULL끼리는 충돌하지 않으므로, 취소된 예약은
 * 서로 몇 건이든 공존하면서 슬롯을 놓아준다.
 */
@Entity
@Getter
public class Reservation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long boothId;

	@Column(nullable = false)
	private Long visitorId;

	@Column(nullable = false)
	private Instant startTime;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ReservationStatus status;

	/**
	 * 예약 시점의 부스 슬롯길이를 복사해둔다. 부스 운영설정이 나중에 바뀌어도
	 * 이미 잡힌 예약의 길이가 소급해서 달라지면 안 된다.
	 */
	@Column(nullable = false)
	private int slotMinutes;

	@Column(nullable = false)
	private Instant createdAt;

	private Instant cancelledAt;

	/**
	 * 이 예약이 차지한 좌석 번호(1..부스 정원). 정원이 1이면 항상 1이다.
	 * 취소해도 남겨둔다 — 누가 몇 번 좌석이었는지는 기록이고, 슬롯을 놓아주는 것은 키의 몫이다.
	 *
	 * 0은 좌석이라는 개념이 없다는 뜻이다 — 인원 제한이 없는 기업 설명회.
	 */
	@Column(columnDefinition = "integer default 1 not null")
	private int seatNo;

	/** 한 부스의 한 슬롯의 한 좌석은 한 건만. 취소하면 NULL이 되어 그 좌석이 풀린다. */
	@Column(unique = true)
	private String boothSlotKey;

	/** 한 사람은 같은 시각에 한 건만 — 여러 부스를 동시에 잡는 것을 막는다. */
	@Column(unique = true)
	private String visitorSlotKey;

	/**
	 * 한 사람은 한 부스를 한 번만 — 같은 기업 시간대를 여러 개 선점하는 것을 막는다.
	 * 여러 기업을 도는 것은 허용된다(부스가 다르면 키도 다르다).
	 */
	@Column(unique = true)
	private String visitorBoothKey;

	protected Reservation() {
	}

	public Reservation(Long boothId, Long visitorId, Instant startTime, int slotMinutes, int seatNo) {
		this.boothId = boothId;
		this.visitorId = visitorId;
		this.startTime = startTime;
		this.slotMinutes = slotMinutes;
		this.seatNo = seatNo;
		this.status = ReservationStatus.RESERVED;
		this.createdAt = Instant.now();
		// 좌석 0 = 인원 제한 없음. 키를 NULL로 두면 이 슬롯에 몇 건이 들어와도 서로 부딪히지
		// 않는다(유니크 인덱스 안의 NULL끼리는 충돌하지 않는다). 사람 쪽 두 키는 그대로
		// 살아 있어서 같은 사람이 두 번 신청하는 것은 여전히 막힌다.
		//
		// 1번 좌석은 좌석번호를 붙이지 않는다 — 이 기능이 생기기 전에 쓰던 키와 글자 그대로 같다.
		// 덕분에 기존 예약 행을 손대지 않아도 되고(마이그레이션 없음), 코드를 되돌려도
		// 옛 코드가 만드는 키와 충돌해 이중예약이 막힌다.
		this.boothSlotKey = seatNo == 0 ? null
			: seatNo == 1 ? slotKey(boothId, startTime)
			: slotKey(boothId, startTime) + "#" + seatNo;
		this.visitorSlotKey = slotKey(visitorId, startTime);
		this.visitorBoothKey = visitorId + "@b" + boothId;
	}

	/** 취소는 되돌릴 수 없다. 다시 잡으려면 새 예약을 만든다. */
	public void cancel() {
		if (status == ReservationStatus.CANCELLED) {
			return;
		}
		this.status = ReservationStatus.CANCELLED;
		this.cancelledAt = Instant.now();
		// 세 키를 모두 NULL로 만들어야 슬롯이 실제로 풀린다. 상태만 바꾸면 유니크 제약이
		// 그대로 남아 같은 슬롯을 아무도 다시 예약하지 못하고, 취소한 본인도
		// 그 부스를 다시 잡지 못한다.
		this.boothSlotKey = null;
		this.visitorSlotKey = null;
		this.visitorBoothKey = null;
	}

	public boolean isOwnedBy(Long visitorId) {
		return this.visitorId.equals(visitorId);
	}

	private static String slotKey(Long ownerId, Instant startTime) {
		return ownerId + "@" + startTime.toEpochMilli();
	}
}
