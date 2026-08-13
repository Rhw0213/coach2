package com.interview.coach2.reservation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * /api/admin/** 의 유일한 관문.
 *
 * 시크릿에 기본값을 주지 않는다 — ADMIN_SECRET을 주입하지 않으면 앱이 아예 기동하지 않는다.
 * 기본값을 두면 배포에서 빠뜨렸을 때 관리자 API가 알려진 값으로 열린 채 조용히 뜬다.
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

	public static final String HEADER = "X-Admin-Secret";

	private final byte[] expected;

	public AdminAuthInterceptor(@Value("${admin.secret}") String secret) {
		if (secret == null || secret.isBlank()) {
			throw new IllegalStateException("admin.secret(ADMIN_SECRET)이 비어 있다");
		}
		this.expected = secret.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		String provided = request.getHeader(HEADER);
		// 길이·내용 비교 모두 상수시간으로 — 문자열 == 비교는 앞자리부터 틀리는 위치가
		// 응답시간에 드러나 시크릿을 한 글자씩 맞춰볼 수 있다.
		if (provided != null
				&& MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expected)) {
			return true;
		}
		response.setStatus(HttpStatus.UNAUTHORIZED.value());
		return false;
	}
}
