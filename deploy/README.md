# coach2 배포 — 호스트 1회 설정

eastAI(coach1)가 이미 돌고 있는 EC2 호스트(`ubuntu@…`, `/home/ubuntu/eastAI`)에
coach2를 나란히 올린다. **eastAI의 컨테이너·DB·볼륨·코드는 건드리지 않는다.**
호스트 nginx 파일에만 `include` 한 줄이 추가된다.

이 문서가 존재하는 이유: 이 호스트의 nginx 설정은 어느 저장소에도 없다.
eastAI가 이미 그 문제를 갖고 있으므로, coach2는 같은 실수를 반복하지 않는다.

> **순서 주의.** GitHub 시크릿은 아래 0~7단계를 **전부 마친 뒤** 등록한다.
> 5단계(nginx include) 전에 main으로 push가 들어가면, 컨테이너 기동은 성공해도
> 헬스체크 단계는 계속 실패한다 — nginx가 아직 `/reserve/`를 라우팅하지 않아서이며
> 예상된 동작이다. **이때 eastAI 쪽을 건드릴 필요는 전혀 없다.**

---

## 0. 사전 확인

```bash
docker ps --format 'table {{.Names}}\t{{.Ports}}'   # eastai 3종이 살아 있는지
sudo ss -tlnp | grep -E ':8090'                     # 아무것도 안 나와야 한다
df -h /                                             # 빌드에 2GB 정도 필요
```

## 1. 저장소 클론

```bash
git clone <coach2-repo-url> /home/ubuntu/coach2
cd /home/ubuntu/coach2
```

## 2. `.env` 작성

GitHub Actions가 배포 때마다 덮어쓰지만, 첫 수동 기동에는 직접 필요하다.

```bash
cp .env.example .env
chmod 600 .env
# DB_PASSWORD를 채운다. 비어 있으면 컨테이너가 의도적으로 기동하지 않는다.
```

## 3. 기동 — nginx보다 먼저

nginx를 건드리기 전에 컨테이너부터 확실히 띄운다. 순서를 바꾸면 502를 보면서
원인이 nginx인지 앱인지 구분하지 못한다.

```bash
docker compose up --build -d --wait --wait-timeout 180
docker compose ps                       # 둘 다 healthy
curl -f http://127.0.0.1:8090/reserve/health
# {"status":"ok","app":"coach2"} 가 나와야 다음 단계로 간다
```

DB에 직접 붙어야 하면 (호스트 포트를 열지 않았다):

```bash
docker compose exec postgres psql -U coach2 -d coach2
```

## 4. nginx 스니펫 설치

```bash
sudo mkdir -p /etc/nginx/snippets
sudo cp /home/ubuntu/coach2/deploy/nginx-coach2.conf /etc/nginx/snippets/coach2.conf
```

## 5. eastAI의 server 블록에 include 추가

**여기가 유일하게 eastAI 쪽 파일을 만지는 지점이다. 백업부터 뜬다.**

```bash
sudo cp /etc/nginx/sites-available/eastpeace.kr /etc/nginx/sites-available/eastpeace.kr.bak
sudo nano /etc/nginx/sites-available/eastpeace.kr
```

`listen 443 ssl;` 이 있는 `server { … }` 안, `location /livekit/ { … }` 바로 아래에
한 줄을 넣는다:

```nginx
    include /etc/nginx/snippets/coach2.conf;
```

## 6. 검증 후 적용

`nginx -t` 없이 reload 하지 않는다. 문법 오류 하나로 eastpeace.kr 전체가 내려간다.

```bash
sudo nginx -t          # syntax is ok / test is successful 를 반드시 확인
sudo systemctl reload nginx
```

`nginx -t`가 실패하면 되돌린다:

```bash
sudo cp /etc/nginx/sites-available/eastpeace.kr.bak /etc/nginx/sites-available/eastpeace.kr
sudo nginx -t && sudo systemctl reload nginx
```

## 7. 최종 확인

```bash
curl -sS https://eastpeace.kr/reserve/health     # {"status":"ok","app":"coach2"}
curl -sS -o /dev/null -w '%{http_code}\n' https://eastpeace.kr/   # eastAI 응답 유지
```

**두 번째 줄이 이 배포의 진짜 합격 기준이다** — coach2가 뜨는 것보다 eastAI가
그대로인 게 중요하다. 응답 본문까지 확인하는 이유는, nginx include가 빠져 있으면
`/reserve/health`가 eastAI의 catch-all로 새면서 상태코드만으로는 통과해버리기 때문이다.

eastAI 루트는 **평소에 302를 준다**(앱이 리다이렉트). 200을 기대하지 말 것.
여기서 문제인 값은 `502`/`504`(nginx가 앱에 못 닿음)와 `000`(연결 실패)뿐이다.

---

## GitHub Actions 시크릿

coach2 저장소에 별도로 등록한다. **0~7단계를 마친 뒤에 등록한다.**

| 시크릿 | 값 |
|---|---|
| `EC2_HOST` | eastAI와 동일 |
| `EC2_USER` | `ubuntu` |
| `EC2_SSH_KEY` | **coach2 전용 신규 키** ← 아래 참조 |
| `EC2_APP_DIR` | `/home/ubuntu/coach2` ← eastAI와 **다름** |
| `DB_PASSWORD` | coach2 전용 신규 비밀번호 (eastAI 것 재사용 금지) |
| `ADMIN_SECRET` | 관리자 API 헤더 시크릿. `openssl rand -hex 32` |

관리자 API는 `X-Admin-Secret: <ADMIN_SECRET>` 헤더로만 열린다. `ADMIN_SECRET`을
주입하지 않으면 앱이 기동하지 않는다 — 배포에서 빠뜨렸을 때 관리자 API가 무방비로
열린 채 뜨는 것보다 낫다.

`DEPLOY_FREEZE` 변수는 **정확히 소문자 `true`** 여야 동작한다.
`True`/`TRUE`/공백 포함은 걸리지 않고 배포가 그대로 나간다.

### SSH 키를 따로 만드는 이유

eastAI의 키를 재사용하면 coach2 저장소가 뚫렸을 때(메인테이너 토큰 유출, 악성 PR 머지,
액션 시크릿 유출) 공격자가 eastAI의 배포 자격증명을 그대로 손에 넣는다. 같은 호스트·같은
계정이므로 `rm -rf /home/ubuntu/eastAI` 도, eastAI `.env` 탈취도 막을 게 없다.

```bash
ssh-keygen -t ed25519 -f ~/.ssh/coach2_deploy -C coach2-deploy -N ''
# 공개키를 서버 ubuntu 계정에 추가
cat ~/.ssh/coach2_deploy.pub >> ~/.ssh/authorized_keys   # (서버에서)
```

개인키(`~/.ssh/coach2_deploy`) 내용을 `EC2_SSH_KEY` 시크릿에 넣는다.

**한계는 알고 쓴다:** 키를 나눠도 `ubuntu`는 docker 그룹 소속이라 여전히 호스트 root와
동등한 권한을 갖는다. 얻는 것은 **독립적인 폐기 가능성**(한쪽만 회수)이지 권한 격리가
아니다. 진짜 격리가 필요하면 `authorized_keys`의 `command=` 강제 명령으로 묶어야 하는데,
현재 워크플로우는 `scp`와 `ssh`를 둘 다 쓰므로 `SSH_ORIGINAL_COMMAND`를 해석하는
래퍼 스크립트가 추가로 필요하다.

## 행사 당일 (8/25) — 하루짜리 절차

동시접속 50명은 실측으로 감당된다(HTTP 계층 50명 동시 예약: 5xx 0건, p95 1.9초,
`ConcurrentHttpBookingTest`). 그날 실제로 남는 위험은 부하가 아니라 **데이터와 배포**다.

### 1. 백업 — 반드시. 지금 사본이 볼륨 하나뿐이다

```bash
mkdir -p /home/ubuntu/coach2-backups
chmod +x /home/ubuntu/coach2/deploy/backup.sh
/home/ubuntu/coach2/deploy/backup.sh          # 먼저 손으로 한 번 돌려본다

crontab -e
# 8월 24·25일에만, 매시 정각
0 * 24,25 8 * /home/ubuntu/coach2/deploy/backup.sh >> /home/ubuntu/coach2-backups/backup.log 2>&1
```

**복구가 되는지 8/24 전에 한 번 확인한다.** 되는 줄 알았던 백업이 안 되는 것이
백업이 없는 것보다 나쁘다.

```bash
cd /home/ubuntu/coach2
docker compose exec -T postgres createdb -U coach2 coach2_restoretest
gunzip -c /home/ubuntu/coach2-backups/<파일>.sql.gz \
  | docker compose exec -T postgres psql -U coach2 -d coach2_restoretest
docker compose exec -T postgres psql -U coach2 -d coach2_restoretest -c 'select count(*) from reservation;'
docker compose exec -T postgres dropdb -U coach2 coach2_restoretest
```

### 2. 배포 동결 — 행사 시작 전에 켠다

GitHub → coach2 저장소 → Settings → Variables → `DEPLOY_FREEZE` = `true`
(**정확히 소문자**. `True`는 걸리지 않는다.)

`docker compose up --build`는 블루그린이 아니다. 기존 컨테이너를 멈추고 새 컨테이너를
띄우므로 JVM이 기동하는 수십 초 동안 `/reserve/` 전체가 502가 된다. 게다가 이미지 빌드가
운영 호스트(vCPU 2)에서 돌아 그 시간 내내 앱·eastAI와 CPU를 다툰다.
`stop_grace_period: 40s`는 멈추는 쪽의 요청만 구제할 뿐 이 502 구간은 없애지 못한다.
행사 중 급한 수정이 필요하면 동결을 잠깐 풀고 사람이 적은 시간에 수동으로 배포한다.

### 3. 감시 (선택)

앱이 죽으면 `restart: always`가 되살린다. 하지만 컨테이너가 unhealthy로 바뀌는 것에는
Docker가 아무 조치도 하지 않는다(Swarm이 아니다) — 살아는 있는데 DB가 막힌 상태는
사람이 보기 전까지 그대로다.

```bash
crontab -e
*/5 * 24,25 8 * curl -fsS --max-time 5 https://eastpeace.kr/reserve/health | grep -q '"app":"coach2"' || logger -t coach2-watchdog 'coach2 health check failed'
```

`journalctl -t coach2-watchdog -f`로 본다. 상태코드가 아니라 본문의 `"app":"coach2"`를
확인하는 이유는 배포 워크플로우와 같다 — nginx include가 어긋나면 eastAI의 catch-all이
2xx를 돌려주기 때문이다. 다만 syslog는 아무도 안 본다. 실제로 도움이 되려면
행사 중 이 명령을 띄워둔 터미널을 하나 열어두는 편이 낫다.

## 롤백

```bash
cd /home/ubuntu/coach2 && docker compose down
```

컨테이너만 내려간다. 볼륨(`coach2_postgres_data`)은 남는다.
nginx는 죽은 업스트림에 502를 낼 뿐 eastAI 경로에는 영향이 없지만,
길게 방치할 거면 5번의 include 줄을 주석 처리하고 `nginx -t && reload` 한다.

**`docker compose down -v` 는 쓰지 않는다** — `-v`는 DB 볼륨을 지운다.

**스키마는 롤백되지 않는다.** `SPRING_JPA_HIBERNATE_DDL_AUTO=update` 라서 구버전 코드를
되돌려도 DB 스키마는 새 상태로 남는다. 엔티티가 생기고 스키마가 굳으면
`validate` + Flyway로 옮긴다.
