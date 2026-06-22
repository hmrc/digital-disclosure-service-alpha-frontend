#!/usr/bin/env bash
# Agent authorisation PoC — local test-data setup helper
#
# Prerequisites:
#   sm2 --start AGENTS_STUBS AGENTS_EXTERNAL_STUBS_FRONTEND   # :9009 / :9099
#   sm2 --start AGENT_AUTHORISATION                           # AAC :9431, ACR :9434
#
# Usage:
#   ./scripts/agent-poc-setup.sh
#   ARN=ABC123 MTDITID=XXIT12345678901 ./scripts/agent-poc-setup.sh

set -euo pipefail

STUBS_URL="${STUBS_URL:-http://localhost:9009}"
ACR_URL="${ACR_URL:-http://localhost:9434}"
DDS_URL="${DDS_URL:-http://localhost:9000/digital-disclosure-service-alpha-frontend}"

ARN="${ARN:-TARN0000001}"
MTDITID="${MTDITID:-XXIT12345678901}"
NINO="${NINO:-AA123456A}"
NEGATIVE_MTDITID="${NEGATIVE_MTDITID:-XXIT99999999999}"
SERVICE="${SERVICE:-HMRC-MTD-IT}"
REGIME="${REGIME:-ITSA}"

echo "=== DDS Agent PoC test-data setup ==="
echo "Stubs:    $STUBS_URL"
echo "ACR:      $ACR_URL"
echo "DDS hub:  $DDS_URL/agent-poc"
echo ""
echo "Agent ARN:        $ARN"
echo "Client MTDITID:   $MTDITID (verifier NINO $NINO)"
echo "Negative client:  $NEGATIVE_MTDITID (no relationship)"
echo ""

post_json() {
  local url="$1"
  local body="$2"
  curl -sf -X POST "$url" \
    -H "Content-Type: application/json" \
    -d "$body" \
    -w "\n  -> HTTP %{http_code}\n" || echo "  -> request failed (is stubs running on $STUBS_URL?)"
}

put_empty() {
  local url="$1"
  curl -sf -X PUT "$url" \
    -w "\n  -> HTTP %{http_code}\n" || echo "  -> request failed (is ACR running on $ACR_URL?)"
}

echo "1. Known facts for Option 2 client (principal enrolment stand-in)"
post_json "$STUBS_URL/agents-external-stubs/known-facts" \
  "{\"enrolmentKey\":\"${SERVICE}~MTDITID~${MTDITID}\",\"verifiers\":[{\"key\":\"NINO\",\"value\":\"${NINO}\"}]}"

echo ""
echo "2. ETMP relationship record (stand-in regime $REGIME)"
post_json "$STUBS_URL/agents-external-stubs/records/relationship" \
  "{\"regime\":\"${REGIME}\",\"agentARN\":\"${ARN}\",\"clientId\":\"${MTDITID}\",\"clientIdType\":\"MTDITID\",\"relationship\":\"Authorised\"}"

echo ""
echo "3. ACR test-only relationship (delegated enrolment stand-in)"
put_empty "$ACR_URL/agent-client-relationships/test-only/agent/${ARN}/service/${SERVICE}/client/MTDITID/${MTDITID}"

echo ""
echo "4. Negative client known facts only (no relationship — gate should block)"
post_json "$STUBS_URL/agents-external-stubs/known-facts" \
  "{\"enrolmentKey\":\"${SERVICE}~MTDITID~${NEGATIVE_MTDITID}\",\"verifiers\":[{\"key\":\"NINO\",\"value\":\"BB987654B\"}]}"

echo ""
echo "=== Manual steps ==="
echo ""
echo "Create users via the stubs UI:"
echo "  Quick-start hub: http://localhost:9099/agents-external-stubs/quick-start-hub"
echo ""
echo "Agent user:"
echo "  - affinityGroup: Agent"
echo "  - enrolment: HMRC-AS-AGENT with ARN $ARN"
echo ""
echo "Client user (Option 2 / consent accept):"
echo "  - principal enrolment: $SERVICE / MTDITID $MTDITID"
echo "  - or sign in after registering via $DDS_URL/agent-poc/option-2/register"
echo ""
echo "Sign in to DDS PoC:"
echo "  http://localhost:9099/bas-gateway/sign-in"
echo "  continue URL: $DDS_URL/agent-poc"
echo ""
echo "Stride sign-in (digitally excluded stand-in):"
echo "  http://localhost:9099/stride/sign-in"
echo ""
echo "Gate check (after agent sign-in, from invite page or directly):"
echo "  $DDS_URL/agent-poc/gate-check?clientId=${MTDITID}&clientIdType=MTDITID"
echo "  $DDS_URL/agent-poc/gate-check?clientId=${NEGATIVE_MTDITID}&clientIdType=MTDITID  (expect blocked)"
echo ""
echo "Done."
