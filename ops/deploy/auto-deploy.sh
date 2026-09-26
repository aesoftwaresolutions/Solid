#!/usr/bin/env bash
# Keeps this machine's Solid containers matching whatever is on GitHub's main branch.
#
# Run on a timer (see ops/deploy/README.md for the cron line). Each run:
#   1. Checks whether origin/main has moved since we last deployed it.
#   2. If so, fast-forwards this checkout to it.
#   3. Rebuilds the Docker images and restarts every service that's affected --
#      the live server (app + web) AND the desktop/remote-database instance --
#      so every access point is running the same code after a push.
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

# Fast-forward only. If this ever fails, this checkout has local commits main doesn't --
# stop rather than silently discard or merge something unexpected.
git merge --ff-only origin/main

# Rebuild everything and recreate only the containers whose image actually changed --
# `up -d` after `build` does that on its own, it won't restart something that didn't change.
docker compose build app web desktop
docker compose up -d

echo "Deployed $LATEST."
