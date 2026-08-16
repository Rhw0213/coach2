package com.interview.coach2.reservation;

/**
 * 부스가 여는 자리의 종류.
 *
 * 정원·합격자 제한과는 다른 축이다. 정원은 '한 시간대에 몇 명', approvalRequired는
 * '아무나 들어와도 되나'를 말하고, 이것은 '무엇을 하는 자리인가'를 말한다.
 * 기업 설명회 시간표와 면접·상담 예약 화면이 서로 다른 목록을 그려야 하므로 필요하다.
 */
public enum BoothKind {

	/** 1:1 면접·상담, 그룹 상담. 예약 화면과 안내 페이지 시간표가 다루는 것. */
	INTERVIEW,

	/** 기업 설명회. 기업마다 정해진 한 시간대에 여럿이 함께 듣는다. */
	BRIEFING
}
