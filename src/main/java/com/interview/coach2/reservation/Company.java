package com.interview.coach2.reservation;

import jakarta.persistence.*;
import lombok.Getter;

/**
 * 참여 기업의 소개·채용정보(JD). 학생이 기업을 고르는 근거가 되는 정보만 담는다.
 *
 * 부스(면접·상담 세션)와는 회사명으로 잇는다. 한 기업이 1:1 면접과 그룹 상담을 따로 열면
 * 부스가 둘이 되는데, 그 둘이 같은 기업임을 아는 곳이 여기다.
 *
 * ponytail: 외래키가 아니라 이름 문자열로 잇는다. 부스에서 companyName을 떼어내 FK로 바꾸는 것이
 * 정석이지만, 그러면 Booth 생성자를 쓰는 테스트 서른 곳과 공개·관리자·담당자 뷰를 한꺼번에
 * 고쳐야 한다 — 행사를 아흐레 앞두고 실사용자가 들어오기 직전에 할 수술이 아니다.
 * 문자열 키의 약점은 두 가지로 막는다: 관리자 화면이 기업을 드롭다운으로만 고르게 하고(오타 차단),
 * 개명하면 그 이름을 쓰던 부스를 함께 갱신한다(끊김 차단). 행사가 끝나면 FK로 올린다.
 *
 * 담당자 이름·연락처·이메일은 일부러 두지 않는다. 학생이 보는 화면에 기업 담당자의
 * 개인 연락처가 실릴 이유가 없고, 두면 언젠가 공개 응답에 딸려 나간다.
 */
@Entity
@Getter
public class Company {

	private static final int LONG_TEXT = 4000;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 부스의 companyName과 글자 그대로 같아야 이어진다. */
	@Column(nullable = false, unique = true)
	private String name;

	/** 대기업 · 중견기업 · 강소기업 · 외국계기업 · 연구기관 등. 목록에서 한눈에 훑는 기준이다. */
	private String category;

	/** 목록 카드에 실리는 한두 줄. 상세의 about과 다르다 — 카드에 긴 글을 실으면 목록이 무너진다. */
	@Column(length = LONG_TEXT)
	private String summary;

	/** 기업 소개 원문. 제출 양식의 '기업 소개' 칸 그대로다. */
	@Column(length = LONG_TEXT)
	private String about;

	private String industry;
	private String founded;
	private String revenue;
	private String employees;
	private String homepage;
	private String headcount;

	/** 고용 형태·근무지·근무 형태·급여·전형 절차·접수 기간. 칸을 여섯 개로 쪼개는 대신 한 덩이로 받는다 —
	 *  기업마다 비는 칸이 달라서, 쪼개면 관리자 화면만 길어지고 화면에는 빈 제목이 남는다. */
	@Column(length = LONG_TEXT)
	private String hiring;

	@Column(length = LONG_TEXT)
	private String roles;

	@Column(length = LONG_TEXT)
	private String duties;

	@Column(length = LONG_TEXT)
	private String requirements;

	@Column(length = LONG_TEXT)
	private String majors;

	@Column(length = LONG_TEXT)
	private String benefits;

	@Column(length = LONG_TEXT)
	private String talent;

	@Column(nullable = false)
	private boolean active = true;

	protected Company() {
	}

	public Company(String name) {
		this.name = required(name);
		this.active = true;
	}

	/** 개명. 부스를 함께 갱신하는 것은 호출부(AdminController)의 몫이다 — 여기서는 이름만 바꾼다. */
	public String rename(String newName) {
		String previous = this.name;
		this.name = required(newName);
		return previous;
	}

	/**
	 * JD 항목 묶음. 문자열 열여섯 개를 자리로 넘기면 두 개가 뒤바뀌어도 컴파일러가 잡지 못한다 —
	 * 이름 붙은 한 덩이로 받고, 각 칸이 제자리에 들어가는지는 테스트가 못박는다.
	 */
	public record Profile(String category, String summary, String about, String industry,
	                      String founded, String revenue, String employees, String homepage,
	                      String headcount, String hiring, String roles, String duties,
	                      String requirements, String majors, String benefits, String talent) {
	}

	/**
	 * JD 항목을 한꺼번에 덮어쓴다. 자료가 아직 '추후 전달'인 칸이 많아 부분 수정이 잦으므로,
	 * 화면은 늘 전체를 보내고 서버는 받은 그대로 저장한다 — 안 보낸 칸이 비워지는 사고를 막는다.
	 */
	public void updateProfile(Profile p) {
		this.category = trimToNull(p.category());
		this.summary = trimToNull(p.summary());
		this.about = trimToNull(p.about());
		this.industry = trimToNull(p.industry());
		this.founded = trimToNull(p.founded());
		this.revenue = trimToNull(p.revenue());
		this.employees = trimToNull(p.employees());
		this.homepage = trimToNull(p.homepage());
		this.headcount = trimToNull(p.headcount());
		this.hiring = trimToNull(p.hiring());
		this.roles = trimToNull(p.roles());
		this.duties = trimToNull(p.duties());
		this.requirements = trimToNull(p.requirements());
		this.majors = trimToNull(p.majors());
		this.benefits = trimToNull(p.benefits());
		this.talent = trimToNull(p.talent());
	}

	public void setActive(boolean active) {
		this.active = active;
	}

	private static String required(String value) {
		String trimmed = value == null ? "" : value.trim();
		if (trimmed.isEmpty()) {
			throw new IllegalArgumentException("회사명은 비어 있을 수 없다");
		}
		return trimmed;
	}

	/**
	 * 빈 칸은 null로 눕힌다. ""와 null이 섞이면 화면이 '값이 있는데 비었다'와 '아직 안 받았다'를
	 * 구분하지 못해, 아무것도 없는 항목 제목만 줄줄이 그리게 된다.
	 */
	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
