# Auto-deploy

`auto-deploy.sh` keeps this machine's containers matching GitHub's `main` branch: the live
server (`app` + `web`, spec 060's server install) and the desktop/remote-database instance
(spec 066) both get rebuilt and restarted whenever a push lands on `main`.

It's a **pull**, not a webhook: nothing needs to be reachable from GitHub, which matters here
since Postgres and the app are only exposed on the Tailscale network, not the public internet.
A cron job just checks every couple of minutes.

## Install (once, on this machine)

```
crontab -e
```

Add this line (adjust the path if the checkout isn't at `~/apps/solid`):

```
*/2 * * * * flock -n /tmp/solid-deploy.lock /home/anthony/apps/solid/ops/deploy/auto-deploy.sh >> /home/anthony/apps/solid/deploy.log 2>&1
```

`flock -n` skips a run if the previous one is still going (a slow build), instead of piling up.

## Checking on it

```
tail -f ~/apps/solid/deploy.log
```

Nothing to do if it just says "Already up to date" -- that's the normal, most-of-the-time
result. A merge conflict, failed build, or anything else unexpected shows up in that log and
leaves the previous, working containers running rather than a half-updated one.
