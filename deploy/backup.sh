#!/bin/sh
# coach2 DB 백업 — 행사 당일 예약 데이터의 두 번째 사본을 만든다.
#
# 지금 예약 데이터의 사본은 도커 볼륨 coach2_postgres_data 하나뿐이다. EBS 장애든
# `docker compose down -v` 오타든 한 번이면 그날 예약이 통째로, 복구 불가능하게 사라진다.
# 행사는 하루짜리라 다시 받을 방법도 없다.
#
# 크론에 직접 한 줄로 박지 않고 파일로 두는 이유: 크론은 `%`를 줄바꿈으로 해석해서
# date +%F 를 그대로 쓰면 명령이 잘린다. 이스케이프를 기억하는 것보다 파일이 안전하다.
#
# 설치 (ubuntu 계정):
#   crontab -e
#   0 * 24,25 8 * /home/ubuntu/coach2/deploy/backup.sh >> /home/ubuntu/coach2-backups/backup.log 2>&1
#
# 날짜를 24,25 8 로 묶어 8월 24·25일에만 돈다 — 지우는 것을 잊어도 평소에는 아무 일도 없다.
set -eu

APP_DIR="${APP_DIR:-/home/ubuntu/coach2}"
OUT_DIR="${OUT_DIR:-/home/ubuntu/coach2-backups}"
KEEP="${KEEP:-48}"

mkdir -p "$OUT_DIR"
cd "$APP_DIR"

STAMP=$(date +%Y%m%d-%H%M%S)
FILE="$OUT_DIR/coach2-$STAMP.sql.gz"

# -T 는 필수다. 크론에는 TTY가 없어서 이것이 없으면 docker compose exec가 바로 실패한다.
# 실패하면 여기서 멈춘다(set -e) — 반쪽짜리 파일을 성공한 백업처럼 남기지 않는다.
docker compose exec -T postgres pg_dump -U "${DB_USERNAME:-coach2}" -d coach2 | gzip > "$FILE.tmp"

# 다 받은 뒤에야 제 이름을 준다. 중간에 끊긴 파일이 복구 대상으로 뽑히면
# 그때는 백업이 있다고 믿은 것이 더 나쁘다.
mv "$FILE.tmp" "$FILE"
echo "$(date -Iseconds) ok $FILE ($(wc -c < "$FILE") bytes)"

# 오래된 것부터 지운다. 디스크가 차면 백업이 아니라 앱이 먼저 죽는다.
ls -1t "$OUT_DIR"/coach2-*.sql.gz 2>/dev/null | tail -n "+$((KEEP + 1))" | xargs -r rm -f
