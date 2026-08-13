package com.interview.coach2.reservation;

/** 전화번호를 숫자만 남겨 정규화한다. 같은 사람이 표기 차이로 갈리지 않게 하는 유일한 관문. */
public final class PhoneNumbers {

	private PhoneNumbers() {
	}

	public static String normalize(String raw) {
		if (raw == null) {
			return null;
		}
		String digits = raw.replaceAll("\\D", "");
		return digits.isEmpty() ? null : digits;
	}
}
