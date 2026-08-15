package com.interview.coach2.reservation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 서버가 정한 한국어 사유를 응답 본문에 실어 보낸다.
 *
 * 이게 없으면 ResponseStatusException은 스프링 기본 경로(sendError → BasicErrorController)를 타고
 * {timestamp, status, error, path} 로 나간다. 사유가 어디에도 담기지 않아서, 화면은
 * "이미 예약된 시간입니다" 대신 "요청에 실패했습니다 (409)" 같은 껍데기만 보여준다.
 * spring.mvc.problemdetails.enabled 설정으로도 켤 수 있지만, 설정은 환경마다 갈리고
 * 테스트가 운영과 다른 모양을 검증하게 된다. 코드로 두면 테스트가 그대로 운영을 증명한다.
 */
@RestControllerAdvice
public class ValidationAdvice {

	// 실패를 남기지 않으면 행사가 끝난 뒤 '몇 명이 마감으로 되돌아갔는지'를 알 방법이 없다.
	// 본문(연락처·이름)은 찍지 않는다 — 로그에 개인정보를 흘리지 않기 위해서다.
	private static final Logger log = LoggerFactory.getLogger(ValidationAdvice.class);

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<ProblemDetail> onResponseStatus(ResponseStatusException e) {
		log.warn("요청 거절 {} — {}", e.getStatusCode(), e.getReason());
		// getBody()의 detail에는 생성 시 넘긴 reason이 들어 있다.
		return ResponseEntity.status(e.getStatusCode()).body(e.getBody());
	}

	/**
	 * 엔티티가 스스로 던지는 IllegalArgumentException을 400으로 바꾼다.
	 * 없으면 잘못된 입력이 500 + 스택트레이스로 나가서, 사용자에게도 불친절하고
	 * 내부 구조도 드러난다. 검증을 컨트롤러에 한 번 더 복사하지 않기 위한 최소 장치다.
	 */
	@ExceptionHandler(IllegalArgumentException.class)
	public ProblemDetail onIllegalArgument(IllegalArgumentException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}
}
