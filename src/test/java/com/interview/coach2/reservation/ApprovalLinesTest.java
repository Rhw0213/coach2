package com.interview.coach2.reservation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기업이 보내오는 합격자 명단은 엑셀에서 복사된다. 구분자가 무엇이든, 이름과 번호의 순서가
 * 어느 쪽이든 읽혀야 한다 — 여기서 한 줄이라도 흘리면 그 사람은 면접을 예약하지 못한다.
 */
class ApprovalLinesTest {

	private static ApprovalLines.Row one(String line) {
		List<ApprovalLines.Row> rows = ApprovalLines.parse(line);
		assertThat(rows).hasSize(1);
		return rows.get(0);
	}

	@Test
	void 공백_쉼표_탭을_모두_구분자로_받는다() {
		for (String line : new String[]{
				"홍길동 010-1234-5678", "홍길동,010-1234-5678", "홍길동\t010-1234-5678"}) {
			ApprovalLines.Row row = one(line);
			assertThat(row.ok()).as(line).isTrue();
			assertThat(row.name()).isEqualTo("홍길동");
			assertThat(row.phone()).isEqualTo("01012345678");
		}
	}

	@Test
	void 번호가_앞에_와도_읽는다() {
		ApprovalLines.Row row = one("010-1234-5678, 홍길동");

		assertThat(row.name()).isEqualTo("홍길동");
		assertThat(row.phone()).isEqualTo("01012345678");
	}

	/** 이름에 공백이 있으면 조각이 셋이 된다. 번호가 아닌 조각을 모두 이름으로 본다. */
	@Test
	void 성과_이름이_띄어져_있어도_붙여서_읽는다() {
		assertThat(one("홍 길동 01012345678").name()).isEqualTo("홍길동");
	}

	@Test
	void 빈_줄은_건너뛴다() {
		assertThat(ApprovalLines.parse("홍길동 01012345678\n\n  \n김철수 01098765432")).hasSize(2);
	}

	@Test
	void 번호가_없는_줄은_읽지_못한_것으로_남는다() {
		ApprovalLines.Row row = one("홍길동");

		assertThat(row.ok()).isFalse();
		assertThat(row.raw()).isEqualTo("홍길동");
	}

	/** 이름 없이 번호만 있으면 누구인지 알 수 없다. 조용히 넣지 말고 관리자에게 돌려준다. */
	@Test
	void 이름이_없는_줄도_읽지_못한_것으로_남는다() {
		assertThat(one("010-1234-5678").ok()).isFalse();
	}

	/** 사번이나 짧은 숫자가 번호로 오인되면 엉뚱한 사람에게 링크가 나간다. */
	@Test
	void 여덟_자리_미만_숫자는_번호로_보지_않는다() {
		assertThat(one("홍길동 12345").ok()).isFalse();
	}

	@Test
	void 널과_빈_문자열은_빈_목록이다() {
		assertThat(ApprovalLines.parse(null)).isEmpty();
		assertThat(ApprovalLines.parse("   \n  ")).isEmpty();
	}
}
