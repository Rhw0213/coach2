package com.interview.coach2.reservation;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

	private final AdminAuthInterceptor adminAuth;

	public WebConfig(AdminAuthInterceptor adminAuth) {
		this.adminAuth = adminAuth;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		// 경로 하나로 묶어둔다 — 관리자 엔드포인트가 늘어도 인증을 따로 붙일 필요가 없다.
		registry.addInterceptor(adminAuth).addPathPatterns("/api/admin/**");
	}
}
