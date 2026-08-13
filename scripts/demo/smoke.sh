#!/usr/bin/env bash
#
# Customer -> one-time payment -> store -> points -> refund smoke over the real HTTP API.
#
# Only runtime OpenAPI operations are called; nothing here reads or writes the database directly.
# Every asynchronous step uses a bounded poll and a missed deadline is a failure, never a pass.
# The script exits non-zero on the first failed expectation.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

SMOKE_CHECKPOINT="full"
case "${1:-}" in
  "") ;;
  --customer-checkpoint) SMOKE_CHECKPOINT="customer" ;;
  *) fail "Unknown argument: $1" ;;
esac
[ "$#" -le 1 ] || fail "Unknown argument: $2"

require_cmd curl python3
load_identity_env
app_healthy || fail "The application is not healthy. Run scripts/demo/start.sh first."

STORE_ID="d1000000-0000-4000-8000-000000000001"
MENU_ID="d2000000-0000-4000-8000-000000000001"
OPTION_ID="d3000000-0000-4000-8000-000000000001"
POINT_ACCOUNT_ID="d7000000-0000-4000-8000-000000000001"
# The deterministic bootstrap policy is 100 bps FLOOR. The order below is two 5,000 KRW units.
EXPECTED_ACCRUAL_KRW=100
RUN_ID="$(date +%s)"
STEP=0
BODY_FILE="${DEMO_RUNTIME_DIR}/smoke-body.json"
HEADERS_FILE="${DEMO_RUNTIME_DIR}/smoke-headers.txt"
CUSTOMER_LOGIN_ID="demo.customer"
CUSTOMER_PASSWORD="local demo customer password 123!"
CUSTOMER_AUTH="customer-session"
CUSTOMER_CSRF_AUTH="customer-csrf"
CUSTOMER_XSRF=""
CUSTOMER_SESSION=""
MERCHANT_LOGIN_ID="demo.merchant"
MERCHANT_INITIAL_PASSWORD="local demo merchant temporary password 123!"
MERCHANT_PASSWORD="local demo merchant password changed 123!"
OTHER_MERCHANT_LOGIN_ID="demo.othermerchant"
OTHER_MERCHANT_PASSWORD="local demo other merchant password 123!"
MERCHANT_AUTH="merchant-session"
MERCHANT_CSRF_AUTH="merchant-csrf"
OTHER_MERCHANT_AUTH="other-merchant-session"
MERCHANT_XSRF=""
MERCHANT_SESSION=""
OTHER_MERCHANT_SESSION=""

# call <name> <expected-status> <method> <path> <token> [json-body] [idempotency-key]
call() {
  local name="$1" expected="$2" method="$3" path="$4" token="$5" body="${6:-}" key="${7:-}"
  STEP=$((STEP + 1))
  local correlation="local-demo-${RUN_ID}-${STEP}"
  local args=(-sS -D "$HEADERS_FILE" -o "$BODY_FILE" -w '%{http_code}' -X "$method"
    -H "X-Correlation-Id: ${correlation}" -m 20)
  if [ "$token" = "$CUSTOMER_AUTH" ]; then
    args+=(-H "Cookie: BEANFLOW_CUSTOMER_SESSION=${CUSTOMER_SESSION}; BEANFLOW_CUSTOMER_XSRF=${CUSTOMER_XSRF}")
    case "$method" in
      GET|HEAD|OPTIONS) ;;
      *) args+=(-H "X-BEANFLOW-CSRF: ${CUSTOMER_XSRF}") ;;
    esac
  elif [ "$token" = "$CUSTOMER_CSRF_AUTH" ]; then
    args+=(-H "Cookie: BEANFLOW_CUSTOMER_XSRF=${CUSTOMER_XSRF}" -H "X-BEANFLOW-CSRF: ${CUSTOMER_XSRF}")
  elif [ "$token" = "$MERCHANT_AUTH" ]; then
    args+=(-H "Cookie: BEANFLOW_MERCHANT_SESSION=${MERCHANT_SESSION}; BEANFLOW_MERCHANT_XSRF=${MERCHANT_XSRF}")
    case "$method" in
      GET|HEAD|OPTIONS) ;;
      *) args+=(-H "X-BEANFLOW-CSRF: ${MERCHANT_XSRF}") ;;
    esac
  elif [ "$token" = "$OTHER_MERCHANT_AUTH" ]; then
    args+=(-H "Cookie: BEANFLOW_MERCHANT_SESSION=${OTHER_MERCHANT_SESSION}; BEANFLOW_MERCHANT_XSRF=${MERCHANT_XSRF}")
    case "$method" in
      GET|HEAD|OPTIONS) ;;
      *) args+=(-H "X-BEANFLOW-CSRF: ${MERCHANT_XSRF}") ;;
    esac
  elif [ "$token" = "$MERCHANT_CSRF_AUTH" ]; then
    args+=(-H "Cookie: BEANFLOW_MERCHANT_XSRF=${MERCHANT_XSRF}" -H "X-BEANFLOW-CSRF: ${MERCHANT_XSRF}")
  elif [ -n "$token" ]; then
    args+=(-H "Authorization: Bearer ${token}")
  fi
  [ -n "$body" ] && args+=(-H "Content-Type: application/json" -d "$body")
  [ -n "$key" ] && args+=(-H "Idempotency-Key: ${key}")
  local status; status="$(curl "${args[@]}" "${DEMO_APP_BASE_URL}${path}")"
  if [[ "|${expected}|" != *"|${status}|"* ]]; then
    printf '\033[0;31m[fail]\033[0m %-46s expected %s got %s (correlationId=%s)\n' \
      "$name" "$expected" "$status" "$correlation" >&2
    head -c 600 "$BODY_FILE" >&2; echo >&2
    exit 1
  fi
  printf '\033[0;32m[ ok ]\033[0m %-46s %s  correlationId=%s\n' "$name" "$status" "$correlation"
}

json() { python3 -c "import json,sys;d=json.load(open('$BODY_FILE'));print(eval(sys.argv[1],{'d':d}) if 1 else '')" "$1"; }

cookie_from_headers() {
  local name="$1"
  python3 -c "import re,sys; text=open(sys.argv[1]).read(); m=re.search(r'(?im)^set-cookie:\s*'+re.escape(sys.argv[2])+r'=([^;\r\n]+)', text); assert m, 'missing '+sys.argv[2]+' cookie'; print(m.group(1))" "$HEADERS_FILE" "$name"
}

log "customer browser login"
call "customer CSRF issue" 204 GET "/auth/customer/csrf" ""
CUSTOMER_XSRF="$(cookie_from_headers BEANFLOW_CUSTOMER_XSRF)"
call "customer Session login" 200 POST "/auth/customer/sessions" "$CUSTOMER_CSRF_AUTH" \
  "{\"loginId\":\"${CUSTOMER_LOGIN_ID}\",\"password\":\"${CUSTOMER_PASSWORD}\"}"
CUSTOMER_SESSION="$(cookie_from_headers BEANFLOW_CUSTOMER_SESSION)"
python3 -c "import json; d=json.load(open('$BODY_FILE')); assert d['actorType']=='CUSTOMER'; assert d['displayName']=='BeanFlow Demo Customer'"
ok "customer Session established without JWT paste"

log "customer discovery"
call "nearby stores"        200 GET  "/stores/nearby?latitude=37.5&longitude=127.0&radiusMeters=1000" "$CUSTOMER_AUTH"
python3 -c "
import json;d=json.load(open('$BODY_FILE'))
ids=[i['storeId'] for i in d['items']]
assert '$STORE_ID' in ids, 'demo store missing from nearby results: %s' % ids
assert all('distanceMeters' in i for i in d['items'])
print('       nearby returned %d store(s), demo store present' % len(d['items']))
"
call "store menus"          200 GET  "/stores/${STORE_ID}/menus" "$CUSTOMER_AUTH"
python3 -c "
import json;d=json.load(open('$BODY_FILE'))
names={i['menuId']: i['available'] for i in d['items']}
assert names.get('$MENU_ID') is True, 'demo menu must be available'
assert False in names.values(), 'fixture should include an unavailable menu'
print('       menus returned %d item(s) including an unavailable one' % len(d['items']))
"
call "store pickup slots"   200 GET  "/stores/${STORE_ID}/pickup-slots" "$CUSTOMER_AUTH"
SLOT_ID="$(python3 -c "
import json;d=json.load(open('$BODY_FILE'))
assert d['items'], 'no open pickup slot; run stop.sh --reset then start.sh + seed.sh'
print(d['items'][0]['pickupSlotId'])
")"
log "using pickup slot ${SLOT_ID}"

log "loyalty baseline"
call "point account baseline" 200 GET "/point-accounts/${POINT_ACCOUNT_ID}" "$CUSTOMER_AUTH"
POINT_BALANCE_BEFORE="$(python3 -c "import json;print(json.load(open('$BODY_FILE'))['availablePointsKrw'])")"
[[ "$POINT_BALANCE_BEFORE" =~ ^[0-9]+$ ]] || fail "Point account baseline was not a non-negative integer."
call "point transactions baseline" 200 GET "/point-accounts/${POINT_ACCOUNT_ID}/transactions?limit=100" "$CUSTOMER_AUTH"
POINT_TRANSACTION_COUNT_BEFORE="$(python3 -c "import json;print(len(json.load(open('$BODY_FILE'))['items']))")"
[[ "$POINT_TRANSACTION_COUNT_BEFORE" =~ ^[0-9]+$ ]] || fail "Point ledger baseline could not be read."
ok "point baseline ${POINT_BALANCE_BEFORE} KRW across ${POINT_TRANSACTION_COUNT_BEFORE} visible transaction(s)"

log "ordering"
ORDER_KEY="demo-order-${RUN_ID}"
ORDER_BODY="{\"storeId\":\"${STORE_ID}\",\"pickupSlotId\":\"${SLOT_ID}\",\"lines\":[{\"menuId\":\"${MENU_ID}\",\"optionIds\":[\"${OPTION_ID}\"],\"quantity\":2}],\"pointsToUseKrw\":0}"
call "create order"         201 POST "/orders" "$CUSTOMER_AUTH" "$ORDER_BODY" "$ORDER_KEY"
ORDER_ID="$(python3 -c "import json;print(json.load(open('$BODY_FILE'))['order']['orderId'])")"
ORDER_LINE_ID="$(python3 -c "import json;print(json.load(open('$BODY_FILE'))['order']['lines'][0]['orderLineId'])")"
log "order ${ORDER_ID}"

# Idempotency: the exact same key and payload must replay the stored response, not create a second order.
call "exact replay is idempotent" 201 POST "/orders" "$CUSTOMER_AUTH" "$ORDER_BODY" "$ORDER_KEY"
REPLAY_ORDER_ID="$(python3 -c "import json;print(json.load(open('$BODY_FILE'))['order']['orderId'])")"
[ "$REPLAY_ORDER_ID" = "$ORDER_ID" ] || fail "Replay returned a different order: ${REPLAY_ORDER_ID}"
ok "replay returned the original order"

CHANGED_BODY="{\"storeId\":\"${STORE_ID}\",\"pickupSlotId\":\"${SLOT_ID}\",\"lines\":[{\"menuId\":\"${MENU_ID}\",\"optionIds\":[],\"quantity\":2}],\"pointsToUseKrw\":0}"
call "same key changed payload is 409" 409 POST "/orders" "$CUSTOMER_AUTH" "$CHANGED_BODY" "$ORDER_KEY"

log "payment"
call "payment browser config" 200 GET "/payment-config" "$CUSTOMER_AUTH"
python3 -c "import json;d=json.load(open('$BODY_FILE'));assert d=={'provider':'TOSS_PAYMENTS','sdkVersion':'V2_STANDARD','clientKey':'test_ck_local_scripted'},d"

PAYMENT_ATTEMPT_KEY="demo-payment-attempt-${RUN_ID}"
call "prepare one-time payment" 200 POST "/orders/${ORDER_ID}/payment-attempts" "$CUSTOMER_AUTH" "" "$PAYMENT_ATTEMPT_KEY"
PAYMENT_ID="$(json "d['paymentId']")"
PROVIDER_ORDER_ID="$(json "d['providerOrderId']")"
PAYMENT_AMOUNT="$(json "d['amount']['value']")"
[ "$(json "d['state']")" = "READY" ] || fail "Prepared payment was not READY."
[ "$(json "d['method']")" = "CARD" ] || fail "Prepared payment did not use Toss Standard CARD method."
python3 -c "import json;d=json.load(open('$BODY_FILE'));assert d['successUrl']=='${DEMO_FRONTEND_BASE_URL}/app/payments/${PAYMENT_ID}/success';assert d['failUrl']=='${DEMO_FRONTEND_BASE_URL}/app/payments/${PAYMENT_ID}/fail'"

call "prepare replay is idempotent" 200 POST "/orders/${ORDER_ID}/payment-attempts" "$CUSTOMER_AUTH" "" "$PAYMENT_ATTEMPT_KEY"
[ "$(json "d['paymentId']")" = "$PAYMENT_ID" ] || fail "Payment preparation replay returned another Payment."

PAYMENT_KEY="demo:${PAYMENT_ID}:approved"
CONFIRM_BODY="{\"paymentKey\":\"${PAYMENT_KEY}\",\"orderId\":\"${PROVIDER_ORDER_ID}\",\"amount\":${PAYMENT_AMOUNT}}"
call "confirm one-time payment" 200 POST "/payments/${PAYMENT_ID}/confirmations" "$CUSTOMER_AUTH" "$CONFIRM_BODY" "demo-confirm-${RUN_ID}"
[ "$(json "d['approvalState']")" = "APPROVED" ] || fail "One-time payment did not become APPROVED."
call "exact callback replay" 200 POST "/payments/${PAYMENT_ID}/confirmations" "$CUSTOMER_AUTH" "$CONFIRM_BODY" "demo-confirm-replay-${RUN_ID}"
[ "$(json "d['paymentId']")" = "$PAYMENT_ID" ] || fail "Callback replay returned another Payment."
call "altered callback rejected" 409 POST "/payments/${PAYMENT_ID}/confirmations" "$CUSTOMER_AUTH" \
  "{\"paymentKey\":\"${PAYMENT_KEY}\",\"orderId\":\"${PROVIDER_ORDER_ID}\",\"amount\":$((PAYMENT_AMOUNT + 1))}" "demo-confirm-tamper-${RUN_ID}"
call "approved payment query" 200 GET "/payments/${PAYMENT_ID}" "$CUSTOMER_AUTH"
[ "$(json "d['approvalState']")" = "APPROVED" ] || fail "Payment query did not return APPROVED."

if [ "$SMOKE_CHECKPOINT" = "customer" ]; then
  rm -f "$BODY_FILE" "$HEADERS_FILE"
  echo
  ok "customer checkpoint completed"
  cat <<EOF

  order            ${ORDER_ID}
  payment          ${PAYMENT_ID}
  store            ${STORE_ID}
  point account    ${POINT_ACCOUNT_ID}
  correlation ids  local-demo-${RUN_ID}-1 .. -${STEP}

EOF
  exit 0
fi

log "merchant initial password change"
call "merchant CSRF issue" 204 GET "/auth/merchant/csrf" ""
MERCHANT_XSRF="$(cookie_from_headers BEANFLOW_MERCHANT_XSRF)"
call "merchant initial Session login" 200 POST "/auth/merchant/sessions" "$MERCHANT_CSRF_AUTH" \
  "{\"loginId\":\"${MERCHANT_LOGIN_ID}\",\"password\":\"${MERCHANT_INITIAL_PASSWORD}\"}"
MERCHANT_SESSION="$(cookie_from_headers BEANFLOW_MERCHANT_SESSION)"
python3 -c "import json;d=json.load(open('$BODY_FILE'));assert d['actorType']=='MERCHANT';assert d['accountState']=='INITIAL_PASSWORD'"
call "initial password gate blocks store order" 403 GET "/store-orders/${ORDER_ID}" "$MERCHANT_AUTH"
[ "$(json "d['code']")" = "INITIAL_PASSWORD_CHANGE_REQUIRED" ] || fail "Initial merchant gate returned the wrong error code."
call "initial merchant me remains available" 200 GET "/merchant/me" "$MERCHANT_AUTH"
call "merchant password change" 204 POST "/auth/merchant/password-changes" "$MERCHANT_AUTH" \
  "{\"currentPassword\":\"${MERCHANT_INITIAL_PASSWORD}\",\"newPassword\":\"${MERCHANT_PASSWORD}\"}"
MERCHANT_SESSION="$(cookie_from_headers BEANFLOW_MERCHANT_SESSION)"
call "active merchant stores" 200 GET "/merchant/me/stores" "$MERCHANT_AUTH"
python3 -c "import json;d=json.load(open('$BODY_FILE'));assert len(d)==1;assert d[0]['storeId']=='$STORE_ID';assert d[0]['membershipRole']=='OWNER'"
ok "merchant Session activated without JWT"

log "store fulfilment"
for target in ACCEPTED PREPARING READY COMPLETED; do
  call "store transition ${target}" 200 PATCH "/store-orders/${ORDER_ID}/status" "$MERCHANT_AUTH" \
    "{\"targetState\":\"${target}\",\"reason\":null}" "demo-${target}-${RUN_ID}"
done

log "customer read-back"
call "customer order read"  200 GET  "/orders/${ORDER_ID}" "$CUSTOMER_AUTH"
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
  call "point accrual poll" 200 GET "/point-accounts/${POINT_ACCOUNT_ID}/transactions?limit=100" "$CUSTOMER_AUTH"
  ACCRUAL_STATE="$(python3 -c "import json;d=json.load(open('$BODY_FILE'));matches=[item for item in d['items'] if item['sourceReference']=='$ACCRUAL_SOURCE'];print('FOUND' if len(matches)==1 and matches[0]['type']=='ACCRUAL' and matches[0]['amountKrw']==$EXPECTED_ACCRUAL_KRW else ('MISSING' if not matches else 'INVALID'))")"
  case "$ACCRUAL_STATE" in
    FOUND) ACCRUAL_FOUND="yes"; break ;;
    INVALID) fail "Completion accrual ledger row for ${ORDER_ID} had an unexpected type or amount." ;;
    MISSING) sleep 2 ;;
    *) fail "Could not classify completion accrual for ${ORDER_ID}." ;;
  esac
done
[ "$ACCRUAL_FOUND" = "yes" ] || fail "Timed out waiting for the completion accrual transaction for ${ORDER_ID}."

call "point account after accrual" 200 GET "/point-accounts/${POINT_ACCOUNT_ID}" "$CUSTOMER_AUTH"
python3 -c "import json;after=json.load(open('$BODY_FILE'))['availablePointsKrw'];expected=int('$POINT_BALANCE_BEFORE') + $EXPECTED_ACCRUAL_KRW;assert after == expected, 'expected availablePointsKrw %d after accrual, got %d' % (expected, after);print('       completion accrual ${EXPECTED_ACCRUAL_KRW} KRW and point balance delta verified')"

log "partial and full remaining refund"
PARTIAL_REFUND_BODY="{\"lineItems\":[{\"orderLineId\":\"${ORDER_LINE_ID}\",\"quantity\":1}],\"reason\":\"local demo partial refund\"}"
call "partial refund" 201 POST "/payments/${PAYMENT_ID}/refunds" "$MERCHANT_AUTH" "$PARTIAL_REFUND_BODY" "demo-partial-refund-${RUN_ID}"
[ "$(json "d['state']")" = "SUCCEEDED" ] || fail "Partial refund cash state was not SUCCEEDED."
[ "$(json "d['cashRefundedKrw']")" = "5000" ] || fail "Partial refund amount was not one 5,000 KRW unit."

call "partial refund replay" 201 POST "/payments/${PAYMENT_ID}/refunds" "$MERCHANT_AUTH" "$PARTIAL_REFUND_BODY" "demo-partial-refund-${RUN_ID}"
[ "$(json "d['cashRefundedKrw']")" = "5000" ] || fail "Partial refund replay changed the amount."
call "full remaining refund" 201 POST "/payments/${PAYMENT_ID}/refunds" "$MERCHANT_AUTH" \
  "{\"reason\":\"local demo full remaining refund\"}" "demo-full-refund-${RUN_ID}"
[ "$(json "d['state']")" = "SUCCEEDED" ] || fail "Full remaining refund cash state was not SUCCEEDED."
[ "$(json "d['cashRefundedKrw']")" = "5000" ] || fail "Full remaining refund amount was not 5,000 KRW."

log "unknown confirmation query recovery"
RECOVERY_ORDER_KEY="demo-recovery-order-${RUN_ID}"
RECOVERY_ORDER_BODY="{\"storeId\":\"${STORE_ID}\",\"pickupSlotId\":\"${SLOT_ID}\",\"lines\":[{\"menuId\":\"${MENU_ID}\",\"optionIds\":[\"${OPTION_ID}\"],\"quantity\":1}],\"pointsToUseKrw\":0}"
call "create recovery order" 201 POST "/orders" "$CUSTOMER_AUTH" "$RECOVERY_ORDER_BODY" "$RECOVERY_ORDER_KEY"
RECOVERY_ORDER_ID="$(json "d['order']['orderId']")"
call "prepare recovery payment" 200 POST "/orders/${RECOVERY_ORDER_ID}/payment-attempts" "$CUSTOMER_AUTH" "" "demo-recovery-attempt-${RUN_ID}"
RECOVERY_PAYMENT_ID="$(json "d['paymentId']")"
RECOVERY_PROVIDER_ORDER_ID="$(json "d['providerOrderId']")"
RECOVERY_AMOUNT="$(json "d['amount']['value']")"
RECOVERY_PAYMENT_KEY="demo:${RECOVERY_PAYMENT_ID}:eventually-approved"
call "unknown confirmation retained" 202 POST "/payments/${RECOVERY_PAYMENT_ID}/confirmations" "$CUSTOMER_AUTH" \
  "{\"paymentKey\":\"${RECOVERY_PAYMENT_KEY}\",\"orderId\":\"${RECOVERY_PROVIDER_ORDER_ID}\",\"amount\":${RECOVERY_AMOUNT}}" "demo-recovery-confirm-${RUN_ID}"
[ "$(json "d['approvalState']")" = "UNKNOWN" ] || fail "Unknown confirmation was not exposed as UNKNOWN."

RECOVERY_DEADLINE=$(( SECONDS + 60 ))
RECOVERY_APPROVED="no"
while (( SECONDS < RECOVERY_DEADLINE )); do
  call "payment recovery poll" "200|202" GET "/payments/${RECOVERY_PAYMENT_ID}" "$CUSTOMER_AUTH"
  RECOVERY_STATE="$(json "d['approvalState']")"
  case "$RECOVERY_STATE" in
    APPROVED) RECOVERY_APPROVED="yes"; break ;;
    UNKNOWN|RECONCILING|APPROVING) sleep 2 ;;
    *) fail "Unexpected payment recovery state ${RECOVERY_STATE}." ;;
  esac
done
[ "$RECOVERY_APPROVED" = "yes" ] || fail "Timed out waiting for unknown payment lookup recovery."
ok "unknown confirmation converged to APPROVED by Provider lookup"

log "authorization failures"
call "no token is 401"            401 GET "/stores/${STORE_ID}/menus" ""
call "customer path rejects bearer" 403 GET "/stores/${STORE_ID}/menus" "not-a-real-jwt"
call "other merchant Session login" 200 POST "/auth/merchant/sessions" "$MERCHANT_CSRF_AUTH" \
  "{\"loginId\":\"${OTHER_MERCHANT_LOGIN_ID}\",\"password\":\"${OTHER_MERCHANT_PASSWORD}\"}"
OTHER_MERCHANT_SESSION="$(cookie_from_headers BEANFLOW_MERCHANT_SESSION)"
call "other store owner denied"   403 GET "/store-orders/${ORDER_ID}" "$OTHER_MERCHANT_AUTH"

rm -f "$BODY_FILE" "$HEADERS_FILE"
echo
ok "core smoke flow completed"
cat <<EOF

  order            ${ORDER_ID}
  payment          ${PAYMENT_ID}
  recovered order  ${RECOVERY_ORDER_ID}
  recovered payment ${RECOVERY_PAYMENT_ID}
  store            ${STORE_ID}
  point account    ${POINT_ACCOUNT_ID}
  correlation ids  local-demo-${RUN_ID}-1 .. -${STEP}

EOF
