package com.interview.coach2.reservation;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;

/**
 * 한 코치의 한 시간슬롯에 대한 예약.
 *
 * ── 중복예약을 막는 방식 ──────────────────────────────────────────
 * 애플리케이션 검사만으로는 동시 요청을 막지 못한다(검사와 저장 사이가 벌어진다).
 * 그래서 DB 유니크 제약이 진짜 방어선이고, 서비스의 사전 검사는 흔한 경우에
 * 친절한 메시지를 주기 위한 것일 뿐이다.
 *
 * 제약을 '부분 유니크 인덱스(WHERE status=RESERVED)'로 만들지 않았다. 그건 Postgres
 * 전용이라 부팅 시 raw JDBC로 따로 만들어야 하고, H2로 도는 테스트에서는 조용히
 * 사라져서 정작 가장 중요한 로직이 검증되지 않는다.
 *
 * 대신 취소하면 NULL이 되는 슬롯키 컬럼에 평범한 유니크 제약을 건다.
 * Postgres도 H2도 유니크 인덱스 안의 NULL끼리는 충돌하지 않으므로, 취소된 예약은
 * 서로 몇 건이든 공존하면서 슬롯을 놓아준다. ddl-auto가 그대로 만들어주고,
 * 테스트에서도 동일하게 동작한다.
 */
@Entity
@Getter
public class Reservation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long coachId;

	@Column(nullable = false)
	private Long customerId;

	@Column(nullable = false)
	private Instant startTime;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ReservationStatus status;

	/**
	 * 예약 시점의 코치 슬롯길이를 복사해둔다. 코치의 근무설정이 나중에 바뀌어도
	 * 이미 잡힌 예약의 길이가 소급해서 달라지면 안 된다.
	 */
	@Column(nullable = false)
	private int slotMinutes;

	@Column(nullable = false)
	private Instant createdAt;

	private Instant cancelledAt;

	/** 한 코치의 한 슬롯은 한 건만. 취소하면 NULL이 되어 슬롯이 풀린다. */
	@Column(unique = true)
	private String coachSlotKey;

	/** 한 사람은 같은 시각에 한 건만 — 여러 코치를 동시에 잡는 것을 막는다. */
	@Column(unique = true)
	private String customerSlotKey;

	protected Reservation() {
	}

	public Reservation(Long coachId, Long customerId, Instant startTime, int slotMinutes) {
		this.coachId = coachId;
		this.customerId = customerId;
		this.startTime = startTime;
		this.slotMinutes = slotMinutes;
		this.status = ReservationStatus.RESERVED;
		this.createdAt = Instant.now();
		this.coachSlotKey = slotKey(coachId, startTime);
		this.customerSlotKey = slotKey(customerId, startTime);
	}

	/** 취소는 되돌릴 수 없다. 다시 잡으려면 새 예약을 만든다. */
	public void cancel() {
		if (status == ReservationStatus.CANCELLED) {
			return;
		}
		this.status = ReservationStatus.CANCELLED;
		this.cancelledAt = Instant.now();
		// 두 키를 NULL로 만들어야 슬롯이 실제로 풀린다. 상태만 바꾸면 유니크 제약이
		// 그대로 남아 같은 슬롯을 아무도 다시 예약하지 못한다.
		this.coachSlotKey = null;
		this.customerSlotKey = null;
	}

	public boolean isOwnedBy(Long customerId) {
		return this.customerId.equals(customerId);
	}

	private static String slotKey(Long ownerId, Instant startTime) {
		return ownerId + "@" + startTime.toEpochMilli();
	}
}
