#!/usr/bin/env bash
# Places hardening, run by .github/workflows/firebase-backend.yml with the owner's FIREBASE_TOKEN.
#   prep   — before the functions deploy: APIs, server key (Places only) → Secret Manager PLACES_KEY,
#            Service Usage admin for the functions' runtime account (the kill switch turns Places off).
#   after  — after the deploy: kill-switch budget → `budget-alerts` topic, APK key → Maps SDK only.
#   report — prints what is actually set (key restrictions, Places state, quotas, budgets).
# Never prints a key string.
set -uo pipefail

P=noted-ce451
TOPIC=budget-alerts
SERVER_KEY=noted-places-server
KILL_NAME="noted places kill switch"
# release keystore (public — it's in every APK)
APP_SHA1=451f6a595d7aaaf825b1c0f9dfe9cea6c0ce67be

AT=$(node -e "require('$(npm root -g)/firebase-tools/lib/auth').getAccessToken(process.env.FIREBASE_TOKEN, []).then(t => console.log(t.access_token))")
echo "::add-mask::$AT"

# user credentials need a quota project for apikeys / budgets
api() { # METHOD URL [BODY]
  local m=$1 u=$2 b=${3:-}
  if [ -n "$b" ]; then
    curl -sS -X "$m" -H "Authorization: Bearer $AT" -H "x-goog-user-project: $P" -H "Content-Type: application/json" -d "$b" "$u"
  else
    curl -sS -X "$m" -H "Authorization: Bearer $AT" -H "x-goog-user-project: $P" -H "Content-Length: 0" "$u"
  fi
}
fail() { echo "::warning::$*"; }
wait_op() { # operation JSON → final JSON (apikeys long-running ops)
  local op=$1 name i
  name=$(jq -r '.name // empty' <<<"$op")
  for i in $(seq 1 20); do
    [ "$(jq -r '.done // false' <<<"$op")" = true ] && { echo "$op"; return; }
    [ -z "$name" ] && { echo "$op"; return; }
    sleep 3
    op=$(api GET "https://apikeys.googleapis.com/v2/$name")
  done
  echo "$op"
}

prep() {
  for s in apikeys.googleapis.com secretmanager.googleapis.com billingbudgets.googleapis.com cloudbilling.googleapis.com \
    cloudresourcemanager.googleapis.com; do
    # not places.googleapis.com: once the kill switch turned it off, it stays off until someone means it
    api POST "https://serviceusage.googleapis.com/v1/projects/$P/services/$s:enable" >/dev/null && echo "enabled $s"
  done
  sleep 20

  # ---- server key: Places API only, never in the APK
  local k
  k=$(api GET "https://apikeys.googleapis.com/v2/projects/$P/locations/global/keys/$SERVER_KEY")
  if [ "$(jq -r '.name // empty' <<<"$k")" = "" ]; then
    echo "creating server key"
    k=$(wait_op "$(api POST "https://apikeys.googleapis.com/v2/projects/$P/locations/global/keys?keyId=$SERVER_KEY" \
      '{"displayName":"noted places (server only)","restrictions":{"apiTargets":[{"service":"places.googleapis.com"}]}}')")
    jq -e '.error' <<<"$k" >/dev/null && { fail "server key: $(jq -c .error <<<"$k")"; return 1; }
  fi
  local ks
  ks=$(api GET "https://apikeys.googleapis.com/v2/projects/$P/locations/global/keys/$SERVER_KEY/keyString" | jq -r '.keyString // empty')
  [ -z "$ks" ] && { fail "server key string unavailable"; return 1; }
  echo "::add-mask::$ks"

  # ---- Secret Manager PLACES_KEY (new version only when it changed)
  local sm="https://secretmanager.googleapis.com/v1/projects/$P/secrets/PLACES_KEY"
  if api GET "$sm" | jq -e '.error' >/dev/null; then
    api POST "https://secretmanager.googleapis.com/v1/projects/$P/secrets?secretId=PLACES_KEY" \
      '{"replication":{"automatic":{}},"labels":{"firebase-managed":"true"}}' | jq -c '{name, error}'
  fi
  local cur
  cur=$(api GET "$sm/versions/latest:access" | jq -r '.payload.data // empty' | base64 -d 2>/dev/null || true)
  if [ "$cur" != "$ks" ]; then
    api POST "$sm:addVersion" "$(jq -nc --arg d "$(printf %s "$ks" | base64 -w0)" '{payload:{data:$d}}')" | jq -c '{name, error}'
  else
    echo "PLACES_KEY up to date"
  fi

  # ---- the functions' runtime account may switch Places off
  local num sa pol
  num=$(api GET "https://cloudresourcemanager.googleapis.com/v1/projects/$P" | jq -r .projectNumber)
  sa="serviceAccount:$num-compute@developer.gserviceaccount.com"
  pol=$(api POST "https://cloudresourcemanager.googleapis.com/v1/projects/$P:getIamPolicy" '{"options":{"requestedPolicyVersion":3}}')
  if jq -e --arg m "$sa" '.bindings[]? | select(.role=="roles/serviceusage.serviceUsageAdmin") | .members | index($m)' <<<"$pol" >/dev/null; then
    echo "runtime account already has serviceUsageAdmin"
  else
    pol=$(jq -c --arg m "$sa" '
      if any(.bindings[]?; .role=="roles/serviceusage.serviceUsageAdmin")
      then .bindings |= map(if .role=="roles/serviceusage.serviceUsageAdmin" then .members += [$m] else . end)
      else .bindings += [{role:"roles/serviceusage.serviceUsageAdmin", members:[$m]}] end' <<<"$pol")
    api POST "https://cloudresourcemanager.googleapis.com/v1/projects/$P:setIamPolicy" \
      "$(jq -c '{policy: ., updateMask: "bindings,etag"}' <<<"$pol")" | jq -c '{etag, error}'
  fi
}

after() {
  # ---- kill-switch budget on the project's billing account, published to the topic
  local acct cur amount budgets b body
  acct=$(api GET "https://cloudbilling.googleapis.com/v1/projects/$P/billingInfo" | jq -r '.billingAccountName // empty')
  [ -z "$acct" ] && { fail "no billing account visible"; return 1; }
  cur=$(api GET "https://cloudbilling.googleapis.com/v1/$acct" | jq -r '.currencyCode // "USD"')
  case "$cur" in ILS) amount=10 ;; *) amount=3 ;; esac

  # Cloud Billing publishes budget alerts as this account
  local t="https://pubsub.googleapis.com/v1/projects/$P/topics/$TOPIC"
  api PUT "$t" '{}' >/dev/null
  local tp
  tp=$(api GET "$t:getIamPolicy")
  if ! jq -e '.bindings[]? | select(.role=="roles/pubsub.publisher") | .members | index("serviceAccount:billing-budget-alert@system.gserviceaccount.com")' <<<"$tp" >/dev/null; then
    tp=$(jq -c '.bindings = ((.bindings // []) + [{role:"roles/pubsub.publisher", members:["serviceAccount:billing-budget-alert@system.gserviceaccount.com"]}])' <<<"$tp")
    api POST "$t:setIamPolicy" "$(jq -c '{policy: .}' <<<"$tp")" | jq -c '{etag, error}'
  fi

  budgets=$(api GET "https://billingbudgets.googleapis.com/v1/$acct/budgets?pageSize=100")
  jq -e '.error' <<<"$budgets" >/dev/null && { fail "budgets: $(jq -c .error <<<"$budgets")"; return 1; }
  b=$(jq -r --arg n "$KILL_NAME" '.budgets[]? | select(.displayName==$n) | .name' <<<"$budgets" | head -1)
  body=$(jq -nc --arg n "$KILL_NAME" --arg p "projects/$P" --arg c "$cur" --arg a "$amount" --arg t "projects/$P/topics/$TOPIC" '{
    displayName: $n,
    budgetFilter: {projects: [$p], calendarPeriod: "MONTH"},
    amount: {specifiedAmount: {currencyCode: $c, units: $a}},
    thresholdRules: [{thresholdPercent: 0.5}, {thresholdPercent: 1.0}],
    notificationsRule: {pubsubTopic: $t, schemaVersion: "1.0"}
  }')
  if [ -z "$b" ]; then
    echo "creating kill-switch budget ($amount $cur)"
    api POST "https://billingbudgets.googleapis.com/v1/$acct/budgets" "$body" | jq -c '{name, error}'
  else
    echo "kill-switch budget exists: $b"
    api PATCH "https://billingbudgets.googleapis.com/v1/$b?updateMask=notificationsRule.pubsubTopic,notificationsRule.schemaVersion" "$body" | jq -c '{name, error}'
  fi

  # ---- the APK's key: Maps SDK for Android only (Places goes through the function now)
  [ -z "${MAPS_API_KEY:-}" ] && { fail "MAPS_API_KEY not available"; return 0; }
  local name k
  name=$(api GET "https://apikeys.googleapis.com/v2/keys:lookupKey?keyString=$MAPS_API_KEY" | jq -r '.name // empty')
  [ -z "$name" ] && { fail "APK key not found in the project"; return 1; }
  k=$(api GET "https://apikeys.googleapis.com/v2/$name")
  echo "APK key before: $(jq -c '.restrictions' <<<"$k")"
  # Maps SDK only, and only from this app signed with our keystore
  body=$(jq -nc --arg pkg com.ohav.noted --arg sha "$APP_SHA1" '{restrictions: {
    androidKeyRestrictions: {allowedApplications: [{packageName: $pkg, sha1Fingerprint: $sha}]},
    apiTargets: [{service: "maps-android-backend.googleapis.com"}]}}')
  if [ "$(jq -cS '.restrictions' <<<"$k")" = "$(jq -cS '.restrictions' <<<"$body")" ]; then
    echo "APK key already locked (app + Maps SDK only)"
  else
    wait_op "$(api PATCH "https://apikeys.googleapis.com/v2/$name?updateMask=restrictions" "$body")" | jq -c '{done, error}'
    echo "APK key after: $(api GET "https://apikeys.googleapis.com/v2/$name" | jq -c '.restrictions')"
  fi
}

report() {
  echo "================ Places / keys / budget report ================"
  echo "--- Places API state:"
  api GET "https://serviceusage.googleapis.com/v1/projects/$P/services/places.googleapis.com" | jq -r .state
  echo "--- API keys (restrictions):"
  api GET "https://apikeys.googleapis.com/v2/projects/$P/locations/global/keys" | jq -r '.keys[]? |
    "\(.displayName // .uid): apis=\([.restrictions.apiTargets[]?.service] | join(",") | if .=="" then "ANY" else . end)" +
    " android=\([.restrictions.androidKeyRestrictions.allowedApplications[]? | "\(.packageName)/\(.sha1Fingerprint)"] | join(","))" +
    " browser=\(.restrictions.browserKeyRestrictions.allowedReferrers // [] | join(","))"'
  echo "--- Places quotas (effective limits):"
  api GET "https://serviceusage.googleapis.com/v1beta1/projects/$P/services/places.googleapis.com/consumerQuotaMetrics" | jq -r '
    .metrics[]? | .displayName as $m | .consumerQuotaLimits[]? | .unit as $u |
    .quotaBuckets[]? | select(.dimensions == null) | "\($m) [\($u)]: \(.effectiveLimit)"' | sort -u
  echo "--- Budgets:"
  local acct
  acct=$(api GET "https://cloudbilling.googleapis.com/v1/projects/$P/billingInfo" | jq -r '.billingAccountName // empty')
  [ -n "$acct" ] && api GET "https://billingbudgets.googleapis.com/v1/$acct/budgets?pageSize=100" | jq -r '
    .budgets[]? | "\(.displayName): \(.amount.specifiedAmount.units // "last-period") \(.amount.specifiedAmount.currencyCode // "") projects=\(.budgetFilter.projects // ["all"] | join(",")) topic=\(.notificationsRule.pubsubTopic // "-")"'
  echo "--- Functions runtime may disable services:"
  api POST "https://cloudresourcemanager.googleapis.com/v1/projects/$P:getIamPolicy" '{}' |
    jq -r '.bindings[]? | select(.role=="roles/serviceusage.serviceUsageAdmin") | .members | join(",")'
  echo "==============================================================="
}

# End-to-end check of the kill switch: a budget alert over the limit → Places off → back on.
killtest() {
  local svc="https://serviceusage.googleapis.com/v1/projects/$P/services/places.googleapis.com" msg i st
  msg=$(jq -nc --arg n "$KILL_NAME" '{budgetDisplayName: $n, costAmount: 99, budgetAmount: 10, currencyCode: "ILS"}' | base64 -w0)
  api POST "https://pubsub.googleapis.com/v1/projects/$P/topics/$TOPIC:publish" "{\"messages\":[{\"data\":\"$msg\"}]}" | jq -c .
  for i in $(seq 1 18); do
    sleep 10; st=$(api GET "$svc" | jq -r .state); echo "places: $st"
    [ "$st" = DISABLED ] && break
  done
  api POST "$svc:enable" >/dev/null
  for i in $(seq 1 12); do sleep 5; [ "$(api GET "$svc" | jq -r .state)" = ENABLED ] && break; done
  echo "places back: $(api GET "$svc" | jq -r .state)"
  [ "$st" = DISABLED ] && echo "KILL SWITCH OK" || { echo "::error::kill switch did not turn Places off"; return 1; }
}

"$@"
