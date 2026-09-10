#!/usr/bin/env bash
#
# Start the onboarding backend for one market.
#
#   ./run.sh                  # meridian-health, live Go  (the default)
#   ./run.sh northbank        # a different market
#   ./run.sh meridian-health mock
#
# Loads credentials from .env.local, which is gitignored and must exist for
# live mode — see the GBG credentials section of HANDOFF.md. Nothing here
# holds a secret; it only reads them.

set -euo pipefail
cd "$(dirname "$0")"

MARKET="${1:-meridian-health}"
GO_MODE="${2:-live}"

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

if [ "$GO_MODE" = "live" ]; then
  if [ ! -f .env.local ]; then
    cat >&2 <<'MISSING'
Live mode needs .env.local in this folder, holding:

  GBG_CLIENT_ID=...
  GBG_CLIENT_SECRET=...
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
