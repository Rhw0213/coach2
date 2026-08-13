package com.interview.coach2.reservation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 엔티티가 스스로 던지는 IllegalArgumentException을 400으로 바꾼다.
 * 없으면 잘못된 입력이 500 + 스택트레이스로 나가서, 사용자에게도 불친절하고
 * 내부 구조도 드러난다. 검증을 컨트롤러에 한 번 더 복사하지 않기 위한 최소 장치다.
 */
@RestControllerAdvice
public class ValidationAdvice {

	@ExceptionHandler(IllegalArgumentException.class)
	public ProblemDetail onIllegalArgument(IllegalArgumentException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}
}
