#!/usr/bin/env bash
#
# Customer -> store -> points core smoke over the real HTTP API.
#
# Only runtime OpenAPI operations are called; nothing here reads or writes the database directly.
# Every asynchronous step uses a bounded poll and a missed deadline is a failure, never a pass.
# The script exits non-zero on the first failed expectation.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

require_cmd curl python3
load_identity_env
app_healthy || fail "The application is not healthy. Run scripts/demo/start.sh first."

STORE_ID="d1000000-0000-4000-8000-000000000001"
MENU_ID="d2000000-0000-4000-8000-000000000001"
OPTION_ID="d3000000-0000-4000-8000-000000000001"
POINT_ACCOUNT_ID="d7000000-0000-4000-8000-000000000001"
PAYMENT_METHOD_ID="d9000000-0000-4000-8000-000000000001"
# The deterministic bootstrap policy is 100 bps FLOOR. The order below is 4,500 + 500 KRW.
EXPECTED_ACCRUAL_KRW=50
RUN_ID="$(date +%s)"
STEP=0
BODY_FILE="${DEMO_RUNTIME_DIR}/smoke-body.json"

# call <name> <expected-status> <method> <path> <token> [json-body] [idempotency-key]
call() {
  local name="$1" expected="$2" method="$3" path="$4" token="$5" body="${6:-}" key="${7:-}"
  STEP=$((STEP + 1))
  local correlation="local-demo-${RUN_ID}-${STEP}"
  local args=(-sS -o "$BODY_FILE" -w '%{http_code}' -X "$method"
    -H "Authorization: Bearer ${token}" -H "X-Correlation-Id: ${correlation}" -m 20)
  [ -n "$body" ] && args+=(-H "Content-Type: application/json" -d "$body")
  [ -n "$key" ] && args+=(-H "Idempotency-Key: ${key}")
  local status; status="$(curl "${args[@]}" "${DEMO_APP_BASE_URL}${path}")"
  if [ "$status" != "$expected" ]; then
    printf '\033[0;31m[fail]\033[0m %-46s expected %s got %s (correlationId=%s)\n' \
      "$name" "$expected" "$status" "$correlation" >&2
    head -c 600 "$BODY_FILE" >&2; echo >&2
    exit 1
  fi
  printf '\033[0;32m[ ok ]\033[0m %-46s %s  correlationId=%s\n' "$name" "$status" "$correlation"
}

json() { python3 -c "import json,sys;d=json.load(open('$BODY_FILE'));print(eval(sys.argv[1],{'d':d}) if 1 else '')" "$1"; }

log "customer discovery"
call "nearby stores"        200 GET  "/stores/nearby?latitude=37.5&longitude=127.0&radiusMeters=1000" "$CUSTOMER_TOKEN"
python3 -c "
import json;d=json.load(open('$BODY_FILE'))
ids=[i['storeId'] for i in d['items']]
assert '$STORE_ID' in ids, 'demo store missing from nearby results: %s' % ids
assert all('distanceMeters' in i for i in d['items'])
print('       nearby returned %d store(s), demo store present' % len(d['items']))
"
call "store menus"          200 GET  "/stores/${STORE_ID}/menus" "$CUSTOMER_TOKEN"
python3 -c "
import json;d=json.load(open('$BODY_FILE'))
names={i['menuId']: i['available'] for i in d['items']}
assert names.get('$MENU_ID') is True, 'demo menu must be available'
assert False in names.values(), 'fixture should include an unavailable menu'
print('       menus returned %d item(s) including an unavailable one' % len(d['items']))
"
call "store pickup slots"   200 GET  "/stores/${STORE_ID}/pickup-slots" "$CUSTOMER_TOKEN"
SLOT_ID="$(python3 -c "
import json;d=json.load(open('$BODY_FILE'))
assert d['items'], 'no open pickup slot; run stop.sh --reset then start.sh + seed.sh'
print(d['items'][0]['pickupSlotId'])
")"
log "using pickup slot ${SLOT_ID}"

log "loyalty baseline"
call "point account baseline" 200 GET "/point-accounts/${POINT_ACCOUNT_ID}" "$CUSTOMER_TOKEN"
POINT_BALANCE_BEFORE="$(python3 -c "import json;print(json.load(open('$BODY_FILE'))['availablePointsKrw'])")"
[[ "$POINT_BALANCE_BEFORE" =~ ^[0-9]+$ ]] || fail "Point account baseline was not a non-negative integer."
call "point transactions baseline" 200 GET "/point-accounts/${POINT_ACCOUNT_ID}/transactions?limit=100" "$CUSTOMER_TOKEN"
POINT_TRANSACTION_COUNT_BEFORE="$(python3 -c "import json;print(len(json.load(open('$BODY_FILE'))['items']))")"
[[ "$POINT_TRANSACTION_COUNT_BEFORE" =~ ^[0-9]+$ ]] || fail "Point ledger baseline could not be read."
ok "point baseline ${POINT_BALANCE_BEFORE} KRW across ${POINT_TRANSACTION_COUNT_BEFORE} visible transaction(s)"

log "ordering"
ORDER_KEY="demo-order-${RUN_ID}"
ORDER_BODY="{\"storeId\":\"${STORE_ID}\",\"pickupSlotId\":\"${SLOT_ID}\",\"lines\":[{\"menuId\":\"${MENU_ID}\",\"optionIds\":[\"${OPTION_ID}\"],\"quantity\":1}],\"pointsToUseKrw\":0}"
call "create order"         201 POST "/orders" "$CUSTOMER_TOKEN" "$ORDER_BODY" "$ORDER_KEY"
ORDER_ID="$(python3 -c "import json;print(json.load(open('$BODY_FILE'))['order']['orderId'])")"
log "order ${ORDER_ID}"

# Idempotency: the exact same key and payload must replay the stored response, not create a second order.
call "exact replay is idempotent" 201 POST "/orders" "$CUSTOMER_TOKEN" "$ORDER_BODY" "$ORDER_KEY"
REPLAY_ORDER_ID="$(python3 -c "import json;print(json.load(open('$BODY_FILE'))['order']['orderId'])")"
[ "$REPLAY_ORDER_ID" = "$ORDER_ID" ] || fail "Replay returned a different order: ${REPLAY_ORDER_ID}"
ok "replay returned the original order"

CHANGED_BODY="{\"storeId\":\"${STORE_ID}\",\"pickupSlotId\":\"${SLOT_ID}\",\"lines\":[{\"menuId\":\"${MENU_ID}\",\"optionIds\":[],\"quantity\":2}],\"pointsToUseKrw\":0}"
call "same key changed payload is 409" 409 POST "/orders" "$CUSTOMER_TOKEN" "$CHANGED_BODY" "$ORDER_KEY"

log "payment"
call "confirm payment"      200 POST "/orders/${ORDER_ID}/payment-confirmations" "$CUSTOMER_TOKEN" \
  "{\"paymentMethodId\":\"${PAYMENT_METHOD_ID}\"}" "demo-pay-${RUN_ID}"

log "store fulfilment"
for target in ACCEPTED PREPARING READY COMPLETED; do
  call "store transition ${target}" 200 PATCH "/store-orders/${ORDER_ID}/status" "$STORE_OWNER_TOKEN" \
    "{\"targetState\":\"${target}\",\"reason\":null}" "demo-${target}-${RUN_ID}"
done

log "customer read-back"
call "customer order read"  200 GET  "/orders/${ORDER_ID}" "$CUSTOMER_TOKEN"
python3 -c "
import json;d=json.load(open('$BODY_FILE'))
# GET /orders/{id} returns the order representation directly, unlike the creation envelope.
order=d.get('order', d)
state=order['state']
assert state=='COMPLETED', 'expected COMPLETED, got %s' % state
print('       order state %s' % state)
"

log "loyalty accrual"
ACCRUAL_SOURCE="order:${ORDER_ID}:completion-accrual:transaction"
ACCRUAL_DEADLINE=$(( SECONDS + 60 ))
ACCRUAL_FOUND="no"
while (( SECONDS < ACCRUAL_DEADLINE )); do
  call "point accrual poll" 200 GET "/point-accounts/${POINT_ACCOUNT_ID}/transactions?limit=100" "$CUSTOMER_TOKEN"
  ACCRUAL_STATE="$(python3 -c "import json;d=json.load(open('$BODY_FILE'));matches=[item for item in d['items'] if item['sourceReference']=='$ACCRUAL_SOURCE'];print('FOUND' if len(matches)==1 and matches[0]['type']=='ACCRUAL' and matches[0]['amountKrw']==$EXPECTED_ACCRUAL_KRW else ('MISSING' if not matches else 'INVALID'))")"
  case "$ACCRUAL_STATE" in
    FOUND) ACCRUAL_FOUND="yes"; break ;;
    INVALID) fail "Completion accrual ledger row for ${ORDER_ID} had an unexpected type or amount." ;;
    MISSING) sleep 2 ;;
    *) fail "Could not classify completion accrual for ${ORDER_ID}." ;;
  esac
done
[ "$ACCRUAL_FOUND" = "yes" ] || fail "Timed out waiting for the completion accrual transaction for ${ORDER_ID}."

call "point account after accrual" 200 GET "/point-accounts/${POINT_ACCOUNT_ID}" "$CUSTOMER_TOKEN"
python3 -c "import json;after=json.load(open('$BODY_FILE'))['availablePointsKrw'];expected=int('$POINT_BALANCE_BEFORE') + $EXPECTED_ACCRUAL_KRW;assert after == expected, 'expected availablePointsKrw %d after accrual, got %d' % (expected, after);print('       completion accrual ${EXPECTED_ACCRUAL_KRW} KRW and point balance delta verified')"

log "authorization failures"
call "no token is 401"            401 GET "/stores/${STORE_ID}/menus" ""
call "malformed token is 401"     401 GET "/stores/${STORE_ID}/menus" "not-a-real-jwt"
call "other store owner denied"   403 GET "/store-orders/${ORDER_ID}" "$OTHER_STORE_OWNER_TOKEN"

rm -f "$BODY_FILE"
echo
ok "core smoke flow completed"
cat <<EOF

  order            ${ORDER_ID}
  store            ${STORE_ID}
  point account    ${POINT_ACCOUNT_ID}
  correlation ids  local-demo-${RUN_ID}-1 .. -${STEP}

EOF
