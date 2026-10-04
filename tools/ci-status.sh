#!/usr/bin/env bash
# Waits for the Actions run of the current commit to finish and prints the error
# annotations the build emitted.
set -uo pipefail
cd "$(dirname "$0")/.."
REPO="repos/rehmaahmed11/App-Monitor"
SHA=$(git rev-parse HEAD)

info=""
for _ in $(seq 1 80); do
  info=$(gh run list -L 12 --json status,conclusion,databaseId,headSha \
        -q ".[] | select(.headSha == \"$SHA\") | \"\(.status)|\(.conclusion // \"\")|\(.databaseId)\"" \
        2>/dev/null | head -1)
  if [ -n "$info" ]; then
    status=$(printf '%s' "$info" | cut -d'|' -f1)
    if [ "$status" = "completed" ]; then
      break
    fi
  fi
  sleep 12
done

conclusion=$(printf '%s' "$info" | cut -d'|' -f2)
run_id=$(printf '%s' "$info" | cut -d'|' -f3)
echo "commit=$SHA run=$run_id conclusion=${conclusion:-not-finished}"
[ -z "$info" ] && exit 0

cr=$(gh api "$REPO/commits/$SHA/check-runs" -q '.check_runs[0].id' 2>/dev/null)
if [ -z "$cr" ]; then
  echo "no check run found"
  exit 0
fi
gh api "$REPO/check-runs/$cr/annotations" \
  -q '.[] | select(.annotation_level=="error" or .annotation_level=="failure") | "ERROR \(.path):\(.start_line) — \(.message)"' 2>/dev/null \
  | head -100
