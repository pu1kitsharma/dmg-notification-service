#!/usr/bin/env bash
# Full end-to-end check against a RUNNING instance with the default config (mvn spring-boot:run or java -jar).
# Covers every endpoint and behaviour reachable over HTTP: RBAC, tenant lifecycle, templates, validation,
# idempotency, scheduling, cancel, channel config, retries/DEAD/replay, rate limits, fairness, batch, reports.
#   BASE=http://localhost:8080 APP_LOG=/path/to/app.log docs/e2e.sh     (APP_LOG is optional: enables the senderId check)
# Takes ~1-2 minutes (real retry backoff). Exit code != 0 if any check fails. Resilience scenarios needing special
# settings (hung provider, tiny pools, crash recovery) are in docs/e2e-resilience.sh.
set -uo pipefail
BASE=${BASE:-http://localhost:8080}
PLATFORM="admin:${APP_BOOTSTRAP_ADMIN_PASSWORD:-admin12345}"
RUN=$(date +%s)
H='Content-Type: application/json'
PASS=0; FAIL=0

# --- helpers -----------------------------------------------------------------------------------------
check() { # name actual expected
  if [ "$2" = "$3" ]; then PASS=$((PASS+1)); echo "  ok    $1"; else FAIL=$((FAIL+1)); echo "  FAIL  $1  (expected '$3', got '$2')"; fi
}
check_true() { # name condition-result(0=true)
  if [ "$2" = 0 ]; then PASS=$((PASS+1)); echo "  ok    $1"; else FAIL=$((FAIL+1)); echo "  FAIL  $1"; fi
}
section() { echo; echo "== $1"; }
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }                       # http status only
jf() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }      # jf 'd["a"][0]'
future() { python3 -c "import datetime as t; print((t.datetime.now(t.timezone.utc)+t.timedelta(seconds=$1)).strftime('%Y-%m-%dT%H:%M:%SZ'))"; }

mk_tenant() { # name rate burst maxAttempts  -> sets creds var T_<name> and id var ID_<name>
  local body="{\"name\":\"e2e-$1-$RUN\",\"ratePerSecond\":$2,\"burst\":$3,\"maxAttempts\":$4,\"adminUsername\":\"e2e-$1-$RUN\",\"adminPassword\":\"e2e-password-1\"}"
  local r; r=$(curl -s -u "$PLATFORM" -H "$H" -X POST $BASE/api/v1/tenants -d "$body")
  eval "ID_$1=$(echo "$r" | jf 'd["id"]')"
  eval "T_$1=\"e2e-$1-$RUN:e2e-password-1\""
}
tapi() { local who=$1; shift; curl -s -u "$who" -H "$H" "$@"; }             # tenant api call, body out
tcode() { local who=$1; shift; code -u "$who" -H "$H" "$@"; }                # tenant api call, status out
mk_template() { tcode "$1" -X POST $BASE/api/v1/templates -d "{\"name\":\"$2\",\"channel\":\"$3\",\"subject\":\"$4\",\"body\":\"$5\"}"; }
send() { # creds recipient [extra json fields, e.g. ,"scheduledAt":"..."] -> id
  tapi "$1" -X POST $BASE/api/v1/notifications -d "{\"channel\":\"EMAIL\",\"templateName\":\"welcome\",\"recipient\":\"$2\",\"variables\":{\"name\":\"Ada\"}${3:-}}" | jf 'd["id"]'
}
nstatus() { tapi "$1" $BASE/api/v1/notifications/$2 | jf 'd["notification"]["status"]'; }
wait_status() { # creds id want [timeout_s]
  local end=$((SECONDS + ${4:-30})) s=""
  while [ $SECONDS -lt $end ]; do s=$(nstatus "$1" "$2"); [ "$s" = "$3" ] && { echo "$s"; return 0; }; sleep 0.5; done
  echo "$s"; return 1
}
count_status() { tapi "$1" "$BASE/api/v1/notifications?status=$2&size=100" | jf 'd["page"]["totalElements"]'; }

# --- 0. server is up ---------------------------------------------------------------------------------
section "0. server reachable"
check "server answers (401 without credentials)" "$(code $BASE/api/v1/tenants)" 401

# --- 1. authentication & RBAC ------------------------------------------------------------------------
section "1. authentication and RBAC"
mk_tenant a 100 100 3
mk_tenant b 100 100 3
check "wrong password -> 401" "$(code -u "e2e-a-$RUN:nope" $BASE/api/v1/notifications)" 401
check "unknown user -> 401" "$(code -u "ghost:ghost" $BASE/api/v1/notifications)" 401
check "tenant admin cannot list tenants -> 403" "$(tcode "$T_a" $BASE/api/v1/tenants)" 403
check "tenant admin cannot create tenants -> 403" "$(tcode "$T_a" -X POST $BASE/api/v1/tenants -d '{}')" 403
check "tenant admin cannot read global limits -> 403" "$(tcode "$T_a" $BASE/api/v1/limits/global)" 403
check "platform admin cannot send notifications -> 403" "$(code -u "$PLATFORM" $BASE/api/v1/notifications)" 403
check "platform admin cannot manage templates -> 403" "$(code -u "$PLATFORM" $BASE/api/v1/templates)" 403
check "platform admin cannot view reports -> 403" "$(code -u "$PLATFORM" $BASE/api/v1/reports/delivery)" 403
check "unknown path -> 404" "$(tcode "$T_a" $BASE/api/v1/nope)" 404
check "unknown report path -> 404" "$(tcode "$T_a" $BASE/api/v1/reports)" 404
check "wrong method (DELETE /notifications) -> 405" "$(tcode "$T_a" -X DELETE $BASE/api/v1/notifications)" 405
check "wrong method (GET on cancel) -> 405" "$(tcode "$T_a" $BASE/api/v1/notifications/x/cancel)" 405
check "wrong method for platform admin (DELETE tenant) -> 405" "$(code -u "$PLATFORM" -X DELETE $BASE/api/v1/tenants/1)" 405
check "unsupported media type -> 415" "$(code -u "$T_a" -H 'Content-Type: text/plain' -X POST $BASE/api/v1/notifications -d x)" 415
check "error bodies are RFC 7807 problem+json" "$(curl -s -o /dev/null -w '%{content_type}' -u "$T_a" $BASE/api/v1/nope | cut -d';' -f1)" application/problem+json

# --- 2. tenant lifecycle (platform admin) ------------------------------------------------------------
section "2. tenant lifecycle"
DUP='{"name":"e2e-a-'$RUN'","adminUsername":"other-admin-'$RUN'","adminPassword":"e2e-password-1"}'
check "duplicate tenant name -> 409" "$(code -u "$PLATFORM" -H "$H" -X POST $BASE/api/v1/tenants -d "$DUP")" 409
DUPU='{"name":"unique-'$RUN'","adminUsername":"e2e-a-'$RUN'","adminPassword":"e2e-password-1"}'
check "duplicate admin username -> 409" "$(code -u "$PLATFORM" -H "$H" -X POST $BASE/api/v1/tenants -d "$DUPU")" 409
SHORT='{"name":"short-'$RUN'","adminUsername":"short-'$RUN'","adminPassword":"1234"}'
check "password shorter than 8 -> 400" "$(code -u "$PLATFORM" -H "$H" -X POST $BASE/api/v1/tenants -d "$SHORT")" 400
check "rate 0 -> 400" "$(code -u "$PLATFORM" -H "$H" -X POST $BASE/api/v1/tenants -d '{"name":"x'$RUN'","ratePerSecond":0,"adminUsername":"x'$RUN'","adminPassword":"e2e-password-1"}')" 400
check "GET tenant by id -> 200" "$(code -u "$PLATFORM" $BASE/api/v1/tenants/$ID_a)" 200
check "GET unknown tenant -> 404" "$(code -u "$PLATFORM" $BASE/api/v1/tenants/99999999)" 404
check "tenant list contains the tenant" "$(curl -s -u "$PLATFORM" $BASE/api/v1/tenants | python3 -c "import sys,json; print(any(t['id']==$ID_a for t in json.load(sys.stdin)))")" True
check "PATCH tenant limits" "$(curl -s -u "$PLATFORM" -H "$H" -X PATCH $BASE/api/v1/tenants/$ID_a -d '{"ratePerSecond":100,"burst":100,"maxAttempts":3}' | jf 'd["maxAttempts"]')" 3
check "PATCH invalid maxAttempts -> 400" "$(code -u "$PLATFORM" -H "$H" -X PATCH $BASE/api/v1/tenants/$ID_a -d '{"maxAttempts":99}')" 400

# --- 3. templates ------------------------------------------------------------------------------------
section "3. templates"
check "create EMAIL template v1 -> 201" "$(mk_template "$T_a" welcome EMAIL 'Hi {{name}}' 'Welcome {{name}}')" 201
check "create SMS template -> 201" "$(mk_template "$T_a" welcome SMS null 'Hi {{name}}')" 201
check "template missing body -> 400" "$(tcode "$T_a" -X POST $BASE/api/v1/templates -d '{"name":"x","channel":"EMAIL"}')" 400
T1=$(tapi "$T_a" $BASE/api/v1/templates | jf '[t["id"] for t in d if t["channel"]=="EMAIL"][0]')
check "GET template by id -> 200" "$(tcode "$T_a" $BASE/api/v1/templates/$T1)" 200
check "other tenant cannot GET template -> 404" "$(tcode "$T_b" $BASE/api/v1/templates/$T1)" 404
mk_template "$T_b" welcome EMAIL 'Hi {{name}}' 'Welcome {{name}}' >/dev/null

# --- 4. send, delivery, audit trail ------------------------------------------------------------------
section "4. send + delivery + audit trail"
ID1=$(send "$T_a" ada@example.com)
check "submitted (id returned)" "$([ -n "$ID1" ] && echo yes)" yes
check "reaches SENT" "$(wait_status "$T_a" $ID1 SENT 15)" SENT
D=$(tapi "$T_a" $BASE/api/v1/notifications/$ID1)
check "rendered body" "$(echo "$D" | jf 'd["notification"]["body"]')" "Welcome Ada"
check "rendered subject" "$(echo "$D" | jf 'd["notification"]["subject"]')" "Hi Ada"
check "audit trail PENDING>PROCESSING>SENT" "$(echo "$D" | jf '">".join(e["to"] for e in d["events"])')" "PENDING>PROCESSING>SENT"
check "one SUCCESS attempt" "$(echo "$D" | jf '[a["outcome"] for a in d["attempts"]]')" "['SUCCESS']"

# --- 5. template versioning -------------------------------------------------------------------------
section "5. template versioning"
check "second create of same name -> version 2" "$(tapi "$T_a" -X POST $BASE/api/v1/templates -d '{"name":"welcome","channel":"EMAIL","subject":"v2 {{name}}","body":"Hey {{name}}"}' | jf 'd["version"]')" 2
ID2=$(send "$T_a" v2@example.com)
wait_status "$T_a" $ID2 SENT 15 >/dev/null
check "new send uses latest version" "$(tapi "$T_a" $BASE/api/v1/notifications/$ID2 | jf 'd["notification"]["body"]')" "Hey Ada"
check "earlier notification body unchanged" "$(tapi "$T_a" $BASE/api/v1/notifications/$ID1 | jf 'd["notification"]["body"]')" "Welcome Ada"

# --- 6. validation matrix ---------------------------------------------------------------------------
section "6. validation and errors"
N='"channel":"EMAIL","templateName":"welcome"'
check "missing template variable -> 422" "$(tcode "$T_a" -X POST $BASE/api/v1/notifications -d "{$N,\"recipient\":\"a@b.com\",\"variables\":{}}")" 422
check "invalid email -> 400" "$(tcode "$T_a" -X POST $BASE/api/v1/notifications -d "{$N,\"recipient\":\"nope\",\"variables\":{\"name\":\"x\"}}")" 400
check "invalid phone for SMS -> 400" "$(tcode "$T_a" -X POST $BASE/api/v1/notifications -d '{"channel":"SMS","templateName":"welcome","recipient":"abc","variables":{"name":"x"}}')" 400
check "valid phone for SMS -> 202" "$(tcode "$T_a" -X POST $BASE/api/v1/notifications -d '{"channel":"SMS","templateName":"welcome","recipient":"+15550001111","variables":{"name":"x"}}')" 202
check "unknown template -> 404" "$(tcode "$T_a" -X POST $BASE/api/v1/notifications -d "{\"channel\":\"EMAIL\",\"templateName\":\"nope\",\"recipient\":\"a@b.com\",\"variables\":{}}")" 404
check "no template for channel (PUSH) -> 404" "$(tcode "$T_a" -X POST $BASE/api/v1/notifications -d '{"channel":"PUSH","templateName":"welcome","recipient":"tok","variables":{"name":"x"}}')" 404
check "empty body {} -> 400" "$(tcode "$T_a" -X POST $BASE/api/v1/notifications -d '{}')" 400
check "malformed JSON -> 400" "$(tcode "$T_a" -X POST $BASE/api/v1/notifications -d '{not json')" 400
check "unknown channel value -> 400" "$(tcode "$T_a" -X POST $BASE/api/v1/notifications -d '{"channel":"FAX","templateName":"welcome","recipient":"x"}')" 400
LONGKEY=$(python3 -c "print('k'*101)")
check "Idempotency-Key > 100 chars -> 400" "$(tcode "$T_a" -H "Idempotency-Key: $LONGKEY" -X POST $BASE/api/v1/notifications -d "{$N,\"recipient\":\"a@b.com\",\"variables\":{\"name\":\"x\"}}")" 400
check "scheduledAt > 365 days ahead -> 400" "$(tcode "$T_a" -X POST $BASE/api/v1/notifications -d "{$N,\"recipient\":\"a@b.com\",\"variables\":{\"name\":\"x\"},\"scheduledAt\":\"2099-01-01T00:00:00Z\"}")" 400
check "GET unknown notification -> 404" "$(tcode "$T_a" $BASE/api/v1/notifications/does-not-exist)" 404

# --- 7. idempotency ---------------------------------------------------------------------------------
section "7. idempotency"
BODY="{$N,\"recipient\":\"idem@example.com\",\"variables\":{\"name\":\"Ada\"}}"
R1=$(tapi "$T_a" -H "Idempotency-Key: idem-$RUN" -X POST $BASE/api/v1/notifications -d "$BODY" -w '\n%{http_code}')
check "first submit -> 202" "$(echo "$R1" | tail -1)" 202
IDEM=$(echo "$R1" | head -1 | jf 'd["id"]')
R2=$(tapi "$T_a" -H "Idempotency-Key: idem-$RUN" -X POST $BASE/api/v1/notifications -d "$BODY" -w '\n%{http_code}')
check "same key + same body -> 200" "$(echo "$R2" | tail -1)" 200
check "replay returns the same id" "$(echo "$R2" | head -1 | jf 'd["id"]')" "$IDEM"
check "same key + different body -> 409" "$(tcode "$T_a" -H "Idempotency-Key: idem-$RUN" -X POST $BASE/api/v1/notifications -d "${BODY/idem@/other@}")" 409
check "same key, other tenant -> new notification (keys are per tenant)" "$(tcode "$T_b" -H "Idempotency-Key: idem-$RUN" -X POST $BASE/api/v1/notifications -d "$BODY")" 202
# concurrent race: 12 parallel submits with one key must create exactly one notification
RK="race-$RUN"
for i in $(seq 1 12); do (tapi "$T_a" -H "Idempotency-Key: $RK" -X POST $BASE/api/v1/notifications -d "$BODY" | jf 'd["id"]' >> /tmp/e2e-race-$RUN.txt) & done; wait
check "12 concurrent same-key submits -> one id" "$(sort -u /tmp/e2e-race-$RUN.txt | wc -l | tr -d ' ')" 1
rm -f /tmp/e2e-race-$RUN.txt

# --- 8. scheduled sends -----------------------------------------------------------------------------
section "8. scheduled sends"
SCH=$(send "$T_a" sched@example.com ",\"scheduledAt\":\"$(future 5)\"")
sleep 1
check "scheduled (+5s) is still PENDING after 1s" "$(nstatus "$T_a" $SCH)" PENDING
check "scheduled is SENT once due" "$(wait_status "$T_a" $SCH SENT 20)" SENT
PAST=$(send "$T_a" past@example.com ",\"scheduledAt\":\"2020-01-01T00:00:00Z\"")
check "scheduledAt in the past sends immediately" "$(wait_status "$T_a" $PAST SENT 15)" SENT

# --- 9. cancel ---------------------------------------------------------------------------------------
section "9. cancel"
CAN=$(send "$T_a" cancel@example.com ",\"scheduledAt\":\"$(future 120)\"")
check "other tenant cannot cancel -> 404" "$(tcode "$T_b" -X POST $BASE/api/v1/notifications/$CAN/cancel)" 404
check "cancel PENDING -> 200" "$(tcode "$T_a" -X POST $BASE/api/v1/notifications/$CAN/cancel)" 200
check "status CANCELLED" "$(nstatus "$T_a" $CAN)" CANCELLED
check "cancel again -> 409" "$(tcode "$T_a" -X POST $BASE/api/v1/notifications/$CAN/cancel)" 409
check "cancel a SENT notification -> 409" "$(tcode "$T_a" -X POST $BASE/api/v1/notifications/$ID1/cancel)" 409
check "replay CANCELLED -> 409" "$(tcode "$T_a" -X POST $BASE/api/v1/notifications/$CAN/replay)" 409
check "cancel audit event recorded" "$(tapi "$T_a" $BASE/api/v1/notifications/$CAN | jf '">".join(e["to"] for e in d["events"])')" "PENDING>CANCELLED"

# --- 10. channel configuration ----------------------------------------------------------------------
section "10. channel config"
check "GET channels -> 200" "$(tcode "$T_a" $BASE/api/v1/channels)" 200
check "PUT unknown channel -> 400" "$(tcode "$T_a" -X PUT $BASE/api/v1/channels/FAX -d '{"enabled":true}')" 400
check "PUT missing enabled -> 400" "$(tcode "$T_a" -X PUT $BASE/api/v1/channels/EMAIL -d '{}')" 400
# queued row + channel disabled after queuing: must stay PENDING (no attempts) until re-enabled
QD=$(send "$T_a" queued@example.com ",\"scheduledAt\":\"$(future 3)\"")
check "disable EMAIL -> 200" "$(tcode "$T_a" -X PUT $BASE/api/v1/channels/EMAIL -d '{"enabled":false}')" 200
check "submit to disabled channel -> 409" "$(tcode "$T_a" -X POST $BASE/api/v1/notifications -d "{$N,\"recipient\":\"a@b.com\",\"variables\":{\"name\":\"x\"}}")" 409
sleep 6
check "queued row stays PENDING while channel disabled (due 3s ago)" "$(nstatus "$T_a" $QD)" PENDING
check "no attempt was made while disabled" "$(tapi "$T_a" $BASE/api/v1/notifications/$QD | jf 'len(d["attempts"])')" 0
check "re-enable with a senderId -> 200" "$(tcode "$T_a" -X PUT $BASE/api/v1/channels/EMAIL -d '{"enabled":true,"senderId":"noreply@acme.test"}')" 200
check "queued row is delivered after re-enable" "$(wait_status "$T_a" $QD SENT 15)" SENT
SND=$(send "$T_a" sender@example.com)
wait_status "$T_a" $SND SENT 15 >/dev/null
if [ -n "${APP_LOG:-}" ]; then
  check_true "senderId reached the provider (app log)" "$(grep -q "$SND.*(sender noreply@acme.test)" "$APP_LOG"; echo $?)"
else
  echo "  skip  senderId check (set APP_LOG=<app log path> to enable)"
fi

# --- 11. retries, DEAD, replay ----------------------------------------------------------------------
section "11. retries, permanent failure, DEAD, replay (real backoff, ~15-25s)"
PERM=$(send "$T_a" fail-permanent@example.com)
check "permanent failure -> DEAD" "$(wait_status "$T_a" $PERM DEAD 15)" DEAD
DP=$(tapi "$T_a" $BASE/api/v1/notifications/$PERM)
check "permanent failure: exactly 1 attempt (no retry)" "$(echo "$DP" | jf 'len(d["attempts"])')" 1
check "lastError recorded" "$(echo "$DP" | jf '"rejected" in (d["notification"]["lastError"] or "")')" True
TR=$(send "$T_a" fail-transient@example.com)
check "transient failures exhaust retries -> DEAD" "$(wait_status "$T_a" $TR DEAD 60)" DEAD
DT=$(tapi "$T_a" $BASE/api/v1/notifications/$TR)
check "3 attempts (maxAttempts=3), all TRANSIENT_FAILURE" "$(echo "$DT" | jf '[a["outcome"] for a in d["attempts"]]')" "['TRANSIENT_FAILURE', 'TRANSIENT_FAILURE', 'TRANSIENT_FAILURE']"
check "audit shows retry loop then DEAD" "$(echo "$DT" | jf '">".join(e["to"] for e in d["events"])')" "PENDING>PROCESSING>PENDING>PROCESSING>PENDING>PROCESSING>DEAD"
check "DEAD reason says retries exhausted" "$(echo "$DT" | jf '"retries exhausted" in d["events"][-1]["reason"]')" True
check "other tenant cannot replay -> 404" "$(tcode "$T_b" -X POST $BASE/api/v1/notifications/$TR/replay)" 404
check "replay DEAD -> 200" "$(tcode "$T_a" -X POST $BASE/api/v1/notifications/$TR/replay)" 200
check "replayed goes DEAD again after a fresh budget" "$(wait_status "$T_a" $TR DEAD 60)" DEAD
DR=$(tapi "$T_a" $BASE/api/v1/notifications/$TR)
check "6 attempts numbered 1..6 (history kept, budget restarted)" "$(echo "$DR" | jf '[a["attemptNo"] for a in d["attempts"]]')" "[1, 2, 3, 4, 5, 6]"
check "audit records the replay" "$(echo "$DR" | jf 'any("replayed" in (e["reason"] or "") for e in d["events"])')" True
check "replay a SENT notification -> 409" "$(tcode "$T_a" -X POST $BASE/api/v1/notifications/$ID1/replay)" 409

# --- 12. per-tenant rate limit (throttled, never dropped) -------------------------------------------
section "12. rate limit: 1/s, burst 3"
mk_tenant r 1 3 3
mk_template "$T_r" welcome EMAIL 'Hi {{name}}' 'Welcome {{name}}' >/dev/null
ITEMS=$(python3 -c "import json; print(json.dumps({'items':[{'notification':{'channel':'EMAIL','templateName':'welcome','recipient':'r%d@example.com'%i,'variables':{'name':'x'}}} for i in range(8)]}))")
check "batch of 8 accepted" "$(tapi "$T_r" -X POST $BASE/api/v1/notifications/batch -d "$ITEMS" | jf 'd["accepted"]')" 8
sleep 1.3
S=$(count_status "$T_r" SENT); P=$(count_status "$T_r" PENDING)
check_true "after ~1.3s only burst+refill delivered (3..5, got $S)" "$([ "$S" -ge 3 ] && [ "$S" -le 5 ] && echo 0 || echo 1)"
check_true "the rest is still PENDING, not dropped (got $P)" "$([ "$P" -ge 3 ] && echo 0 || echo 1)"
END=$((SECONDS+25)); while [ $SECONDS -lt $END ] && [ "$(count_status "$T_r" SENT)" != 8 ]; do sleep 1; done
check "all 8 eventually delivered" "$(count_status "$T_r" SENT)" 8

# --- 13. fairness: noisy tenant cannot starve a quiet one -------------------------------------------
section "13. fairness"
mk_tenant n 100000 100000 3
mk_tenant q 100000 100000 3
mk_template "$T_n" welcome EMAIL 'Hi {{name}}' 'Welcome {{name}}' >/dev/null
mk_template "$T_q" welcome EMAIL 'Hi {{name}}' 'Welcome {{name}}' >/dev/null
big() { python3 -c "import json; print(json.dumps({'items':[{'notification':{'channel':'EMAIL','templateName':'welcome','recipient':'n$1-%d@example.com'%i,'variables':{'name':'x'}}} for i in range(100)]}))"; }
tapi "$T_n" -X POST $BASE/api/v1/notifications/batch -d "$(big a)" >/dev/null
tapi "$T_n" -X POST $BASE/api/v1/notifications/batch -d "$(big b)" >/dev/null
for i in 1 2 3; do send "$T_q" q$i@example.com >/dev/null; done
END=$((SECONDS+6)); while [ $SECONDS -lt $END ] && [ "$(count_status "$T_q" SENT)" != 3 ]; do sleep 0.3; done
check "quiet tenant's 3 messages delivered within 6s" "$(count_status "$T_q" SENT)" 3
NP=$(count_status "$T_n" PENDING)
check_true "...while the noisy tenant still has a backlog (pending=$NP)" "$([ "$NP" -gt 0 ] && echo 0 || echo 1)"
END=$((SECONDS+40)); while [ $SECONDS -lt $END ] && [ "$(count_status "$T_n" SENT)" != 200 ]; do sleep 1; done
check "noisy tenant's 200 all delivered eventually" "$(count_status "$T_n" SENT)" 200

# --- 14. deactivate / reactivate tenant -------------------------------------------------------------
section "14. deactivate / reactivate"
mk_tenant d 100 100 3
mk_template "$T_d" welcome EMAIL 'Hi {{name}}' 'Welcome {{name}}' >/dev/null
DQ=$(send "$T_d" dq@example.com ",\"scheduledAt\":\"$(future 3)\"")
check "deactivate -> 200" "$(code -u "$PLATFORM" -H "$H" -X PATCH $BASE/api/v1/tenants/$ID_d -d '{"active":false}')" 200
check "submit while deactivated -> 403" "$(tcode "$T_d" -X POST $BASE/api/v1/notifications -d "{$N,\"recipient\":\"a@b.com\",\"variables\":{\"name\":\"x\"}}")" 403
sleep 6
check "queued row is not sent while deactivated" "$(nstatus "$T_d" $DQ)" PENDING
check "reactivate -> 200" "$(code -u "$PLATFORM" -H "$H" -X PATCH $BASE/api/v1/tenants/$ID_d -d '{"active":true}')" 200
check "queued row delivered after reactivation" "$(wait_status "$T_d" $DQ SENT 15)" SENT

# --- 15. global limit ------------------------------------------------------------------------------
section "15. global limit (restored afterwards)"
ORIG=$(curl -s -u "$PLATFORM" $BASE/api/v1/limits/global)
restore() { curl -s -o /dev/null -u "$PLATFORM" -H "$H" -X PUT $BASE/api/v1/limits/global -d "$ORIG"; }
trap restore EXIT
check "PUT rate 0 -> 400" "$(code -u "$PLATFORM" -H "$H" -X PUT $BASE/api/v1/limits/global -d '{"ratePerSecond":0,"burst":1}')" 400
check "PUT 5/s burst 5 -> 200" "$(code -u "$PLATFORM" -H "$H" -X PUT $BASE/api/v1/limits/global -d '{"ratePerSecond":5,"burst":5}')" 200
check "GET reflects the change" "$(curl -s -u "$PLATFORM" $BASE/api/v1/limits/global | jf 'd["ratePerSecond"]')" 5
mk_tenant g 100000 100000 3
mk_template "$T_g" welcome EMAIL 'Hi {{name}}' 'Welcome {{name}}' >/dev/null
GI=$(python3 -c "import json; print(json.dumps({'items':[{'notification':{'channel':'EMAIL','templateName':'welcome','recipient':'g%d@example.com'%i,'variables':{'name':'x'}}} for i in range(30)]}))")
tapi "$T_g" -X POST $BASE/api/v1/notifications/batch -d "$GI" >/dev/null
sleep 1.5
GS=$(count_status "$T_g" SENT)
check_true "global limit throttles a tenant with a huge own limit (sent=$GS of 30 after 1.5s, expect 5..14)" "$([ "$GS" -ge 5 ] && [ "$GS" -le 14 ] && echo 0 || echo 1)"
restore
check "restored" "$(curl -s -u "$PLATFORM" $BASE/api/v1/limits/global | jf 'd["ratePerSecond"]')" "$(echo "$ORIG" | jf 'd["ratePerSecond"]')"
END=$((SECONDS+20)); while [ $SECONDS -lt $END ] && [ "$(count_status "$T_g" SENT)" != 30 ]; do sleep 1; done
check "all 30 delivered after restoring the limit" "$(count_status "$T_g" SENT)" 30

# --- 16. batch ------------------------------------------------------------------------------------
section "16. batch"
B='{"items":[
 {"idempotencyKey":"bk-'$RUN'","notification":{"channel":"EMAIL","templateName":"welcome","recipient":"b1@example.com","variables":{"name":"x"}}},
 {"idempotencyKey":"bk-'$RUN'","notification":{"channel":"EMAIL","templateName":"welcome","recipient":"b1@example.com","variables":{"name":"x"}}},
 {"notification":{"channel":"EMAIL","templateName":"nope","recipient":"b2@example.com"}},
 {"notification":{"channel":"EMAIL","templateName":"welcome","recipient":"bad-email","variables":{"name":"x"}}},
 {"idempotencyKey":"bk-'$RUN'","notification":{"channel":"EMAIL","templateName":"welcome","recipient":"different@example.com","variables":{"name":"x"}}}]}'
BR=$(tapi "$T_a" -X POST $BASE/api/v1/notifications/batch -d "$B")
check "batch: 1 accepted" "$(echo "$BR" | jf 'd["accepted"]')" 1
check "batch: 1 duplicate" "$(echo "$BR" | jf 'd["duplicates"]')" 1
check "batch: 3 rejected" "$(echo "$BR" | jf 'd["rejected"]')" 3
check "batch per-item statuses" "$(echo "$BR" | jf '[r["status"] for r in d["results"]]')" "[202, 200, 404, 400, 409]"
check "batch empty -> 400" "$(tcode "$T_a" -X POST $BASE/api/v1/notifications/batch -d '{"items":[]}')" 400
BIG101=$(python3 -c "import json; item={'notification':{'channel':'EMAIL','templateName':'welcome','recipient':'a@b.com'}}; print(json.dumps({'items':[item]*101}))")
check "batch of 101 -> 400" "$(tcode "$T_a" -X POST $BASE/api/v1/notifications/batch -d "$BIG101")" 400
check "batch item missing fields -> 400 (whole request)" "$(tcode "$T_a" -X POST $BASE/api/v1/notifications/batch -d '{"items":[{"notification":{}}]}')" 400

# --- 17. list, pagination, filters -----------------------------------------------------------------
section "17. list / pagination / filters"
sleep 2
L=$(tapi "$T_a" "$BASE/api/v1/notifications?size=2&page=0")
check "page shape has content + page{}" "$(echo "$L" | jf '"content" in d and "totalElements" in d["page"]')" True
check "size=2 returns 2" "$(echo "$L" | jf 'len(d["content"])')" 2
check "size > 100 is clamped to 100" "$(tapi "$T_a" "$BASE/api/v1/notifications?size=1000" | jf 'd["page"]["size"]')" 100
check "page beyond the end is empty" "$(tapi "$T_a" "$BASE/api/v1/notifications?page=9999" | jf 'len(d["content"])')" 0
check "filter status=CANCELLED" "$(tapi "$T_a" "$BASE/api/v1/notifications?status=CANCELLED" | jf 'd["page"]["totalElements"]')" 1
check "filter channel=SMS" "$(tapi "$T_a" "$BASE/api/v1/notifications?channel=SMS" | jf 'all(n["channel"]=="SMS" for n in d["content"]) and d["page"]["totalElements"]>=1')" True
check "filter to=2000-01-01 -> none" "$(tapi "$T_a" "$BASE/api/v1/notifications?to=2000-01-01T00:00:00Z" | jf 'd["page"]["totalElements"]')" 0
check "filter from > to -> 400" "$(tcode "$T_a" "$BASE/api/v1/notifications?from=2999-01-01T00:00:00Z&to=2000-01-01T00:00:00Z")" 400
check "invalid status value -> 400" "$(tcode "$T_a" "$BASE/api/v1/notifications?status=WAT")" 400
check "newest first" "$(tapi "$T_a" "$BASE/api/v1/notifications?size=5" | jf '[n["createdAt"] for n in d["content"]]==sorted([n["createdAt"] for n in d["content"]],reverse=True)')" True

# --- 18. reports -----------------------------------------------------------------------------------
section "18. delivery report"
RP=$(tapi "$T_a" $BASE/api/v1/reports/delivery)
check "report has SENT count" "$(echo "$RP" | jf 'd["byStatus"]["SENT"]>=5')" True
check "report has DEAD count" "$(echo "$RP" | jf 'd["byStatus"]["DEAD"]>=2')" True
check "report has CANCELLED count" "$(echo "$RP" | jf 'd["byStatus"]["CANCELLED"]')" 1
check "report byTemplate lists welcome" "$(echo "$RP" | jf '[t["template"] for t in d["byTemplate"]]')" "['welcome']"
check "report successRate is SENT/(SENT+DEAD)" "$(echo "$RP" | jf 'abs(d["successRate"]-d["byStatus"]["SENT"]/(d["byStatus"]["SENT"]+d["byStatus"]["DEAD"]))<1e-9')" True
check "top failures ranked by count, normalised" "$(echo "$RP" | jf 'd["topFailures"][0]["count"]>=d["topFailures"][-1]["count"] and all(f["reason"]==f["reason"].lower() for f in d["topFailures"])')" True
check "report channel filter (PUSH -> empty)" "$(tapi "$T_a" "$BASE/api/v1/reports/delivery?channel=PUSH" | jf 'd["total"]')" 0
check "report from>to -> 400" "$(tcode "$T_a" "$BASE/api/v1/reports/delivery?from=2999-01-01T00:00:00Z&to=2000-01-01T00:00:00Z")" 400
check "report is tenant-scoped (b's total == b's own notification count)" "$(tapi "$T_b" $BASE/api/v1/reports/delivery | jf 'd["total"]')" "$(tapi "$T_b" "$BASE/api/v1/notifications?size=100" | jf 'd["page"]["totalElements"]')"

# --- 19. cross-tenant isolation --------------------------------------------------------------------
section "19. cross-tenant isolation"
check "tenant b cannot GET tenant a's notification -> 404" "$(tcode "$T_b" $BASE/api/v1/notifications/$ID1)" 404
check "tenant b's list does not contain a's ids" "$(tapi "$T_b" "$BASE/api/v1/notifications?size=100" | jf 'not any(n["id"]=="'$ID1'" for n in d["content"])')" True

# --- 20. server-side health (log must be clean) ---------------------------------------------------
section "20. app log has no unhandled errors"
if [ -n "${APP_LOG:-}" ]; then
  check "no 'Unhandled error' entries in the app log" "$(grep -c 'Unhandled error' "$APP_LOG")" 0
  # Postgres/Hibernate logs the unique-constraint hit of the concurrent same-key race (section 7) at ERROR even though
  # the service handles it and replays the winner; that one known line is ignored, anything else is a failure.
  check "no unexpected ERROR-level entries in the app log" "$(grep ' ERROR ' "$APP_LOG" | grep -vc 'uq_notification_idem')" 0
else
  echo "  skip  log checks (set APP_LOG=<app log path> to enable)"
fi

# --- summary ---------------------------------------------------------------------------------------
echo
echo "=================================================="
echo "E2E RESULT: $PASS passed, $FAIL failed"
[ "$FAIL" = 0 ] && echo "E2E OK" || { echo "E2E FAILED"; exit 1; }
