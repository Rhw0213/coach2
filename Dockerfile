# syntax=docker/dockerfile:1
#
# 베이스 이미지는 eastAI(coach1)와 동일하게 맞춘다 — 호스트에 이미 받아져 있어
# 레이어가 공유되고, 두 번째 스택을 올려도 이미지 디스크가 거의 늘지 않는다.

# ── Stage 1: Build ──────────────────────────────────────────
# 태그는 8.x 최신을 가리킨다. Spring Boot 4.0.6 플러그인은 Gradle 8.14 이상을 요구하므로
# 로컬에서 그보다 낮은 gradle로 빌드하면 플러그인 적용 단계에서 바로 실패한다.
FROM gradle:8-jdk17-alpine AS builder
WORKDIR /app

COPY build.gradle settings.gradle ./
COPY src/ src/

# 캐시 마운트로 진짜 Gradle 캐시(내려받은 jar)를 빌드 간에 유지한다.
# eastAI가 쓰는 'gradle dependencies' 선행 레이어 방식은 베끼지 않았다 —
# 그 명령은 의존성 그래프(POM)만 해석하고 jar는 받지 않아서, 다음 레이어가
# 어차피 전부 다시 내려받는다. 게다가 배포마다 도는 docker image prune -f 가
# 그 레이어를 날려 매번 반복된다. 캐시 마운트는 prune에도 살아남는다.
RUN --mount=type=cache,target=/home/gradle/.gradle \
    GRADLE_USER_HOME=/home/gradle/.gradle gradle bootJar --no-daemon

# ── Stage 2: Run ─────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# healthcheck가 curl을 쓴다
RUN apk add --no-cache curl

# root로 돌리지 않는다. 앱이나 의존성에 RCE가 나도 컨테이너 안에서 uid 0을 주지 않는다.
RUN addgroup -S app && adduser -S app -G app

# build.gradle에서 archiveFileName을 app.jar로 고정했다 — 와일드카드로 집다가
# plain jar까지 걸려 빌드가 깨지는 일이 없도록 정확한 이름으로 복사한다.
COPY --from=builder --chown=app:app /app/build/libs/app.jar app.jar

USER app

EXPOSE 8080
# mem_limit 1536m 기준. 힙 65%(≈998m) + metaspace 상한 192m를 명시해서
# 나머지를 스레드 스택·코드캐시·네이티브에 남긴다. metaspace를 안 묶으면
# Hibernate 프록시 생성이 밀어올려 cgroup OOM-kill(SIGKILL)로 이어질 수 있고,
# 그건 ExitOnOutOfMemoryError가 잡지 못한다.
ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=65.0", \
    "-XX:MaxMetaspaceSize=192m", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-jar", "app.jar"]
