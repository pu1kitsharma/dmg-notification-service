#!/usr/bin/env bash
# Resilience end-to-end scenarios that need special settings, so this script starts/stops the app itself from the
# packaged jar (run `mvn -q package -DskipTests` first). Nothing else may be listening on :8080.
#   R1 hung provider is interrupted, recorded as transient, retried, and does not pin the pool thread
#   R2 a saturated channel pool does not block the tenant's other channels; released rows are not lost or duplicated
#   R3 hard crash (kill -9) mid-send on a persistent DB: rows are recovered by lease expiry and delivered once
#   R4 graceful shutdown (SIGTERM) mid-send: same guarantee
# R3/R4 use a file-based H2 by default; set DB_URL (+DB_USER/DB_PASS) to run them against e.g. Postgres:
#   DB_URL=jdbc:postgresql://localhost:5432/notify DB_USER=postgres DB_PASS=secret docs/e2e-resilience.sh
set -uo pipefail
cd "$(dirname "$0")/.."
BASE=http://localhost:8080
PLATFORM="admin:${APP_BOOTSTRAP_ADMIN_PASSWORD:-admin12345}"
JAR=$(ls target/notification-service-*.jar | head -1)
H='Content-Type: application/json'
RUN=$(date +%s)
WORK=$(mktemp -d)
PASS=0; FAIL=0; APP_PID=""

check() { if [ "$2" = "$3" ]; then PASS=$((PASS+1)); echo "  ok    $1"; else FAIL=$((FAIL+1)); echo "  FAIL  $1  (expected '$3', got '$2')"; fi; }
check_true() { if [ "$2" = 0 ]; then PASS=$((PASS+1)); echo "  ok    $1"; else FAIL=$((FAIL+1)); echo "  FAIL  $1"; fi; }
section() { echo; echo "== $1"; }
jf() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

start_app() { # logfile extra-args...
  local log=$1; shift
  java -jar "$JAR" "$@" > "$log" 2>&1 &
  APP_PID=$!
  for _ in $(seq 1 60); do curl -s -o /dev/null $BASE/api/v1/tenants && return 0; sleep 1; done
  echo "app failed to start; see $log"; exit 2
}
stop_app() { [ -n "$APP_PID" ] && { kill "$APP_PID" 2>/dev/null; wait "$APP_PID" 2>/dev/null; }; APP_PID=""; }
trap 'stop_app; rm -rf "$WORK"' EXIT
mk_tenant() { # tag maxAttempts -> T_<tag>
  curl -s -o /dev/null -u "$PLATFORM" -H "$H" -X POST $BASE/api/v1/tenants \
    -d "{\"name\":\"$1-$RUN\",\"ratePerSecond\":100000,\"burst\":100000,\"maxAttempts\":$2,\"adminUsername\":\"$1-$RUN\",\"adminPassword\":\"e2e-password-1\"}"
  eval "T_$1=\"$1-$RUN:e2e-password-1\""
}
tapi() { local who=$1; shift; curl -s -u "$who" -H "$H" "$@"; }
mk_template() { tapi "$1" -X POST $BASE/api/v1/templates -d "{\"name\":\"welcome\",\"channel\":\"$2\",\"subject\":\"Hi\",\"body\":\"Hello {{name}}\"}" >/dev/null; }
send() { # creds channel recipient -> id
  tapi "$1" -X POST $BASE/api/v1/notifications -d "{\"channel\":\"$2\",\"templateName\":\"welcome\",\"recipient\":\"$3\",\"variables\":{\"name\":\"x\"}}" | jf 'd["id"]'
}
nstatus() { tapi "$1" $BASE/api/v1/notifications/$2 | jf 'd["notification"]["status"]'; }
wait_status() { local end=$((SECONDS + ${4:-30})) s=""; while [ $SECONDS -lt $end ]; do s=$(nstatus "$1" "$2"); [ "$s" = "$3" ] && { echo "$s"; return 0; }; sleep 0.5; done; echo "$s"; return 1; }
count_status() { tapi "$1" "$BASE/api/v1/notifications?status=$2&size=100" | jf 'd["page"]["totalElements"]'; }

if curl -s -o /dev/null $BASE/api/v1/tenants; then echo "something is already listening on :8080; stop it first"; exit 2; fi

# ---------------------------------------------------------------------------------------------------
section "R1. hung provider (provider takes 20s, send-timeout 1s, pool of 1 thread)"
start_app $WORK/r1.log --app.channels.latency-ms=20000 --app.dispatch.send-timeout-ms=1000 --app.dispatch.pool-size=1 --app.retry.base-delay-ms=500
mk_tenant r1 2; mk_template "$T_r1" EMAIL
A=$(send "$T_r1" EMAIL a@example.com); B=$(send "$T_r1" EMAIL b@example.com)
# without the watchdog the single thread would be pinned for 20s per send: 2 messages x 2 attempts = 80s
START=$SECONDS
check "message A reaches DEAD (all attempts time out)" "$(wait_status "$T_r1" $A DEAD 30)" DEAD
check "message B reaches DEAD too (thread was freed for it)" "$(wait_status "$T_r1" $B DEAD 30)" DEAD
check_true "both finished in < 30s (took $((SECONDS-START))s; would be 80s+ without the timeout)" "$([ $((SECONDS-START)) -lt 30 ] && echo 0 || echo 1)"
DA=$(tapi "$T_r1" $BASE/api/v1/notifications/$A)
check "2 attempts, both TRANSIENT_FAILURE" "$(echo "$DA" | jf '[a["outcome"] for a in d["attempts"]]')" "['TRANSIENT_FAILURE', 'TRANSIENT_FAILURE']"
check "error says the provider call timed out" "$(echo "$DA" | jf '"timed out after 1000ms" in d["attempts"][0]["error"]')" True
check "no unhandled errors logged" "$(grep -c 'Unhandled error' $WORK/r1.log)" 0
stop_app

# ---------------------------------------------------------------------------------------------------
section "R2. saturated SMS pool must not block the same tenant's EMAIL (1 thread + 1 queue slot per channel, 3s provider)"
start_app $WORK/r2.log --app.channels.latency-ms=3000 --app.dispatch.pool-size=1 --app.dispatch.queue-capacity=1 --app.dispatch.send-timeout-ms=10000
mk_tenant r2 3; mk_template "$T_r2" EMAIL; mk_template "$T_r2" SMS
ITEMS=$(python3 -c "
import json
items=[{'notification':{'channel':'SMS','templateName':'welcome','recipient':'+1555000%04d'%i,'variables':{'name':'x'}}} for i in range(5)]
items.append({'notification':{'channel':'EMAIL','templateName':'welcome','recipient':'mail@example.com','variables':{'name':'x'}}})
print(json.dumps({'items':items}))")
R=$(tapi "$T_r2" -X POST $BASE/api/v1/notifications/batch -d "$ITEMS")
check "6 accepted (5 SMS then 1 EMAIL)" "$(echo "$R" | jf 'd["accepted"]')" 6
MAIL=$(echo "$R" | jf 'd["results"][5]["id"]')
check "EMAIL delivered while SMS is backed up" "$(wait_status "$T_r2" $MAIL SENT 12)" SENT
SMS_NOW=$(count_status "$T_r2" SENT)
check_true "at that moment SMS was still backlogged (SENT total incl. email = $SMS_NOW, expect <= 3 of 6)" "$([ "$SMS_NOW" -le 3 ] && echo 0 || echo 1)"
END=$((SECONDS+45)); while [ $SECONDS -lt $END ] && [ "$(count_status "$T_r2" SENT)" != 6 ]; do sleep 1; done
check "all 6 delivered eventually (released rows were not lost)" "$(count_status "$T_r2" SENT)" 6
check "each SMS delivered exactly once at the provider" "$(grep -c '\[SMS\] delivered' $WORK/r2.log)" 5
check "no notification exceeded 1 successful attempt" "$(tapi "$T_r2" "$BASE/api/v1/notifications?size=100" | jf 'max(n["attemptCount"] for n in d["content"])')" 1
check "no unhandled errors logged" "$(grep -c 'Unhandled error' $WORK/r2.log)" 0
stop_app

# ---------------------------------------------------------------------------------------------------
crash_scenario() { # label signal
  local label=$1 sig=$2 db="${DB_URL:-jdbc:h2:file:$WORK/db-$1;MODE=PostgreSQL;DB_CLOSE_DELAY=-1}"
  local dbargs=(--spring.datasource.url="$db")
  [ -n "${DB_URL:-}" ] && dbargs+=(--spring.datasource.username="${DB_USER:-postgres}" --spring.datasource.password="${DB_PASS:-}")
  section "$label. $( [ "$sig" = 9 ] && echo "kill -9" || echo "SIGTERM" ) mid-send on a persistent DB, then restart (lease 6s, provider 4s)"
  # send-timeout 5s < lease 6s; provider takes 4s so sends are in flight when we kill the process
  start_app $WORK/$label-1.log "${dbargs[@]}" --app.channels.latency-ms=4000 --app.dispatch.lease-seconds=6 --app.dispatch.send-timeout-ms=5000 --app.dispatch.pool-size=4 --app.retry.base-delay-ms=200
  mk_tenant $label 4
  local creds; creds=$(eval echo "\$T_$label")
  mk_template "$creds" EMAIL
  local ids=()
  for i in 1 2 3; do ids+=("$(send "$creds" EMAIL "c$i@example.com")"); done
  sleep 1.5
  check "3 sends are in flight (PROCESSING) when the process dies" "$(count_status "$creds" PROCESSING)" 3
  kill -$sig "$APP_PID"; wait "$APP_PID" 2>/dev/null; APP_PID=""
  check_true "app is really down" "$(curl -s -o /dev/null $BASE/api/v1/tenants; [ $? -ne 0 ] && echo 0 || echo 1)"
  # restart on the same database with a fast provider; recovery must come from the persisted lease
  start_app $WORK/$label-2.log "${dbargs[@]}" --app.channels.latency-ms=0 --app.dispatch.lease-seconds=6 --app.dispatch.send-timeout-ms=5000 --app.retry.base-delay-ms=200
  check "data survived the restart (3 notifications visible)" "$(tapi "$creds" "$BASE/api/v1/notifications?size=100" | jf 'd["page"]["totalElements"]')" 3
  local all=1
  for id in "${ids[@]}"; do [ "$(wait_status "$creds" "$id" SENT 40)" = SENT ] || all=0; done
  check "all 3 recovered and delivered after restart" "$all" 1
  for id in "${ids[@]}"; do
    local d; d=$(tapi "$creds" $BASE/api/v1/notifications/$id)
    check "  $id: exactly one SUCCESS attempt" "$(echo "$d" | jf 'sum(1 for a in d["attempts"] if a["outcome"]=="SUCCESS")')" 1
    check "  $id: audit shows the recovery (lease expired / interrupted then retried)" "$(echo "$d" | jf 'any(("lease expired" in (e["reason"] or "")) or ("interrupted" in (e["reason"] or "")) or ("timed out" in (e["reason"] or "")) for e in d["events"])')" True
  done
  check "provider delivered each message exactly once after restart" "$(grep -c 'delivered .* to c' $WORK/$label-2.log)" 3
  check "no unhandled errors after restart" "$(grep -c 'Unhandled error' $WORK/$label-2.log)" 0
  stop_app
}
crash_scenario R3 9
crash_scenario R4 TERM

echo
echo "=================================================="
echo "RESILIENCE RESULT: $PASS passed, $FAIL failed"
[ "$FAIL" = 0 ] && echo "RESILIENCE OK" || { echo "RESILIENCE FAILED"; exit 1; }
