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

## 행사 취소 — 종료와 파기

행사가 취소되어 시스템을 더 쓰지 않을 때의 절차다.

**새 인스턴스를 만들어 옮긴 뒤 정지시키지 않는다.** 정지한 EC2도 EBS 요금은 계속 나가고,
다시 켜는 날에는 인증서 만료(Let's Encrypt 90일)와 밀린 패치 때문에 어차피 손봐야 한다.
보관할 가치가 있는 것은 코드뿐이고 그것은 저장소에 있다 — 서버 한 대를 세워둘 이유가 없다.
다시 열게 되면 이 문서의 설치 절차로 새로 올리는 편이 빠르다.

> ⚠ **되돌릴 수 없다.** 아래 `down -v` 는 DB 볼륨을 지운다. 예약자 명단·합격자 명단이
> 함께 사라지므로, **발주처 확인을 받고 시작한다.** 나중에 "명단만 좀 주세요"가 오면
> 그때는 줄 것이 없다.

### 순서 — 이 순서여야 하는 이유가 각각 있다

**0. 배포 동결부터.** 파기 도중 누가 `main`에 푸시하면 컨테이너가 되살아난다.

GitHub → Settings → Variables → `DEPLOY_FREEZE` = `true` (**소문자**)

**1. 안내 페이지를 먼저 올린다.** 컨테이너를 먼저 내리면 그 사이 들어온 사람이 502를 본다.

```bash
sudo mkdir -p /var/www/coach2-notice/reserve
sudo cp /home/ubuntu/coach2/deploy/notice/index.html /var/www/coach2-notice/reserve/
sudo cp /home/ubuntu/coach2/deploy/nginx-coach2-notice.conf /etc/nginx/snippets/coach2.conf
sudo nginx -t && sudo systemctl reload nginx
curl -sS https://eastpeace.kr/reserve/ | grep -q 취소 && echo '안내 페이지 정상'
```

안내 문구는 **초안이다.** `deploy/notice/index.html` 을 열어 '취소'인지 '연기'인지,
개인정보 파기 문장이 실제 처리와 맞는지 확인하고 고친 뒤 올린다.

**2. 컨테이너와 DB 볼륨 파기**

```bash
cd /home/ubuntu/coach2
docker compose down -v            # -v 가 볼륨을 지운다. 이 문서 다른 곳의 경고와 반대다
docker volume ls | grep coach2    # 아무것도 안 나와야 한다
docker ps -a | grep coach2        # 아무것도 안 나와야 한다
```

**3. 백업 파일까지 지운다 — 여기를 잊는다**

행사 당일 절차의 crontab을 걸었다면 덤프가 쌓여 있고, 그 안에는 예약자 이름·연락처가
평문으로 들어 있다. DB만 지우고 여기를 두면 파기한 것이 아니다.

```bash
crontab -e                                  # backup.sh · 워치독 줄 제거
rm -rf /home/ubuntu/coach2-backups
rm -f /tmp/*.sql.gz
```

**4. 자격증명 정리**

```bash
rm -f /home/ubuntu/coach2/.env              # DB 비밀번호·ADMIN_SECRET 이 평문으로 있다
# ~/.ssh/authorized_keys 에서 coach2-deploy 공개키 줄 제거
```

GitHub 시크릿(`DB_PASSWORD`, `ADMIN_SECRET`, `EC2_SSH_KEY`)도 삭제한다.
쓰지 않는 시크릿은 지켜야 할 대상만 늘린다.

**5. 이미지 정리 (선택)**

```bash
docker image prune -f
```

`docker builder prune` 은 쓰지 않는다 — 빌드 캐시는 데몬 전역이라 eastAI 캐시까지 지운다.

**6. 검증 — 넷 다 확인한다**

```bash
curl -sS -o /dev/null -w '%{http_code}\n' https://eastpeace.kr/reserve/   # 200 (안내)
curl -sS -o /dev/null -w '%{http_code}\n' https://eastpeace.kr/           # eastAI 그대로
docker ps | grep coach2 || echo 'coach2 컨테이너 없음'
docker volume ls | grep coach2 || echo 'coach2 볼륨 없음'
```

**7. 파기 기록을 남긴다**

개인정보를 파기하면 기록을 남기는 것이 원칙이다. 이 저장소나 발주처 문서에 한 줄 남긴다.

| 항목 | 내용 |
|---|---|
| 파기 일시 | |
| 대상 | `visitor`(이름·연락처·학교·전공·학년), `approval`(합격자 이름·연락처), 예약 기록 |
| 사유 | 행사 취소로 수집 목적 소멸 |
| 방법 | Docker 볼륨 삭제(`compose down -v`), 백업 덤프 파일 삭제 |
| 확인자 | |

### 다시 열게 되면

코드는 저장소에 그대로 있다. 이 문서 0~7단계(공유 호스트) 또는
'eastAI와 인스턴스 분리' 절(전용 호스트)로 새로 올린다.
**데이터는 없다** — 파기했으므로 부스·기업·명단을 다시 넣는 것부터 시작한다.

## eastAI와 인스턴스 분리 — 전용 EC2 + 서브도메인

**목적은 장애·자원 격리다.** 지금은 vCPU 2를 나눠 쓰고, 무엇보다 docker 데몬과 `ubuntu`
계정을 공유한다 — 배포 키를 나눠도 `ubuntu`는 docker 그룹이라 호스트 root와 동등해서,
한쪽이 뚫리면 다른 쪽도 같이 넘어간다. 인스턴스를 나누면 그 고리가 끊긴다.

**끊기지 않는 것이 하나 있다.** 포스터·QR·문자로 나간 주소가 `eastpeace.kr/reserve`를
가리킨다. 종이는 회수되지 않으므로 옛 호스트에 308 리다이렉트를 남긴다
(`deploy/nginx-eastai-redirect.conf`). eastAI에 남기는 흔적은 include 한 줄에서
redirect 한 줄로 바뀔 뿐 크기가 같다.

**앱은 이미 옮길 준비가 돼 있다.** 프론트는 `location.pathname` 기준 상대경로라 도메인이
없고(`app.js`의 `BASE`), 경로 prefix는 `CONTEXT_PATH` 환경변수다. 코드 변경은 없다.

### 준비물

| 항목 | 값 | 이유 |
|---|---|---|
| 인스턴스 | **RAM 4GB 이상** (t3.medium 급) | 컨테이너 한계 합이 앱 1536m + DB 512m = 2GB다. 2GB 인스턴스는 OS·도커가 앉을 자리가 없다 |
| 디스크 | 20GB 이상 | 호스트에서 이미지를 빌드한다(`up --build`). 빌드에 2GB 정도 든다 |
| 보안그룹 | 22 / 80 / 443 | 8090은 열지 않는다 — 루프백 전용이다 |
| 고정 IP | 탄력적 IP | 재부팅으로 IP가 바뀌면 DNS가 어긋난다 |
| DNS | `reserve.eastpeace.kr` A 레코드 | 인증서 HTTP-01 검증이 이 이름으로 새 IP에 닿아야 한다 |

### 절차 — 5~7단계까지는 무중단이다

**1. (하루 전) DNS TTL을 60초로 낮춘다.** 되돌릴 일이 생겼을 때 전파를 기다리지 않는다.

**2. 새 호스트 준비**

```bash
sudo apt-get update && sudo apt-get install -y docker.io docker-compose-v2 nginx certbot python3-certbot-nginx
sudo usermod -aG docker ubuntu   # 다시 로그인해야 적용된다
git clone <coach2-repo-url> /home/ubuntu/coach2
cd /home/ubuntu/coach2 && cp .env.example .env && chmod 600 .env
```

`.env`에서 **`CONTEXT_PATH=`를 빈 값으로 둔다.** 서브도메인이라 prefix가 없다.
비워두면 앱이 루트로 서비스한다. (`docker-compose.yml`이 `${CONTEXT_PATH-/reserve}`로
콜론 없이 받는다 — `:-`였다면 빈 값이 `/reserve`로 되돌아가 조용히 404가 났다.)

**3. 기동 — nginx보다 먼저**

```bash
docker compose up --build -d --wait --wait-timeout 300
curl -f http://127.0.0.1:8090/health     # {"status":"ok","app":"coach2"} — /reserve 없이
```

**4. DNS A 레코드 추가 → 인증서 발급**

```bash
dig +short reserve.eastpeace.kr          # 새 IP가 나와야 다음으로 간다
sudo certbot certonly --nginx -d reserve.eastpeace.kr
```

**5. nginx 설치**

```bash
sudo cp deploy/nginx-coach2-standalone.conf /etc/nginx/sites-available/reserve.eastpeace.kr
sudo ln -s /etc/nginx/sites-available/reserve.eastpeace.kr /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
curl -sS https://reserve.eastpeace.kr/health
```

**6. 여기까지 옛 사이트는 멀쩡히 돌고 있다.** 새 주소로는 빈 DB가 보인다. 새 주소에서
예약 화면·관리자 화면이 뜨는지 눌러본다. 문제가 있으면 여기서 멈춰도 아무 일도 안 난다.

**7. 컷오버 — 이 순서를 지키면 예약이 유실되지 않는다** (다운타임 5분 내외)

```bash
# (a) 옛 호스트 — 쓰기를 멈춘다. DB는 살려둔다(덤프를 떠야 한다).
cd /home/ubuntu/coach2 && docker compose stop app

# (b) 옛 호스트 — 덤프
docker compose exec -T postgres pg_dump -U coach2 -d coach2 | gzip > /tmp/cutover.sql.gz

# (c) 새 호스트로 옮겨 복원 (새 호스트에서 실행)
scp ubuntu@<옛IP>:/tmp/cutover.sql.gz /tmp/
cd /home/ubuntu/coach2
docker compose exec -T postgres psql -U coach2 -d coach2 -c 'drop schema public cascade; create schema public;'
gunzip -c /tmp/cutover.sql.gz | docker compose exec -T postgres psql -U coach2 -d coach2
docker compose restart app

# (d) 옛 호스트 — 스니펫을 리다이렉트로 교체
sudo cp deploy/nginx-eastai-redirect.conf /etc/nginx/snippets/coach2.conf
sudo nginx -t && sudo systemctl reload nginx
```

⚠ (a)를 건너뛰고 덤프하면 **덤프와 컷오버 사이에 들어온 예약이 사라진다.** 앱을 먼저
멈추는 것이 유일한 무손실 순서다.

**8. 검증 — 셋 다 통과해야 끝난 것이다**

```bash
curl -sS https://reserve.eastpeace.kr/health                        # coach2 응답
curl -sS -o /dev/null -w '%{http_code} %{redirect_url}\n' https://eastpeace.kr/reserve/    # 308 + 새 주소
curl -sS -o /dev/null -w '%{http_code}\n' https://eastpeace.kr/     # eastAI 그대로 (302/200)
```

관리자 화면에서 **예약 건수가 옛 호스트와 같은지** 눈으로 확인한다. 숫자가 다르면
복원이 덜 된 것이므로 9번으로 되돌린다.

**9. GitHub 설정 교체**

| 항목 | 값 |
|---|---|
| `EC2_HOST` (시크릿) | 새 인스턴스 IP |
| `EC2_SSH_KEY` (시크릿) | 새 인스턴스용 키 |
| 변수 `PUBLIC_URL` | `https://reserve.eastpeace.kr` |

`PUBLIC_URL`을 넣으면 배포 후 헬스체크가 새 주소를 본다(넣지 않으면 옛 주소를 본다).
컷오버가 끝나면 워크플로우의 **eastAI 확인 단계는 지운다** — 더 이상 같은 호스트가
아니므로, 남의 서비스 장애로 coach2 배포가 실패하게 된다.

**10. 정리 (컷오버 확인 후 하루쯤 뒤)**

```bash
# 옛 호스트 — 컨테이너만 내린다. 볼륨은 남긴다(롤백 자산이다).
cd /home/ubuntu/coach2 && docker compose down
```

### 롤백

컷오버 직후라면 되돌릴 수 있다. 옛 호스트의 DB 볼륨이 그대로 남아 있기 때문이다.

```bash
# 옛 호스트
sudo cp deploy/nginx-coach2.conf /etc/nginx/snippets/coach2.conf   # 리다이렉트 → 원래 스니펫
sudo nginx -t && sudo systemctl reload nginx
cd /home/ubuntu/coach2 && docker compose up -d
```

**되돌릴 수 있는 시간은 짧다.** 컷오버 뒤 새 주소로 들어온 예약은 옛 볼륨에 없다.
롤백하면 그 예약들이 사라지므로, 되돌릴 거면 컷오버 직후에 결정한다.

### 일정 주의

행사는 8/25다. **컷오버는 늦어도 8/23까지 끝내고 8/24는 아무것도 건드리지 않는다.**
전날에 남기는 것은 확인뿐이다 — DNS 전파, 인증서 갱신 크론, 백업 크론(새 호스트에
다시 걸어야 한다. 옛 호스트의 crontab은 따라오지 않는다).

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
