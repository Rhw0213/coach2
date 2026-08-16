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

	/**
	 * 엑셀 명단은 '이름 번호 학번' 순서인 경우가 흔하다. 예전에는 줄 끝에서부터 훑어
	 * 학번을 번호로 채갔고, 그 사람은 정확히 입력해도 명단과 안 맞아 영영 거절당했다.
	 */
	@Test
	void 학번이_뒤에_붙어도_휴대폰을_고른다() {
		ApprovalLines.Row row = one("홍길동 010-1234-5678 20211234");

		assertThat(row.ok()).isTrue();
		assertThat(row.phone()).isEqualTo("01012345678");
	}

	/** 학번이 이름에 들러붙으면 '홍길동20211234'가 저장되어 역시 본인 확인이 안 된다. */
	@Test
	void 숫자만_있는_조각은_이름에_넣지_않는다() {
		assertThat(one("홍길동 010-1234-5678 20211234").name()).isEqualTo("홍길동");
		assertThat(one("3 홍길동 01012345678").name()).isEqualTo("홍길동");
	}

	/** 엑셀에서 같은 번호가 두 열에 걸쳐 복사되는 일은 흔하다. 같은 값이면 하나로 본다. */
	@Test
	void 같은_번호가_두_번_적혀도_읽는다() {
		ApprovalLines.Row row = one("홍길동 010-1234-5678 01012345678");

		assertThat(row.ok()).isTrue();
		assertThat(row.phone()).isEqualTo("01012345678");
		assertThat(row.name()).isEqualTo("홍길동");
	}

	/** 서로 다른 휴대폰이 둘이면 어느 쪽이 본인인지 알 수 없다. 찍지 말고 관리자에게 돌려준다. */
	@Test
	void 서로_다른_휴대폰이_둘이면_읽지_못한_것으로_남는다() {
		assertThat(one("홍길동 010-1234-5678 010-9876-5432").ok()).isFalse();
	}

	/** 휴대폰 모양이 아니어도 흘리지 않는다 — 유선번호로 등록된 명단이 있을 수 있다. */
	@Test
	void 휴대폰_모양이_아니어도_여덟_자리_이상이면_읽는다() {
		ApprovalLines.Row row = one("홍길동 02-1234-5678");

		assertThat(row.ok()).isTrue();
		assertThat(row.phone()).isEqualTo("0212345678");
	}

	@Test
	void 널과_빈_문자열은_빈_목록이다() {
		assertThat(ApprovalLines.parse(null)).isEmpty();
		assertThat(ApprovalLines.parse("   \n  ")).isEmpty();
	}
}
