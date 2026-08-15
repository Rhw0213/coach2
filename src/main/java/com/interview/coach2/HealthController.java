package com.interview.coach2;

import com.interview.coach2.reservation.BoothRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 컨테이너 healthcheck와 nginx 경로 연결을 확인하는 최소 엔드포인트.
 * context-path가 /reserve 이므로 외부에서는 https://eastpeace.kr/reserve/health 로 보인다.
 *
 * DB를 한 번 왕복한다. 상수만 돌려주면 '앱은 떴지만 DB가 없거나 스키마가 어긋난' 상태를
 * 통과시켜 버린다 — ddl-auto=update가 ALTER에 실패해도 로그만 남기고 넘어가므로
 * 실제로 일어날 수 있는 시나리오다. 그 경우 배포는 초록불인데 첫 사용자 요청이 500으로
 * 터지고, 그때야 문제를 알게 된다. 쿼리 하나로 배포 단계에서 걸러낸다.
 */
@RestController
public class HealthController {

	private final BoothRepository booths;

	public HealthController(BoothRepository booths) {
		this.booths = booths;
	}

	@GetMapping("/health")
	public Map<String, Object> health() {
		// 예외가 나면 그대로 500이 나가는 게 맞다 — healthcheck가 실패해야 배포가 멈춘다.
		return Map.of("status", "ok", "app", "coach2", "booths", booths.count());
	}
}
