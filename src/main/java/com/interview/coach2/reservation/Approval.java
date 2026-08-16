package com.interview.coach2.reservation;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * 서류 합격자. 이 사람만 해당 부스(그 기업의 1:1 면접)를 예약할 수 있다.
 *
 * 부스 단위로 잡는다. 합격은 '그 기업의 1:1 면접에 붙었다'는 뜻이고, 1:1 면접은 부스 하나이므로
 * 부스가 곧 그 자격의 범위다. 나중에 기업이 별도 개념이 되어도 이 부스는 그 기업에 속하므로
 * 의미가 흔들리지 않는다.
 *
 * 전화번호로 사람을 가리킨다. Visitor로 가리킬 수 없다 — 합격을 등록하는 시점에 그 사람은
 * 아직 예약을 한 적이 없어 Visitor 행이 존재하지 않는다. 껍데기 Visitor를 미리 만드는 것은
 * '예약한 사람'이라는 그 테이블의 뜻을 망가뜨린다.
 *
 * 토큰은 개별 안내 링크의 열쇠다. 이름·연락처만으로 예약하게 두면, 남의 이름과 번호를 아는
 * 사람이 예약을 시도해 보는 것만으로 그 사람의 서류 합격 여부를 알아낼 수 있다.
 */
@Entity
@Getter
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"boothId", "phone"}))
public class Approval {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long boothId;

	/** 숫자만 남긴 형태. Visitor.phone과 같은 규칙이어야 예약 때 사람이 이어진다. */
	@Column(nullable = false)
	private String phone;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false, unique = true)
	private String token;

	@Column(nullable = false)
	private Instant createdAt;

	protected Approval() {
	}

	public Approval(Long boothId, String name, String normalizedPhone) {
		if (boothId == null) {
			throw new IllegalArgumentException("부스가 필요하다");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("이름은 비어 있을 수 없다");
		}
		if (normalizedPhone == null || normalizedPhone.isBlank()) {
			throw new IllegalArgumentException("연락처는 비어 있을 수 없다");
		}
		this.boothId = boothId;
		this.name = name.trim();
		this.phone = normalizedPhone;
		this.token = UUID.randomUUID().toString().replace("-", "");
		this.createdAt = Instant.now();
	}

	public boolean isFor(Long booth) {
		return boothId.equals(booth);
	}
}
