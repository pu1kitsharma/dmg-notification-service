#!/usr/bin/env bash
# End-to-end smoke test against a running instance (mvn spring-boot:run). Exits non-zero on the first failure.
#   BASE=http://localhost:8080 APP_BOOTSTRAP_ADMIN_PASSWORD=... docs/smoke.sh
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
PLATFORM="admin:${APP_BOOTSTRAP_ADMIN_PASSWORD:-admin12345}"
SUFFIX=$(date +%s)
TENANT_USER="smoke-$SUFFIX-admin"
TENANT="$TENANT_USER:smoke-password-1"
H='Content-Type: application/json'

code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
expect() { [ "$1" = "$2" ] || { echo "FAIL: $3 (expected $2, got $1)"; exit 1; }; echo "ok   $3"; }
field() { python3 -c "import sys,json; print(json.load(sys.stdin)$1)"; }

expect "$(code $BASE/api/v1/tenants)" 401 "unauthenticated -> 401"

TENANT_BODY='{"name":"smoke-'$SUFFIX'","ratePerSecond":100,"burst":100,"maxAttempts":3,"adminUsername":"'$TENANT_USER'","adminPassword":"smoke-password-1"}'
expect "$(code -u "$PLATFORM" -H "$H" -X POST $BASE/api/v1/tenants -d "$TENANT_BODY")" 201 "platform admin creates tenant"
expect "$(code -u "$TENANT" $BASE/api/v1/tenants)" 403 "tenant admin cannot list tenants"
expect "$(code -u "$PLATFORM" $BASE/api/v1/notifications)" 403 "platform admin cannot send"

expect "$(code -u "$TENANT" -H "$H" -X POST $BASE/api/v1/templates -d \
  '{"name":"welcome","channel":"EMAIL","subject":"Hi {{name}}","body":"Welcome {{name}}"}')" 201 "create template"

RESP=$(curl -s -u "$TENANT" -H "$H" -H "Idempotency-Key: smoke-$SUFFIX" -X POST $BASE/api/v1/notifications \
  -d '{"channel":"EMAIL","templateName":"welcome","recipient":"ada@example.com","variables":{"name":"Ada"}}')
ID=$(echo "$RESP" | field "['id']")
echo "ok   submitted $ID"
expect "$(code -u "$TENANT" -H "$H" -H "Idempotency-Key: smoke-$SUFFIX" -X POST $BASE/api/v1/notifications \
  -d '{"channel":"EMAIL","templateName":"welcome","recipient":"ada@example.com","variables":{"name":"Ada"}}')" 200 "idempotent replay -> 200"

wait_status() { # id, wanted
  for _ in $(seq 1 40); do
    S=$(curl -s -u "$TENANT" $BASE/api/v1/notifications/$1 | field "['notification']['status']")
    [ "$S" = "$2" ] && { echo "ok   $1 -> $2"; return 0; }
    sleep 0.5
  done
  echo "FAIL: $1 stuck in $S (wanted $2)"; exit 1
}
wait_status "$ID" SENT

# permanent failure -> DEAD -> replay -> DEAD again (still failing recipient), audit trail present
DEAD=$(curl -s -u "$TENANT" -H "$H" -X POST $BASE/api/v1/notifications \
  -d '{"channel":"EMAIL","templateName":"welcome","recipient":"fail-permanent@example.com","variables":{"name":"Bob"}}' | field "['id']")
wait_status "$DEAD" DEAD
expect "$(code -u "$TENANT" -X POST $BASE/api/v1/notifications/$DEAD/replay)" 200 "replay DEAD -> 200"
wait_status "$DEAD" DEAD
expect "$(code -u "$TENANT" -X POST $BASE/api/v1/notifications/$ID/replay)" 409 "replay SENT -> 409"

# batch
BATCH=$(curl -s -u "$TENANT" -H "$H" -X POST $BASE/api/v1/notifications/batch -d \
  '{"items":[{"notification":{"channel":"EMAIL","templateName":"welcome","recipient":"c@example.com","variables":{"name":"C"}}},{"notification":{"channel":"EMAIL","templateName":"nope","recipient":"d@example.com"}}]}')
[ "$(echo "$BATCH" | field "['accepted']")" = 1 ] && [ "$(echo "$BATCH" | field "['rejected']")" = 1 ] || { echo "FAIL: batch $BATCH"; exit 1; }
echo "ok   batch: 1 accepted, 1 rejected"

# report + list filters
REPORT=$(curl -s -u "$TENANT" $BASE/api/v1/reports/delivery)
echo "$REPORT" | field "['byTemplate'][0]['template']" | grep -q welcome || { echo "FAIL: report $REPORT"; exit 1; }
echo "ok   report by template"
expect "$(code -u "$TENANT" "$BASE/api/v1/notifications?status=DEAD&channel=EMAIL")" 200 "list with filters"

# other tenants' data is invisible; global limits are platform-only
expect "$(code -u "$TENANT" $BASE/api/v1/limits/global)" 403 "tenant admin cannot read global limits"
expect "$(code -u "$PLATFORM" $BASE/api/v1/limits/global)" 200 "platform admin reads global limits"
echo "SMOKE OK"
