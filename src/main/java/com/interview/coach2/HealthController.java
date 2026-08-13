package com.interview.coach2;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 컨테이너 healthcheck와 nginx 경로 연결을 확인하는 최소 엔드포인트.
 * 예약 도메인이 붙기 전에 배포 파이프라인(빌드 → 컨테이너 → nginx → HTTPS)이
 * 실제로 뚫리는지 검증하는 용도다.
 *
 * context-path가 /reserve 이므로 외부에서는 https://eastpeace.kr/reserve/health 로 보인다.
 */
@RestController
public class HealthController {

	@GetMapping("/health")
	public Map<String, String> health() {
		return Map.of("status", "ok", "app", "coach2");
	}
}
