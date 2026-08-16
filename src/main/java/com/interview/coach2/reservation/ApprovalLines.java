package com.interview.coach2.reservation;

import java.util.ArrayList;
import java.util.List;

/**
 * 관리자가 붙여넣은 합격자 명단을 읽는다. 기업이 보내오는 명단은 엑셀에서 복사되므로
 * 구분자가 탭일 수도, 쉼표일 수도, 공백일 수도 있다. 한 줄에 이름과 번호가 있으면 받는다.
 *
 * 번호는 '어디에 있느냐'가 아니라 '번호처럼 생겼느냐'로 고른다. 예전에는 줄 끝에서부터
 * 훑어 8자리 이상인 첫 조각을 번호로 잡았는데, 엑셀에서 흔한 '홍길동 010-1234-5678 20211234'
 * 순서에서는 학번이 번호로 채가진다. 그 사람은 정확히 입력해도 명단과 안 맞아 영영 거절당하고,
 * 관리자 화면에는 '추가됨'으로만 보여 원인을 알 수 없다.
 *
 * 그래서 휴대폰 모양(01로 시작하는 10~11자리)을 먼저 찾고, 없을 때만 옛 규칙으로 물러난다.
 * 휴대폰 모양이 둘 이상이면 찍지 않고 읽지 못한 줄로 돌려준다 — 잘못 넣는 것보다 낫다.
 *
 * 이름은 글자가 든 조각만 이어붙인다. 붙여쓰는 이유는 '홍 길동'처럼 성과 이름이 띄어진
 * 경우를 하나로 모으기 위해서다(예약할 때의 본인 확인도 공백을 무시한다).
 */
final class ApprovalLines {

	/** 글자가 하나도 없는 조각은 이름이 아니다 — 학번·사번·순번이 이름에 들러붙지 않게 한다. */
	private static final java.util.regex.Pattern HAS_LETTER = java.util.regex.Pattern.compile("\\p{L}");

	private ApprovalLines() {
	}

	/** 한 줄을 읽은 결과. phone이 null이면 읽지 못한 줄이고, raw를 그대로 돌려준다. */
	record Row(String name, String phone, String raw) {

		boolean ok() {
			return phone != null && name != null && !name.isBlank();
		}
	}

	static List<Row> parse(String text) {
		List<Row> rows = new ArrayList<>();
		if (text == null) {
			return rows;
		}
		for (String line : text.split("\\R")) {
			String trimmed = line.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			rows.add(parseLine(trimmed));
		}
		return rows;
	}

	private static Row parseLine(String line) {
		String[] parts = line.split("[,\\t]+|\\s{1,}");

		// 1) 휴대폰 모양부터 찾는다. 같은 번호가 두 열에 걸쳐 두 번 적힌 명단은 흔하므로
		//    값이 같으면 하나로 본다. 서로 다른 번호가 둘이면 어느 쪽인지 알 수 없다.
		int phoneAt = -1;
		String phone = null;
		for (int i = 0; i < parts.length; i++) {
			String digits = PhoneNumbers.normalize(parts[i]);
			if (!isMobile(digits)) {
				continue;
			}
			if (phone != null && !phone.equals(digits)) {
				return new Row(null, null, line);
			}
			if (phone == null) {
				phoneAt = i;
				phone = digits;
			}
		}

		// 2) 휴대폰 모양이 없으면 옛 규칙으로 물러난다 — 유선번호나 낯선 표기를 흘리지 않기 위해서다.
		if (phone == null) {
			for (int i = parts.length - 1; i >= 0; i--) {
				String digits = PhoneNumbers.normalize(parts[i]);
				if (digits != null && digits.length() >= 8) {
					phoneAt = i;
					phone = digits;
					break;
				}
			}
		}
		if (phone == null) {
			// 조각으로 못 찾았으면 줄 전체가 하이픈·공백이 섞인 번호일 수 있다.
			String whole = PhoneNumbers.normalize(line);
			return whole != null && whole.length() >= 8
				? new Row(null, whole, line)
				: new Row(null, null, line);
		}

		StringBuilder name = new StringBuilder();
		for (int i = 0; i < parts.length; i++) {
			if (i != phoneAt && HAS_LETTER.matcher(parts[i]).find()) {
				name.append(parts[i]);
			}
		}
		return new Row(name.isEmpty() ? null : name.toString(), phone, line);
	}

	private static boolean isMobile(String digits) {
		return digits != null && digits.length() >= 10 && digits.length() <= 11
			&& digits.startsWith("01");
	}
}
