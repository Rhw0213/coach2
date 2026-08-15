package com.interview.coach2;

import com.interview.coach2.reservation.BoothRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 컨테이너 healthcheck와 CI가 의존하는 계약을 고정한다.
 * 응답 본문이 바뀌면 배포 워크플로우의 grep '"app":"coach2"' 가 조용히 실패한다.
 *
 * Spring Boot 컨텍스트를 띄우지 않고 standalone으로 돈다 — Boot 4는
 * @AutoConfigureMockMvc를 spring-boot-starter-test에서 빼버렸고, 여기서 확인하려는 건
 * 라우팅 매핑과 JSON 직렬화뿐이라 전체 컨텍스트가 필요 없다.
 * (컨텍스트가 실제로 뜨는지는 ReservationServiceTest의 @SpringBootTest가 증명한다.)
 */
class HealthControllerTest {

	// DB 왕복은 스텁으로 대신한다. 여기서 고정하려는 건 응답 계약이고,
	// DB가 실제로 살아 있어야 200이 난다는 성질은 운영의 healthcheck가 검증한다.
	private final BoothRepository booths = mock(BoothRepository.class);
	private final MockMvcTester mvc = MockMvcTester.of(new HealthController(booths));

	{
		when(booths.count()).thenReturn(3L);
	}

	@Test
	void health_returns_ok() {
		assertThat(mvc.get().uri("/health"))
			.hasStatusOk()
			.bodyJson().extractingPath("$.status").isEqualTo("ok");
	}

	@Test
	void health_identifies_the_app() {
		assertThat(mvc.get().uri("/health"))
			.bodyJson().extractingPath("$.app").isEqualTo("coach2");
	}
}
