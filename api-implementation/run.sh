#!/usr/bin/env bash
#
# Start the onboarding backend for one market.
#
#   ./run.sh                  # meridian-health, live Go  (the default)
#   ./run.sh northbank        # a different market
#   ./run.sh meridian-health mock
#   ./run.sh northbank test-scenarios   # real Go, journeys run on a test scenario
#
# Loads credentials from .env.local, which is gitignored and must exist for
# live and test-scenarios modes — see the GBG credentials section of HANDOFF.md. Nothing here
# holds a secret; it only reads them.

set -euo pipefail
cd "$(dirname "$0")"

MARKET="${1:-meridian-health}"
GO_MODE="$(printf '%s' "${2:-live}" | tr '[:upper:]' '[:lower:]')"

# Only these three: anything else would start with no Go client wired in at
# all, and fail with a bean error that doesn't say why.
case "$GO_MODE" in
  mock|live|test-scenarios) ;;
  *)
    echo "Unknown mode '$2'. Use one of: mock, live, test-scenarios" >&2
    exit 1
    ;;
esac

# The project needs JDK 21. The bundled oracleJdk-26 in this folder is too new
# for the Lombok version the build pins, and fails with symbol-not-found errors
# across the mock package.
for candidate in /opt/homebrew/opt/openjdk@21 /usr/local/opt/openjdk@21; do
  if [ -d "$candidate" ]; then
    export JAVA_HOME="$candidate"
    export PATH="$JAVA_HOME/bin:$PATH"
    break
  fi
done

if ! command -v java >/dev/null 2>&1; then
  echo "No java on PATH. Install one with:  brew install openjdk@21" >&2
  exit 1
fi

if [ "$GO_MODE" != "mock" ]; then
  if [ ! -f .env.local ]; then
    cat >&2 <<'MISSING'
Live and test-scenarios modes need .env.local in this folder, holding:

  GBG_CLIENT_ID=...
  GBG_CLIENT_SECRET=...

plus, only for a tenant using the password grant (go.grant-type: password):

  GBG_USERNAME=...
  GBG_PASSWORD=...

Ask whoever administers the tenant for these. The file is gitignored;
never commit credentials. To run without them:  ./run.sh <market> mock
MISSING
    exit 1
  fi
  # set -a exports everything sourced, which is what Spring reads; without it
  # they would be shell variables only and the app would see nothing.
  set -a
  # shellcheck disable=SC1091
  source .env.local
  set +a
  echo "Credentials loaded for ${GBG_CLIENT_ID:-<unset>}"
fi

echo "Starting $MARKET (go.mode=$GO_MODE) on Java $(java -version 2>&1 | head -1 | cut -d'"' -f2)"
exec mvn spring-boot:run \
  -Dspring-boot.run.profiles="$MARKET" \
  -Dspring-boot.run.arguments="--go.mode=$GO_MODE"
