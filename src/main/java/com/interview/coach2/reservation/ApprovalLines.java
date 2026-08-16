package com.interview.coach2.reservation;

import java.util.ArrayList;
import java.util.List;

/**
 * 관리자가 붙여넣은 합격자 명단을 읽는다. 기업이 보내오는 명단은 엑셀에서 복사되므로
 * 구분자가 탭일 수도, 쉼표일 수도, 공백일 수도 있다. 한 줄에 이름과 번호가 있으면 받는다.
 *
 * 번호는 줄에서 '숫자만 남겼을 때 8자리 이상'인 마지막 조각으로 잡는다. 앞에서부터 찾으면
 * 사번이나 순번이 먼저 걸리고, 위치로 고정하면 '홍길동 010-…' 과 '010-… 홍길동' 중
 * 하나만 받게 된다. 나머지 조각을 이어붙인 것이 이름이다.
 */
final class ApprovalLines {

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
		int phoneAt = -1;
		String phone = null;
		for (int i = parts.length - 1; i >= 0; i--) {
			String digits = PhoneNumbers.normalize(parts[i]);
			if (digits != null && digits.length() >= 8) {
				phoneAt = i;
				phone = digits;
				break;
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
			if (i != phoneAt && !parts[i].isBlank()) {
				name.append(parts[i]);
			}
		}
		return new Row(name.isEmpty() ? null : name.toString(), phone, line);
	}
}
