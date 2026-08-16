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

	/*
	 * 학교·전공·학년 구분. 기업 담당자가 명단에서 읽는 값이라 예약과 함께 받는다.
	 *
	 * nullable이다. 운영 DB는 SPRING_JPA_HIBERNATE_DDL_AUTO=update로 굴러가는데,
	 * 이미 행이 있는 테이블에 NOT NULL 컬럼을 붙이면 ALTER가 실패하고 컬럼이 아예
	 * 안 생긴다. 필수 여부는 ReservationService가 강제한다 — 화면에서 오는 모든
	 * 예약은 셋 다 채워져야 통과한다. 비어 있는 행은 이 기능 이전에 잡힌 예약뿐이다.
	 */
	@Column(length = 60)
	private String school;

	@Column(length = 60)
	private String major;

	/** '3학년', '졸업생' 같은 표시 문자열. 화면의 선택지가 그대로 들어온다. */
	@Column(length = 20)
	private String standing;

	@Column(nullable = false)
	private Instant createdAt;

	protected Visitor() {
	}

	public Visitor(String name, String normalizedPhone) {
		this(name, normalizedPhone, null, null, null);
	}

	public Visitor(String name, String normalizedPhone, String school, String major, String standing) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("이름은 비어 있을 수 없다");
		}
		if (normalizedPhone == null || normalizedPhone.isBlank()) {
			throw new IllegalArgumentException("연락처는 비어 있을 수 없다");
		}
		this.name = name;
		this.phone = normalizedPhone;
		this.school = school;
		this.major = major;
		this.standing = standing;
		this.token = UUID.randomUUID().toString().replace("-", "");
		this.createdAt = Instant.now();
	}

	/**
	 * 신청서에 다시 적은 소속으로 맞춘다. 사람은 번호로 찾으므로, 만들 때만 넣으면
	 * 이 항목이 생기기 전에 예약한 적 있는 사람은 다시 채워 넣어도 영영 빈 채로 남는다.
	 *
	 * 나중에 적은 것이 이긴다. 학년이 바뀌었거나 오타를 고쳤을 수 있고, 담당자가 볼 값은
	 * 그 사람이 마지막으로 말한 값이다. 이름은 그대로 둔다 — 본인 확인에 쓰는 값이라
	 * 두 번째 예약에서 바뀌면 첫 예약을 자기 이름으로 찾지 못하게 된다.
	 */
	void updateProfile(String school, String major, String standing) {
		this.school = school;
		this.major = major;
		this.standing = standing;
	}
}
