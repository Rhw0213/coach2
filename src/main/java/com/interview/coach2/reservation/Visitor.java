package com.interview.coach2.reservation;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * 박람회 방문자(예약자). 로그인이 없으므로 정규화된 전화번호가 사람의 신원이고,
 * 불투명 토큰이 "내 예약" 링크의 열쇠다.
 *
 * 예약마다 토큰을 새로 발급하지 않고 사람 단위로 붙이는 이유가 두 가지다:
 * 한 번의 링크로 본인의 모든 부스 예약을 보여줄 수 있고, "같은 사람이 같은 시간에
 * 두 부스를 잡는 것"을 막으려면 어차피 안정적인 사람 식별자가 필요하다.
 */
@Entity
@Getter
public class Visitor {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	/** 숫자만 남긴 형태. 같은 사람이 010-1234-5678 / 01012345678로 갈리지 않게 한다. */
	@Column(nullable = false, unique = true)
	private String phone;

	@Column(nullable = false, unique = true)
	private String token;

	@Column(nullable = false)
	private Instant createdAt;

	protected Visitor() {
	}

	public Visitor(String name, String normalizedPhone) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("이름은 비어 있을 수 없다");
		}
		if (normalizedPhone == null || normalizedPhone.isBlank()) {
			throw new IllegalArgumentException("연락처는 비어 있을 수 없다");
		}
		this.name = name;
		this.phone = normalizedPhone;
		this.token = UUID.randomUUID().toString().replace("-", "");
		this.createdAt = Instant.now();
	}
}
