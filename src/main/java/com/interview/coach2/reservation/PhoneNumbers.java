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

	/**
	 * 화면에 되비출 때 쓴다. 개별 안내 링크는 문자로 돌아다니다 남의 손에 들어갈 수 있으므로,
	 * 그 링크를 연 화면이 번호 전체를 그대로 보여주면 링크 유출이 곧 연락처 유출이 된다.
	 * 본인이 자기 번호를 알아보는 데는 앞 세 자리와 뒤 네 자리면 충분하다.
	 */
	public static String mask(String normalized) {
		if (normalized == null || normalized.length() < 7) {
			return "****";
		}
		return normalized.substring(0, 3) + "****" + normalized.substring(normalized.length() - 4);
	}
}
