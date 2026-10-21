---
name: vahter
description: Manage phone keys for the Vahter Android app.
version: 0.1.0
author: Artem (Vahter) + Hermes Agent
license: MIT
platforms: [linux]
metadata:
  hermes:
    tags: [vahter, android, phone, access]
    category: devops
---

# Vahter Skill

Vahter is an Android assistant that talks to this Hermes through the built-in API server behind a reverse proxy.
Each phone has its own key; the proxy checks it and swaps in the real API key. This skill issues, lists and
revokes phone keys. It does not change Hermes itself: it only writes files under `$HERMES_HOME/vahter/`, and the
host reloads the proxy when `devices.caddy` changes.

## When to Use

- The owner asks for an invite for a phone: «сделай приглашение для Вахтёра для S24», "invite my phone to Vahter".
- The owner asks which phones are connected, or to revoke one.

Do not run it for anyone but the owner, and never paste a phone key into a group chat.

## Prerequisites

- `$HERMES_HOME/vahter/host` contains the public host name (written by the host `install.sh`).
- `$HERMES_HOME/vahter/` is writable by Hermes (the installer sets the owner).

## How to Run

Use `terminal` with the skill-relative script:

```
bash scripts/vahter-devices.sh invite "<phone name>"   # new key, prints the invite link
bash scripts/vahter-devices.sh list                      # names and dates
bash scripts/vahter-devices.sh revoke "<phone name>"   # key stops working at once
```

## Quick Reference

| Ask | Command | Reply with |
|---|---|---|
| invite a phone | `invite "<name>"` | the printed `https://<host>/vahter/connect#…` link, one line |
| which phones | `list` | names and dates |
| revoke a phone | `revoke "<name>"` | one line that the key no longer works |

## Procedure

1. Pick a short phone name from the request (`S24 Ultra`, `Pixel`). Ask only if the name is really missing.
2. Run the command with `terminal`. The script writes `devices.txt` and rebuilds `devices.caddy`; the proxy
   reloads itself within a second.
3. Send the link exactly as printed. The owner opens it on the phone; the Vahter app connects by itself.
   If the link does not open the app, the page shows the same link to copy into the app
   (Settings → Brains → Hermes → Paste invite).

## Pitfalls

- The link is the phone key. It is not one-time: it works until revoked. Treat it like a password.
- `host` missing → the script stops. Run the host `install.sh` first.
- Re-inviting an existing name replaces its key: the old link stops working.

## Verification

`list` shows the new name; `curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer <key>" https://<host>/v1/models` prints `200`.
