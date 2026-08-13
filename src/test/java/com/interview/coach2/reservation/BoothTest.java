package com.interview.coach2.reservation;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoothTest {

	private static final LocalDate DAY = LocalDate.of(2026, 9, 15);

	private static Booth booth(String companyName, String boothNo, String note) {
		return new Booth(companyName, boothNo, note, DAY,
			LocalTime.of(10, 0), LocalTime.of(17, 0), 30);
	}

	@Test
	void 앞뒤_공백을_떼고_저장한다() {
		// 운영에서 실제로 "  a2"가 들어와 부스번호 정렬이 뒤집혔다
		// (공백이 영문자보다 작아 a2가 a1보다 앞섰다).
		Booth b = booth("  네이버 ", "  a2", "  백엔드 모집  ");

		assertThat(b.getCompanyName()).isEqualTo("네이버");
		assertThat(b.getBoothNo()).isEqualTo("a2");
		assertThat(b.getNote()).isEqualTo("백엔드 모집");
	}

	@Test
	void 공백만_있는_값은_비어_있는_것으로_본다() {
		assertThatThrownBy(() -> booth("   ", "a1", null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("회사명은 비어 있을 수 없다");

		assertThatThrownBy(() -> booth("네이버", "  ", null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("부스번호는 비어 있을 수 없다");
	}

	@Test
	void 빈_소개는_null로_저장한다() {
		// ""와 null이 섞이면 화면에서 빈 줄이 생긴다
		assertThat(booth("네이버", "a1", "   ").getNote()).isNull();
		assertThat(booth("네이버", "a1", null).getNote()).isNull();
	}

	@Test
	void 수정할_때도_공백을_뗀다() {
		Booth b = booth("네이버", "a1", null);

		b.updateInfo(" 카카오 ", " b7 ", " 프론트엔드 ");

		assertThat(b.getCompanyName()).isEqualTo("카카오");
		assertThat(b.getBoothNo()).isEqualTo("b7");
		assertThat(b.getNote()).isEqualTo("프론트엔드");
	}

	@Test
	void 운영시간이_뒤집히면_거부한다() {
		assertThatThrownBy(() -> new Booth("네이버", "a1", null, DAY,
			LocalTime.of(17, 0), LocalTime.of(10, 0), 30))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("운영 시작은 종료보다 빨라야 한다");
	}
}
