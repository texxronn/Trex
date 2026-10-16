# V2-AUTH-PLAN.md

> **Personal project, single operator, private.** `V2-SPEC.md` is the specification; this file is the
> build order for one change to it. Where this file and the spec disagree, the spec wins, and the spec
> is amended in the same change (Stage 1), per `AGENTS.md`.

**Status:** proposed; not started. Nothing built.
**Decisions:** A-1…A-4 accepted by the operator (2026-10-16); A-5…A-9 open with recommendations.
**Goal (operator, 2026-10-16):** sign in to the hub as a known person — **ron** or **mel** — so that a
LAN listener is not an open door *and* so a decision in the log is signed by a verified person.
**Authority:** `V2-SPEC.md` §5 (decisions), §9 (hub API and UI), §12 (configuration);
`docs/DEPLOYMENTS.md`; `AGENTS.md`.

---

## 1. The problem, measured

Two facts about the hub today, both true and both acceptable right now only because the listener is
assumed to be private.

**One: nothing is authenticated.** `docs/DEPLOYMENTS.md` says it plainly — *"**There is no
authentication.** Anyone who can reach :8090 can read the journal and write decisions, so it is
LAN-only for now."* Production sets `TREX_HUB_BIND=0.0.0.0`, so the whole read/decision API is served
to the LAN in cleartext. `HubHttpApi` registers its routes and reads no `Authorization` header; the
only credential anywhere in the tree is the runner's shared secret
(`RunnerHttpApi.isAuthorized`, `X-Trex-Runner-Token`), presented *by* the hub, not *to* it — and it is
`null`-tolerant, so an unset token means *open*, which is exactly the footgun this plan avoids (§4.1).

**Two: identity is client-declared.** Who you are is a browser suggestion:

| where | how it is decided today |
|---|---|
| `web/js/app.js:29` | `user: localStorage.getItem('trex.user') \|\| 'ron'` |
| `web/js/status.js:159` | an `acting as` `<select>` over `refdata.users`, written to `localStorage` |
| `web/js/decisions.js:5` | `{ actor: 'user', user: ctx.user }` — sent in the body |
| `web/js/cash.js:120`, `web/js/eyeball.js:231` | `user: ctx.user` in the body |
| `web/js/since.js:38` | `api.since(markerN, context.user)` — `?user=<id>` |
| `GET /api/since`, `GET/POST /api/acks` (spec §9 table) | `user` is a request parameter |

So the default today is: everyone is **ron** until they pick otherwise, and any caller may claim any
id. The log is permanent and `externalId`/history are the only things that cannot change (`AGENTS.md`)
— a `PIN` "signed" by a switcher is a weak signature on a permanent record.

A config file is not the place to fix this either: `deploy/config/users.yaml` is *"attribution, not
authorization"* by its own comment, and it must stay that way — an id there is stamped into decisions
forever and is **never reused**, so credentials do **not** belong in it, and must never be committed.

---

## 2. The model: who you are, held in a cookie

Auth is a **hub listener concern**, not a log or derive concern. Nothing about it reaches
`trex-core`, the writer, or the line format.

- A person signs in with a **password** and gets a **signed session cookie**. The cookie carries only
  `user` and an expiry; it is not a credential store.
- The hub **verifies** the cookie on every request, and (Stage 5) **stamps** the session's user onto
  writes, so the browser can no longer name a user.
- Credentials are **PBKDF2-SHA256 hashes** in a file **outside the repo**, written by
  `trex hub passwd`. The hub reads it; nothing prints it.
- **Off by default.** No `--auth` means exactly today's behaviour, so every existing test and a
  loopback dev run are untouched. Auth is a switch the host turns on.

What it is **not**: authorization (ron and mel can still do everything — `users.yaml`'s own rule, "any
user may close any grain"), a roles or permissions system, a change to the log format, or a change to
any derived table. It is a door and a signature.

---

## 3. Operator decisions

| Id | Question | Answer | Annotation |
|---|---|---|---|
| **A-1** | What must it protect? | **Both: the LAN gate *and* verified attribution** (accepted) | Neither half alone is worth the build: a gate without a stamp leaves the switcher, a stamp without a gate leaves the LAN open. |
| **A-2** | How does a person prove it, and how does it travel? | **Per-user password → signed `HttpOnly` session cookie** (accepted) | Machines get no credential: see A-4. |
| **A-3** | Where do secrets live? | **PBKDF2 hashes in a file outside the repo**, written by `trex hub passwd` (accepted) | Not `deploy/config`, not the image, not git — ever. |
| **A-4** | Must `/mcp` and the CLI authenticate too? | **No — browser only** (accepted) | Recorded as a *residual seam* (§8): `/mcp` inherits the listener and stays open. |
| **A-5** | Session lifetime | **30 days, sliding on use; no remember-me toggle** | A household hub on a trusted LAN: re-typing a password weekly is friction with no gain. |
| **A-6** | Cookie flags | **`HttpOnly; SameSite=Lax; Path=/`; `Secure` only when TLS is in front** | Over plain HTTP on the LAN a `Secure` cookie would simply never be sent; see §8. |
| **A-7** | Default posture | **Off unless `--auth <file>` is given. A non-loopback bind without auth logs a startup WARN** | Keeps dev and the test suite as they are, and makes the dangerous combination visible instead of silent. |
| **A-8** | Who may log in | **A declared `active` user only; `agent` is not a login** | `agent` stays what it is (D4): the MCP writer's identity, never a person. An inactive user's password is refused at login and by `passwd`. |
| **A-9** | Brute force | **A fixed 250 ms delay per failed attempt, and a per-user failure counter in memory for the log; no lockout** | LAN, two users, root on the host for a reset: a lockout is a self-inflicted denial of service. |

---

## 4. The shape in detail

### 4.1 The credential file (`--auth <path>`, `TREX_AUTH_FILE`)

Outside the repo, `0600`, root — the same handling as the other host secrets. Created by
`trex hub passwd`.

```yaml
# /opt/trex/auth.yaml — root only, 0600. Never in the repo, never in the image, never synced by
# `sync-config` (it is not a deploy/config file and config drift must not know about it).
version: 1
# Generated once, when the file is created. Changing it signs everyone out.
sessionSecret: "<32 random bytes, base64>"
users:
  ron:
    pbkdf2: "sha256:600000:<salt-b64>:<derived-b64>"
  mel:
    pbkdf2: "sha256:600000:<salt-b64>:<derived-b64>"
```

- Hashing: JDK `SecretKeyFactory` / `PBKDF2WithHmacSHA256`, 600 000 iterations, 16-byte random salt,
  compared with `MessageDigest.isEqual` (constant time). No new dependency; `Hashes.sha256` is *not*
  reused — a password is not a revision.
- A user key naming someone absent from `users.yaml`, or `active: false`, is a `passwd` refusal.
- Missing/unreadable `--auth` file, or a malformed one, is a **startup error** (like an empty
  `users.yaml`), not a silent "open" — a typo must not disable the gate.

### 4.2 The CLI

```
trex hub passwd --auth /opt/trex/auth.yaml --user ron      # prompts twice, writes the hash
trex hub passwd --auth /opt/trex/auth.yaml --list           # ids and set/unset, never a hash
```

Creates the file and its `sessionSecret` on first use; `--user mel` adds or replaces mel's hash; a
`passwd` for an `active: false` user is refused. This runs on the host, as root, over SSH — the one
place a plaintext password is typed and immediately discarded.

### 4.3 The session cookie

```
trex_session = v1.<user>.<expEpochSeconds>.<base64url(HMAC-SHA256(secret, "v1|user|exp"))>
```

- Verified on every request: parse, check `exp > now`, recompute the MAC, `MessageDigest.isEqual`.
  Any failure is anonymous — never an error page with a hint.
- Sliding: a verified cookie past its half-life is re-issued with a fresh `exp`.
- `Logout` clears it (`Max-Age=0`) and, since the hub keeps **no session table**, that is the whole
  revocation model: changing `sessionSecret` in the file invalidates every session at once.

### 4.4 What is gated

| Path | Anonymous | Note |
|---|---|---|
| `GET /login`, `POST /api/login`, `POST /api/logout` | open | The login page carries its own inline CSS: nothing else needs to be open. |
| `GET /api/whoami` | `401` | The UI's first call; `200 {user, name}` once signed in. |
| `GET /`, `/index.html`, `/ui/*` | `302 → /login?next=<path>` | A browser clicking a link should land on a form, not on JSON. |
| `/api/*` (all of §9, `/api/jobs…` included), `/head`, `/api/events` | `401 {"error":"not signed in"}` | SSE included: `connect()` in the UI treats a `401` as "go to `/login`". |
| `/mcp` | **open** (A-4) | By decision. Pinned by a test so it cannot drift silently (§7, test 7). |

### 4.5 Enforcement point

One wrapper, not one check per route. `HubHttpApi.start` gains an `Auth` argument (null = today) and
every gated `createContext` / `route` call goes through a single `gate(handler)` helper:

```
HubCommand (--auth, TREX_AUTH_FILE) → HubConfig.authFile → HubService.start → Auth.load(file)
                                                          → HubHttpApi.start(..., auth)
```

`Auth` lives in `trex-v2-hub` (`trex.v2.hub.auth`), next to `McpConfig` and `RunnerClient`; the
`passwd` subcommand lives in `trex-v2-dist` beside `HubCommand`. `trex-core` is untouched — it stays
pure and deterministic (`AGENTS.md`).

### 4.6 Attribution: the stamp (A-1's second half)

With a session, the user is a **fact of the connection**, so the hub stops trusting the body:

- `POST /api/decisions`, `POST /api/cash`, `POST /api/acks` — the session's user is stamped, and the
  actor is **forced to `user`**. Stamping the user alone is not enough: the writer drops `user` to null
  for any other actor, so a draft arriving with `actor: "system"` would be logged as a machine and lose
  the person. A body that names a *different* user is **`403`**, not silently overridden: a mismatch is
  a bug or an attack, and either way the honest answer is to fail loudly. (The MCP path keeps its own
  rule unchanged: `actingUser` must be a declared user or `agent`, `McpWriteTools`.)
- `GET /api/since?user=` — the `user` parameter is **ignored**; the session decides whose "since you
  last cleared" line is read. (`GET /api/acks` takes no `user` and reports every user's read rows for
  the client to pick from; a `POST` body's `user` is ignored and stamped.)
- The UI deletes the `acting as` switcher and `trex.user` from `localStorage`; `ctx.user` arrives from
  `GET /api/whoami`; the status strip reads `Signed in as Ron · Sign out`.
- **With sign-in off the shell still needs a name**, so `GET /api/whoami` answers
  `{user, name, signedIn: false}` for the **default acting user** — the first declared `active` user in
  `users.yaml` (`ron`) — and the client keeps *declaring* that user in a write body, exactly as it did
  before sign-in existed. The hub only stamps when it has a session; a client-declared user is
  redundant when it has one. (This was missed by the first pass and caught in review: dropping the
  client's `user` outright broke every write in the sign-in-off dev setup, which the API-level tests
  did not cover.)
- Still true, and worth saying: **names are not permissions.** ron and mel may each close any grain,
  exactly as `users.yaml` says; auth changes *who the log says did it*, not what anyone may do.

---

## 5. Spec amendments (Stage 1, same change)

| Where | Change |
|---|---|
| `V2-SPEC.md` **§9.5** (new) | *Sign-in and attribution*: the login, the cookie, §4.4's gate table, A-4's open `/mcp` stated as a decision with its consequence, A-7's default, and the stamping rule of §4.6. |
| `V2-SPEC.md` §9 table | Add `GET /login`, `POST /api/login`, `POST /api/logout`, `GET /api/whoami`; amend the `/api/since`, `/api/acks`, `/api/decisions`, `/api/cash` rows to say the user comes from the session. |
| `V2-SPEC.md` §9.1 | The status strip's acting-user switcher → the signed-in identity and **Sign out**. |
| `V2-SPEC.md` §5 | One sentence: the hub stamps `user` from the session; the decision envelope and the line format are unchanged. |
| `V2-SPEC.md` §12 | Auth is **not** a `deploy/config` file: `--auth <path>` / `TREX_AUTH_FILE`, outside the repo, outside config drift, 0600. |
| `docs/DEPLOYMENTS.md` | Replace *"There is no authentication."*; add the `auth.yaml` + `trex hub passwd` runbook; restate the `/mcp` note honestly (§8). |
| `CHANGELOG.md` | Under `[Unreleased]` → **Added**, with the "not in the repo" config note and the upgrade step (the first `--auth` run). |

**Downstream view (ezbookkeeping): none.** Auth touches no derive input, no category, no identity rule
and no unit, so `trex egress ezb` is unchanged. This is stated in the spec PR because `AGENTS.md`
requires the effect to be named, even when it is nil.

---

## 6. Stages (each compiles, tests green before the next)

| Stage | Work | Files |
|---|---|---|
| **1** | Spec and docs only, no code. | `V2-SPEC.md`, `docs/DEPLOYMENTS.md`, `CHANGELOG.md` |
| **2** | `AuthFile` (read, PBKDF2 verify, `sessionSecret`, refusal rules) + `trex hub passwd`. | new `trex.v2.hub.auth.AuthFile`, `trex-v2-dist/…/cli/HubPasswdCommand.java`, `Main.java` |
| **3** | `Session` (sign/verify/expire/slide) + `Auth`; `gate()` in `HubHttpApi`; `/api/login`, `/api/logout`, `/api/whoami`; the startup WARN (A-7). | `trex-v2-hub` (`HubHttpApi`, `HubConfig`, `HubService`), `trex-v2-dist/…/cli/HubCommand.java` |
| **4** | The login page and the shell: `boot()` reads `/api/whoami`, the API wrapper turns `401` into a redirect, the switcher is retired. | `web/login.html` (new), `web/js/app.js`, `api.js`, `status.js`, `sse.js` |
| **5** | The stamp: hub-side stamping for decisions/cash/acks, the `403` mismatch rule, the ignored `?user=`, and the UI stops sending `user`. | `HubService` (`submitDecisions`, `submitCash`, `postAck`), `web/js/decisions.js`, `cash.js`, `eyeball.js`, `since.js` |
| **6** | **Deferred, not built now** (A-4): a machine token for `/mcp` and the CLI, TLS/`Secure`, a real lockout. | — |

---

## 7. Acceptance tests

1. **Off by default is a real guarantee.** With no `--auth`, `GET /api/status` and `POST /api/decisions`
   behave exactly as today (this guards every other test in the suite).
2. `POST /api/login` with the wrong password → `401`, no `Set-Cookie`; with the right one → `200` and a
   cookie that is `HttpOnly` and `SameSite=Lax`.
3. A tampered cookie, an expired cookie, and a cookie signed with an old `sessionSecret` are each
   `401`; `GET /` anonymously is a `302` to `/login?next=…`.
4. Signed in as **ron**, `POST /api/decisions` with a body naming `mel` → `403`, **and the log is
   unchanged** (read the head before and after).
5. Signed in as **ron**, a normal decision appends a row whose `user` is `ron` — read back from the log,
   not from the response.
6. `--user mel` on an `active: false` mel → `passwd` refuses; a login for mel when inactive → `401`.
7. **`/mcp` still answers with no cookie** — the A-4 decision, pinned, so that turning it on later is a
   visible spec change and not a quiet refactor.
8. A restart with the same file keeps a cookie valid; flipping `sessionSecret` invalidates it.

---

## 8. What this does not touch, and the honest residual

**Does not touch:** `derive()` and its purity; the log's format; the three content kinds; identity and
`externalId`; any derived table or unit; `trex index --rebuild`; the sequencer's loopback posture; the
MCP attribution rules; anything in `trex-core`.

**The residual, stated plainly** — two things this build deliberately leaves open, both consequences
of A-4 and A-6:

1. **`/mcp` stays open.** An unauthenticated caller on the LAN can still read everything the MCP read
   tools expose, and — only if the host sets `TREX_MCP_ALLOW_WRITES=1` — post decisions as `agent`.
   Recommendation: leave `TREX_MCP_ALLOW_WRITES` unset on the host, and treat Stage 6 as the fix if
   the hub is ever reachable by a device you do not own.
2. **Plain HTTP on the LAN.** The password crosses the wire readable to any eavesdropper on the
   segment, and so does the cookie. A gate without TLS stops the casual other-device reader, not a
   network sniffer. **Recommended posture, and the one that actually closes both:** bind the hub to
   loopback (`TREX_HUB_BIND=127.0.0.1`) and reach it from the LAN through an SSH tunnel — which also
   closes seam 1, because `/mcp` becomes unreachable off-host again. Auth then protects the *tunnel
   users*, and the LAN is not a trust boundary at all.

That second point is the one to decide before deploying: with TLS absent, this plan is a login that
says who you are, not a wall.

---

## 9. Follow-ups this plan does not cover

- Machine credentials: `X-Trex-Token` for `/mcp` and `trex mcp --token` (Stage 6).
- TLS termination and the `Secure` cookie flag (A-6).
- A `trex hub passwd --revoke <user>` for a lost device — today, changing `sessionSecret` signs
  everyone out, which is enough for two people.
- Whether `/api/refdata` should still list every user, now that no switcher reads it (it does feed the
  notes' "by" labels; leave it).
