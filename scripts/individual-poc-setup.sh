#!/usr/bin/env bash
# Individual auth + IV uplift PoC — local setup helper
#
# Prerequisites: service-manager (sm2), MongoDB, sbt
#
# Usage:
#   ./scripts/individual-poc-setup.sh
#
# Then start the alpha frontend:
#   cd digital-disclosure-service-alpha-frontend && sbt run

set -euo pipefail

echo "==> Starting platform stubs for individual auth PoC..."
sm2 --start AUTH AGENTS_STUBS AGENTS_EXTERNAL_STUBS_FRONTEND 2>/dev/null || true

echo "==> Stopping real IV frontend (if running) and starting IV stub on :9948..."
sm2 --stop IDENTITY_VERIFICATION_FRONTEND 2>/dev/null || true
sm2 --start IDENTITY_VERIFICATION_STUB 2>/dev/null || true

echo ""
echo "Individual auth PoC is ready to run."
echo ""
echo "  1. Enable the feature flag (default on in application.conf):"
echo "       features.individual-auth-poc = true"
echo ""
echo "  2. Start the alpha frontend:"
echo "       cd digital-disclosure-service-alpha-frontend && sbt run"
echo ""
echo "  3. Open the hub:"
echo "       http://localhost:9000/digital-disclosure-service-alpha-frontend/individual-poc"
echo ""
echo "  4. Click 'View my auth session' — you will be sent to BAS gateway stub"
echo "     (accountType=individual). Sign in as a user WITHOUT a NINO to exercise IV."
echo ""
echo "  5. IV stub (:9948) lets you pick Success and optionally enter a NINO."
echo ""
echo "Optional — full PTA flow (see README PTA section):"
echo "       sm2 --start PTA_ALL"
echo "       sm2 --stop PERTAX_FRONTEND && cd ../pta/pertax-frontend && sbt 'run 9232'"
echo "       http://localhost:9232/personal-account/"
echo ""
