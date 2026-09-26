#!/usr/bin/env bash
# Keeps this machine's Solid containers matching whatever is on GitHub's main branch.
#
# Run on a timer (see ops/deploy/README.md for the cron line). Each run:
#   1. Checks whether origin/main has moved since we last deployed it.
#   2. If so, makes this checkout exactly match origin/main.
#   3. Rebuilds the Docker images and restarts every service that's affected --
#      the live server (app + web) AND the desktop/remote-database instance --
#      so every access point is running the same code after a push.
#
# This checkout is a deploy target, not somewhere anyone develops -- it should never carry a commit that
# didn't come from GitHub's main, so step 2 is a hard reset to origin/main rather than a merge. That also
# makes it self-healing: if this checkout is ever manually poked at (a hotfix typed directly over SSH, say),
# the next run puts it back to exactly what's on GitHub instead of getting stuck unable to fast-forward past
# whatever was typed. Anything that's supposed to differ per machine (ports, IPs, secrets) belongs in this
# machine's own .env, never in a hand-edited copy of a tracked file -- .env is gitignored, so a reset never
# touches it, and docker-compose.yml reads those settings from it (see .env.example).
#
# Safe to run often: if nothing changed, it does nothing but print one line.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."   # repo root (this script lives in ops/deploy/)

echo "== $(date -Is) =="

git fetch origin main --quiet

CURRENT="$(git rev-parse HEAD)"
LATEST="$(git rev-parse origin/main)"

if [ "$CURRENT" = "$LATEST" ]; then
    echo "Already up to date ($CURRENT)."
    exit 0
fi

echo "New commit(s) on main: $CURRENT -> $LATEST"

git checkout main --quiet
git reset --hard origin/main

# Rebuild everything and recreate only the containers whose image actually changed --
# `up -d` after `build` does that on its own, it won't restart something that didn't change.
docker compose build app web desktop
docker compose up -d

echo "Deployed $LATEST."
