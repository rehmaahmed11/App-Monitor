#!/usr/bin/env bash
# Waits for the newest Actions run of the current branch to finish and prints any
# error annotations that the build emitted.
set -uo pipefail
cd "$(dirname "$0")/.."

for _ in $(seq 1 60); do
  info=$(gh run list -L 1 --json status,conclusion,databaseId,headSha \
        -q '.[0] | "\(.status)|\(.conclusion // "")|\(.databaseId)|\(.headSha)"' 2>/dev/null)
  status=$(printf '%s' "$info" | cut -d'|' -f1)
  if [ "$status" = "completed" ]; then
    break
  fi
  sleep 12
done

conclusion=$(printf '%s' "$info" | cut -d'|' -f2)
run_id=$(printf '%s' "$info" | cut -d'|' -f3)
sha=$(printf '%s' "$info" | cut -d'|' -f4)
echo "run=$run_id conclusion=${conclusion:-running} sha=$sha"

cr=$(gh api "repos/rehmaahmed11/App-Monitor/commits/$sha/check-runs" -q '.check_runs[0].id' 2>/dev/null)
if [ -z "$cr" ]; then
  echo "no check run found"
  exit 0
fi
gh api "repos/rehmaahmed11/App-Monitor/check-runs/$cr/annotations" \
  -q '.[] | select(.annotation_level=="error") | "ERROR \(.path):\(.start_line) — \(.message)"' 2>/dev/null \
  | head -80
