# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **P2P readings on the panel and in the doctor (the plan's T6, readings half)** (2026-10-22). The panel
  snapshot gained an optional `p2p` block (`readP2p`), and the sidebar card renders the three-tier copy
  from §7.4: `直连 43 ms` / `中转 812 ms · 本趟走中转可能慢` / `对端不在, 走 git`, plus the trust state
  (`新对端已加入` / `已信任` / `换机待确认` / `已吊销`), the six-digit SAS, sent/received counters, the
  cooling flag and the last error. `sync_doctor` prints the same three tiers plus trust/SAS/cooling and
  the last P2P error code. Trust-table actions (confirm a changed node, revoke) are not on the panel yet.
- **P2P identity, trust table and SAS (the plan's T5)** (2026-10-22). The sidecar now keeps a stable
  ed25519 identity when given `LW_KEY_FILE` (32 raw bytes, mode 0600, generated once and reused —
  proven on device and across restarts). `lib/p2p-trust.mjs` stores per-device `nodeId` trust in
  `<repoDir>/.p2p/trust.json` (git-ignored, atomic, unknown keys kept): first sight is trusted, a changed
  `nodeId` is `P2P_NODE_CHANGED` and blocks the dial, a revoked peer is never dialled, and `SAS` derives
  the same six digits on both ends from the sorted node ids. Peer cards now carry a `p2p` section
  (`nodeId`/`ticket`) that the card reader/writer round-trips and preserves across rewrites; the service
  consults the trust table before every dial, writes new peers, reports `trustState`/`blockedPeer`/`sas`,
  and the own card is written with our `p2p.nodeId` once the sidecar is up. Inbound checks compare the
  sidecar's node id (not the repo device id).
- **P2P service and engine hooks (the plan's T4, continued)** (2026-10-22). `lib/p2p-service.mjs` resolves
  the peer from the `p2p.nodeId` line in `peers/*.json` (skipping our own card and refusing to dial before
  the engine's device id exists), runs the two-segment session once per `push`/`pull` via
  `beforeSync()`, and owns the resident lifecycle (inbound loop for every enabled profile, 60 s heartbeat
  for `resident`). `P2pRunner` gained the inbound loop and dial-target precedence
  (ticket → nodeId → device id). In `index.mjs` a P2P attempt now precedes `pushSync`/`runPullSync`
  (failures fall through to the existing transport and warn once), `sync_doctor` reports the runner's
  status, and a mount-scoped effect starts/stops the resident half. Everything stays inert while
  `p2p.enabled` is false, which is still the default.
- **P2P runner: budget, breaker and readings (the start of the plan's T4)** (2026-10-22).
  `lib/p2p-runner.mjs` wraps the sidecar and the session state machine into a schedulable action:
  outbound (`ensure` → dial → session) and inbound (accept → peer-id check → same machine), a 20 s
  session budget that closes the stream on timeout, a 3-failure → 60 s breaker (`P2P_BUSY` while cooling,
  one success resets it), a 60 s heartbeat hook, and the §7.4 status shape (`path`/`rttMs`/counters/
  `cooling`/`lastError`/`gc`). Failures return `{ok:false}` instead of throwing so the engine can fall
  back to git without a delay.
- **P2P garbage collection (the plan's T3 tail)** (2026-10-22). `lib/p2p-gc.mjs` handles the three
  reclaimable leftovers from §6.3 and leaves the mailbox alone: `.p2p/tmp` is cleared at startup or
  after 24 h; a revoked or 90-day-unseen peer loses only its `base/<peerId>/` manifest cache; and a path
  that is absent from the peer's manifest for two consecutive rounds *and* has a local copy older than
  30 days is deleted through the same tombstone path (trash + `.p2p/deleted.json`). All three counters
  fold into `status.p2p.gc` via `gcSummary`.
- **P2P peer sessions and a real loopback e2e (the plan's T3 core)** (2026-10-22).
  `lib/p2p-peer.mjs` runs one session over any frame stream: HELLO (version + device id checked),
  HEADS, then two half-duplex segments ordered by device id — pull a root's manifest (paged), diff it,
  `NEED` the missing paths, consume `BLOB`s in batches, apply single files atomically and `conv` sets only
  after the whole set verifies, answer `MANIFEST_REQ`/`NEED` for the other side, and propagate
  tombstones into `.p2p/trash` (never touching the mailbox or the local device's files). `P2pSidecar`
  now opens both directions of the data plane (`openFrameStream` / `acceptInbound`, `p2p.inbound`
  defaults on) and closes its callback server on `stop()`. The opt-in loopback test (`LW_P2P_E2E=1`)
  drives two real sidecars over QUIC and moves a live file, a conv set and a tombstone deletion.
- **P2P apply and manifests (the plan's T3, continued)** (2026-10-22). `lib/p2p-apply.mjs` encodes the
  author rules (`maySend`/`mayReceive`, with the mailbox exception), rejects absolute/`..`/NUL paths,
  writes single files atomically with sha256 verification, finalises a `conv/<device>/<session>/` set only
  after the whole set verifies (old copy goes to `.p2p/trash/<date>/`), and applies tombstones — own
  paths only, never the mailbox, 200 paths / 32 MiB per round, deletions recorded in `.p2p/deleted.json`
  so a later git push does not turn them into remote deletions. `lib/p2p-manifest.mjs` builds per-root
  manifests (one row per `conv` session, 5000-row pages), cross-device stable digests, and the
  missing/changed diff that becomes `NEED`.
- **P2P wire frames (the start of the plan's T3)** (2026-10-22). `lib/p2p-frame.mjs` implements the
  8-byte `LW`/version/type/length header, a streaming decoder that survives arbitrary chunk splits, the
  nine frame types from §5.4/§5.5 (including `MANIFEST_REQ`), and the `BLOB` body shape (self-describing
  JSON header + raw bytes). Bad magic, version mismatch, unknown types and bodies over 8 MiB all raise
  `P2P_PROTOCOL`; business semantics stay in the frame layer's callers, not here.
- **P2P sidecar supervision (the plan's T2)** (2026-10-22). New `lib/p2p-config.mjs` + `lib/p2p-sidecar.mjs`
  turn the optional `p2p` config section into a supervised local process: the binary is looked up next to
  `libnode.so` on Android / under `native/win32-x64/` on the desktop, spawned with a per-process token and
  ephemeral ports, `READY` is parsed for the control/data ports, and `/v1/id` supplies the endpoint id and
  ticket. Without a binary `status()` reports `available:false` + `P2P_UNAVAILABLE` (it never throws), an
  unexpected exit is restarted twice (2 s / 10 s backoff) and then reported as `P2P_CRASH`, and `stop()`
  asks `/v1/shutdown` before falling back to a kill. `sync_doctor` prints a `p2p` line, and nothing in this
  layer implements the sync protocol — it only moves bytes for the frame layer that follows (T3+).
- **The dialogue has its own append-only ledger (`conv/`)** (2026-10-22, the plan's P1). The `live/`
  snapshot is a rolling window (40 entries / 16 000 chars), so a projection whose cursor fell behind
  that window lost the stretch in between for good. Every device now appends its settled
  user/assistant entries to `conv/<device>/<session>/chunks/…` — 16 entries per chunk, one JSON
  object per line, each carrying the host event `seq`. Sealed chunks never change, so a push only
  carries the tail chunk (a few KB) and a turn no longer leans on the multi-MB archive to keep the
  dialogue; the ledger is written from the same `condense` pass as the live snapshot, so the two
  cannot disagree. `meta.json` carries `chunkEntries` / `entries` / `chunks` / `bytes` / `sha256`,
  the append is idempotent by `seq`, and `conv/` is single-writer: only the owning device writes its
  own folder (`ownershipOfPath` keeps a pull from forking it).
- **Folding a peer's answers fills from that ledger when the window moved** (2026-10-22). If the
  peer's snapshot is truncated and the local cursor sits before its first entry, the missing stretch
  is read from `conv/<peer>/<session>/` and merged by `seq` before `proxyEventsFrom`, so rounds the
  peer ran while this device was offline no longer punch a hole in the projection.

### Fixed

- **A folded answer left the projection session unloadable** (2026-10-22). The synthetic
  `assistant/message` had no `message.source`, and the store's V4 format refuses any durable message
  without a producer-owned `source.kind` — the whole log was then reported as `stored log is
  corrupt` and the conversation pane came up empty. The log itself had the exchange, which is why
  the live view looked fine until a page reload or a host restart. Folded answers now carry
  `source: { kind: 'model', provider: 'luwi-peer', model: 'luwi-peer' }`, the shape the host writes
  for its own answers; existing projection logs were repaired in place (the attribution was added
  to every durable message that lacked one).

### Changed

- **A parts set is one unit on both sides: pulled whole, pushed whole** (2026-10-22). Parts are
  updated one by one, so a pull that decided per path could keep the remote's `0004.bin` next to
  the local `0005.bin`, and a push that unioned per path could put two lineages into one set — the
  `sha256` in `parts/index.json` only stopped the damage at the adopt step (the set stayed broken
  in the mirror). The pull now decides per set: it reads the remote index first, takes the set only
  when its `bytes` beats the local one (index bytes, or the flat session file when the local side
  has not been cut yet), then writes the parts and the index last. The push applies the same rule
  in reverse: a local set that wins overwrites the same names and lists the remote's extra parts as
  deletions; a remote set that wins leaves the local parts out of the push entirely. A set that
  loses keeps its bytes in git history (the pull checkpoint or the remote commit); it is not copied
  into a fork file, because fork files never travel and a forked set would only add noise on both
  sides.
- **A mirror round no longer deletes the extra parts of a longer set** (2026-10-22). When the mirror
  already holds a set that is longer than what the local session store has (a peer just appended, or
  a fresh pull has not been adopted yet), the mirror skipped the write — but the delete phase still
  reconciled against the *local* cut, so the parts beyond it were removed and had to be downloaded
  again. Every existing part file now counts as a target for that round: neither overwritten nor
  deleted.
- **The first pull of a big session is bounded per trip** (2026-10-22). A fresh device used to
  fetch every part of every set in one call (the mirror here is 8 sets / 14 MB, the largest 5.7 MB
  in 22 parts), which pushed the queued `inbox/` lines behind it and could run into the 240 s pull
  deadline. A trip now takes whole sets only — smallest first, up to 32 parts and 4 MiB, always at
  least one — and leaves the rest for the next minute. A trip that defers a set does not stamp the
  six-hour head cache, so the next pull keeps going; the parts already fetched are skipped by sha,
  so finishing a set needs no extra bookkeeping.
- **An incomplete parts set is `deferred`, not a failure, and a part file is never adopted alone**
  (2026-10-22). Adopting used to list a missing or half-written set under `failed`, which read as
  a real error every time a pull landed mid-set; it now lands in `deferred` and is retried.
  A `parts/*.bin` with no `index.json` next to it is skipped entirely (it used to be adopted as a
  plain file into the session store, leaving a `parts/` directory there).
- **The pull and push readings now show what the parts path cost** (2026-10-22): the pull result
  carries `partSetsTaken` / `partSetsDeferred` / `partsFetched` / `bytesFetched` (and `deferred`
  for the sets still landing), the push result carries `uploadedParts`. Without them "did it save
  anything" was a guess.

- **The panel is one page: pairing first, diagnostics and device cards gone** (2026-10-22). The
  three internal tabs (Overview / Actions / Diagnostics) are merged into a single scroll: the
  pairing card sits at the top (the first thing you do on a new phone is scan a code, so it leads),
  then the repository card, the mirror readings, the pull/push actions, and the peer channel. The
  diagnostics tab, its "self-check" / "copy report" buttons, and the peer-card block are removed —
  `doctor` and `peers` still exist on the host side (`/sync doctor`, the tools), the panel just does
  not show them any more.
- **Session logs travel as zstd frames, so a push no longer re-uploads the whole file** (2026-10-22).
  On this desktop the sync can only reach GitHub through the REST transport (`github.com:443` is
  blocked here), and that transport has no delta: any changed blob is uploaded whole. A session
  log grows by 60~80 KB per turn, and every turn was re-uploading the full 3~5 MB file. The host
  writes those logs as append-only multi-frame zstd (a 5.5 MB session is 4077 frames of ~1.4 KB),
  so the mirror now cuts them at frame boundaries into `parts/0000.bin`, `parts/0001.bin`, … plus a
  small `parts/index.json` (`file` / `bytes` / `parts`). Sealed parts never change, so blob-sha
  dedupe skips them and a push only carries the growing tail, bounded by the part size (256 KiB).
  Reassembly is a plain concatenation: byte-for-byte identical to the source file, verified against
  the live session store. Sessions under 256 KiB and non-zstd files keep the old single-file
  layout, and a session that switches layouts has its old flat copy deleted in the same round.
- **The per-session size cap goes from 4 MB to 16 MB** (2026-10-22, the owner's call). The 4 MB
  cap existed because a whole-file upload made a multi-MB session too expensive to re-send every
  turn; with parts a push only carries the tail, so the cap is now about what the other device has
  to download on first sight, and 16 MB covers even long sessions.
- **`parts/index.json` carries `sha256` of the assembled bytes.** Parts are updated one by one,
  which leaves a window where the remote part and the local part at the same index come from two
  different lineages (both sides typed into the same session). Reassembly now refuses a set whose
  digest does not match: that pull keeps the local session and the next mirror round re-cuts it
  from the local source, instead of writing a blend of two sessions into the session store.
- **A pull no longer re-reads the whole mirror every tick** (2026-10-22). Two costs stacked up
  under a one-minute pull interval: `finishExternalPull` ran even when the remote head had not
  moved (so every tick re-assembled every parts session — 16 MB of sessions is 16 MB of reads per
  minute), and each assembly rebuilt the full file just to compare its length. The adopt step now
  runs only when the API pull actually brought something, and it skips a parts directory whose
  `bytes:parts:sha256` fingerprint and local file size are unchanged. A quiet minute is now one
  metadata request; a busy one only reassembles the sessions that changed.

## [0.0.2] - 2026-10-15

### Added

- **The sync panel can write the configuration** (a `configure` op plus the Repository card
  in the Actions tab): fill in the git remote, set the branch and flip the sync switch
  without editing `$DSH_HOME/lw/session-sync.json` by hand. Turning sync off parks the
  address in `lw.savedRemote` and zeroes the three auto values, exactly like the phone's
  settings page does; turning it on puts them back. Every key this layer does not own is
  kept byte for byte.
- **The file layer now works on a desktop host too.** `readLuwiSyncConfig` only ever looked
  at `env.DSH_HOME`, and the Electron main process does not put that into the host's
  environment — it passes the profile directory as argv. So on a desktop the JSON layer was
  empty all along, and the panel could write an address that nothing ever read (the
  symptom: `remoteState: unset` while the address sits in the file). The home directory is
  now derived from the parent of `sessionRoot`, which is always available.

### Fixed

- **A session could vanish right after a line was typed into it, and the answer would land
  in a different session** (a real device, 2026-10-15). Two rules were fighting each other:
  the desktop reported its session list without the sessions the peer had reported back (an
  echo guard), and the phone reported the desktop's own adopted copies straight back to it —
  so the desktop stopped reporting a session the phone was actively projecting, the phone
  concluded the peer no longer had it, and archived its own projection (hiding a session
  from every grouped list). The echo guard now always reports a session that is running,
  and a session the peer *did* report this round is only dropped from the projection ledger,
  never archived.
## [0.2.24] - 2026-10-10

### Changed

- The README now carries the terminal demo and an animated version of it: the same
  real run, shown assembling line by line. The static image stays as the primary
  one, because a crawler or an assistant can read a PNG and cannot read a GIF.

  Both frames are that plugin's own output — the card is generated by running its
  code over its own fixture, not drawn by hand.


## [0.2.23] - 2026-10-05

### Changed

- Correct the release date in the previous section, which was stamped with the literal string `undefined` by the release stamper. No content or behaviour change; the version is bumped only because npm will not republish an existing version.


## [Unreleased]

### Added (Luwi fork)

- The ownership guard (`agent/pre-step`) gained a **fourth criterion**: a locally created session
  whose `cwd` sits inside the shadow root (`<DSH_HOME>/lw/peer-workspaces/…`, i.e. a peer workspace
  mirrored locally) belongs to the peer. Its line goes out with the new `kind: 'new'` target — the
  peer **opens a session in that workspace** — and the session id it returns is bound as this local
  session's projection, so the peer's answers fold back into the very session the user is looking
  at. When the line cannot go out, a local notice is appended and the local model still never runs
  (fail closed).
- New target kind `new` in `normalizeTarget()`: "open a new session in that workspace". The
  `workspace` kind keeps its old "reuse the most recent session, or open one" semantics. An older
  peer coerces `new` to `session` with an empty id and answers a plain "could not resolve the target
  to a session on this host", so version skew fails closed instead of executing locally.
- `sync_peer` takes a `fresh` flag for `action: send` (without `sessionId`): open a new session on
  the other machine instead of reusing its most recent one.
- The panel snapshot reports `peer.workspace.newSessions: true` — a read-only version criterion for
  this batch, the same trick the `peer` block used.

### Fixed (Luwi fork)

- The panel's send box under the cross-device peer card posted `target: { kind: 'session', id: '' }`,
  which the receiving side can never resolve: the button had never worked. It now sends
  `{ kind: 'new', name }` together with a workspace picker filled from the peer's reported
  workspaces.

## [0.2.22] - 2026-10-04

undefined

## [0.2.21] - 2026-10-04


### Changed

- Host pins move to `0.2.1-alpha.1`; re-verified against that host line. Every `@deepseek-ai/dsh-*` dev/test dependency now pins `0.2.1-alpha.1`, the `dshWorkshop.compatibility.dshVersions` timeline appends `0.2.1-alpha.1`, and the compatibility baseline in every README records the `dsh-v0.2.1-alpha.1` host. The declared host ranges (`engines.dsh` and the `peerDependencies` union) gain the `|| >=0.2.0-0 <0.3.0 || >=0.2.1-0 <0.3.0` clauses: the previous upper bound was `<0.2.0`, which under semver rejects every 0.2.x host, so the probe host itself was not installable. Nothing was narrowed — the `0.1.x` clauses are unchanged, in place and in order.

## [0.2.20] - 2026-09-25

### Changed

- Host pins move to `0.1.7-rc.2`; re-verified against that host line. Every `@deepseek-ai/dsh-*` dev/test dependency now pins `0.1.7-rc.2`, the `dshWorkshop.compatibility.dshVersions` timeline appends `0.1.7-rc.2`, and the compatibility baseline in every README records the `dsh-v0.1.7-rc.2` host. The declared host ranges (`engines.dsh` and the `peerDependencies` union) are deliberately **unchanged** — they already admit `0.1.7-rc.2`, and a range is what the manifest accepts, not what has been tested.

## [0.2.19] - 2026-09-24
### Changed

- Host pins move to `0.1.7-rc.1`; re-verified against that host line. Every `@deepseek-ai/dsh-*` dev/test dependency now pins `0.1.7-rc.1`, the `pnpm-lock.yaml` graph is regenerated, `dshWorkshop.compatibility.dshVersions` appends `0.1.7-rc.1`, the monthly Compat workflow and the CI probe install the `0.1.7-rc.1` host, and the compatibility baseline in every README records `dsh-v0.1.7-rc.1`. The declared host ranges (`engines.dsh` and the `peerDependencies` union) are deliberately **unchanged** — they already admit `0.1.7-rc.1`, and a range is what the manifest accepts, not what has been tested.

## [0.2.18] - 2026-09-23

### Changed

- **Re-verified against the `0.1.7-alpha.2` host; the `@deepseek-ai/dsh-*` host pins move to `0.1.7-alpha.2`.** The `alpha.2` wave removes nothing this plugin consumes: the published type surface of every host package it resolves is either byte-identical to `0.1.7-alpha.1` (`@deepseek-ai/cordis` `4.0.3`≡`4.0.4`, `@deepseek-ai/schemastery` `3.18.3`≡`3.18.4`) or strictly additive (`ToolDefinition.projectContent?`, `ClientModuleLoader.importError()`, six added `dsh-client-locale` keys). The wave's only removals are internals no plugin in this family references — `dsh-subprocess-local` privates and its non-entry `bindManagedProcess`, `dsh-client-web` `assertEntriesActive`, `dsh-app-boot`'s `unhandledRejection` event, `dsh-client-ui-plugin-manager` `apply()`, a refined `dsh-client-ui-primitives` `CodeBlock` signature, and the `diff.files.one`/`diff.files.other` locale key pair. Both rulers therefore stay green on the moved pin: `typecheck` against the checkout, `typecheck:ci` against the published `0.1.7-alpha.2` faces.
- The declared host range is deliberately **unchanged**. It already admits `0.1.7-alpha.2` (`0.1.7-alpha.2` satisfies the `>=0.1.7-0 <0.2.0` clause), and the family convention keeps peer ranges wider than the verified line rather than narrowing them to it; a range is what the manifest accepts, not what has been tested.
- Repo documentation (`AGENTS.md`), the workspace graph pin (`pnpm-workspace.yaml`) and the published-line workflow pins move with the manifest, so no file still claims the previous line.


## [0.2.17] - 2026-09-22

### Fixed

- The conflict fork notice no longer writes the harness-retired `{ kind: 'plugin', plugin: … }` message source. DeepSeek Harness `0.1.7-alpha.1` turned `MessageSourceMap` into a merge-extensible producer-owned union with no shared `plugin` catch-all, and `@deepseek-ai/dsh-session-format-v3-to-v4`'s `assertV4MessageSources` refuses `kind === 'plugin'` when a session is read back, so a notice written by an earlier build left the forked session readable but not continuable. `injectForkNotice` now writes `{ kind: 'dsh-session-sync', form: 'notice', summary: … }` — the plugin's own producer-owned kind, declared by declaration merging in `types.d.ts`. `form` and `summary` are unchanged, so no user-visible behavior changes.

- `scripts/loader-runner.mjs` sweeps `ctx.registry` for `FiberState.FAILED` fibers after `ctx.loader.await()` and rethrows their `_error`. `cordis-plugin-loader` 1.0.4 (the loader the 0.1.7 host vendors) catches an import failure inside `Entry._init`, logs it and returns, and `EntryTree.await` settles its tasks with `Promise.allSettled`, which never rejects — so a failed mount stopped propagating and the invalid-config and default-export regression cases were exiting non-zero for the wrong reason (`the /sync command is missing`). `FiberState` is a `const enum` with no runtime object, so its `FAILED` value (`3`) is mirrored.

### Added

- `test/source-readback.test.mjs`, a real-host read-back gate: it drives the payload captured from `injectForkNotice` through the host's own admission functions (`assertV4RowAdmission`, `releasedV4SessionFormatCodec.encodeEvent`, `restoreReleasedV4Artifact`) and through `Session.fromRestore` + `deriveMessages`. It admits the new kind, admits the V3-migrated legacy `plugin:dsh-session-sync` form, and pins that the retired `{ kind: 'plugin' }` form is refused — a source-kind regression now fails at the read-back boundary instead of only in the type gate.

### Changed

- Pin the `@deepseek-ai/dsh-*` dev/test dependencies to `0.1.7-alpha.1`, `@deepseek-ai/cordis` to `^4.0.3` (4.0.2 does not export `Volatile`, and `@deepseek-ai/dsh-session-format-v3-to-v4` peer-requires `^4.0.3`), `@deepseek-ai/schemastery` to `^3.18.3` and `@deepseek-ai/cosmokit` to `^1.8.4`. `pnpm-workspace.yaml` gains package-name `overrides` for the bare cordis/cosmokit/schemastery peer edges plus a self-referential `dsh-session-sync` entry, so one copy of each resolves and the host type graph cannot fork. `dshWorkshop.compatibility.dshVersions` now records `0.1.7-alpha.1`.

- Widen `engines.dsh` and every `peerDependencies` range with a fourth `|| >=0.1.7-0 <0.2.0` clause, and add `@deepseek-ai/dsh-session-format-v3-to-v4` to devDependencies. The clause is a bug fix rather than a gratuitous widening: under semver's prerelease rule a prerelease version satisfies a range only when some comparator shares its `major.minor.patch` tuple *and* carries a prerelease tag, so the previous three clauses excluded `0.1.7-alpha.1` — the target host itself.

- Declare `@deepseek-ai/dsh-llm` as a peer. `index.mjs` imports `createUserMessage` from it and `types.d.ts` augments its `MessageSourceMap`, so the dependency the plugin actually consumes was previously undeclared.

### Docs

- Refresh the five-language README compatibility baseline to `dsh-v0.1.7-alpha.1` (verified 2026-09-22) and record the producer-owned source kind in the session-log bullet. `AGENTS.md` gains the `MessageSourceMap` merge in the `types.d.ts` layout line, the corrected test-file count, and the accurate split between the two typecheck gates.

## [0.2.16] - 2026-09-19

### Added

- `pnpm run check:lockfile` (`scripts/check-lockfile-drift.mjs`) fails fast when `package.json` and `pnpm-lock.yaml` disagree; the probe is read-only and the documented checks chain runs it alongside the other gates.

### Changed

- The release workflow now publishes through **npm trusted publishing** (OIDC) instead of the long-lived `NPM_TOKEN` secret: `setup-node` no longer sets `registry-url` (its empty `_authToken` line made the registry answer 404 on PUT), npm is upgraded to >= 11.5.1 before publishing, and the "NPM_TOKEN is not set -> skip" guard is gone so a missing publisher cannot turn a release into a silent no-op.
## [0.2.15] - 2026-09-18

### Fixed

- The storage-domain handle no longer leaks across an unmount that lands while `ctx.storageDomain.open` is still in flight. The domain effect is registered synchronously inside the apply frame and owns the handle, so the disposer closes the domain once the open settles instead of throwing `INACTIVE_EFFECT` into a swallowed rejection. The silent `.catch(() => {})` is gone: an open or close failure now warns with its reason, and consumers still await the table promise.

- The `approval` confirmation channel reads the open-turn state from the host `turnBoundary` session projection (`openTurnStartSeq`) instead of the removed `Session.events` property. On hosts of the 0.1.6 line the previous read was always `undefined`, so `/sync pull|push` under `confirmVia: approval` failed even inside an open turn; it now proceeds there, and fails closed with an actionable reason when the projection is not composed (`@deepseek-ai/dsh-session-projection` missing) or no turn is open. The conflict path's session-level fork uses the same projection read and skips the fork when the turn state is unavailable (fork files are still preserved).

### Changed

- Host-private session artifacts are never mirrored or deleted. `session.lock` (the harness session lease) and `session.migration.*.tmp` staging files are per-host runtime state; copying them into the worktree leaked one device's transient state into shared history, and deleting them from the worktree churned commits (a previously committed lock could also be removed by a device that simply had no open session). Both mirror loops now protect them; session logs (`session.jsonl`, `session.v[1-9]*.jsonl[.zstd]`) remain the mirrored payload, and a regression test pins that they still sync. A source-side or target-side `session.lock` no longer changes the mirrored file count.

- Fork-path mapping is anchored on the fork file name itself (`forkFileName`'s pattern) instead of a fixed path depth: the session id is the fork file's parent segment, which resolves the host `<projectKey>/<sessionId>/` layout and the legacy flat layout identically and stays correct at any nesting depth.

### Added

- `dsh.manifestVersion: 1`, the canonical three-clause `engines.dsh` range, and an optional peer on `@deepseek-ai/dsh-session-projection` (the open-turn read falls back to a fail-closed reason when it is absent).

## [0.2.14] - 2026-09-12

### Changed

- Rename the four translated READMEs to `README-<lang>.md`. npm selects the package-page readme as the first markdown file matching its `{README,README.*}` glob (`@npmcli/package-json`, publish path), and that glob order puts `README.<lang>.md` ahead of `README.md` — so npm was serving the Simplified-Chinese file for this package too (measured on 15/15 sampled packages of the family). The new names sit outside the glob, so the English source is served again. No content changed apart from the language-switcher link each translation holds to its siblings, and the repo readme gate still passes. Takes effect with the next release; an already-published version cannot gain a corrected readme retroactively.
- Pin the `@deepseek-ai/dsh-*` dev/test dependencies to the published `0.1.5-rc.2` line and record `0.1.5-rc.2` in `dshWorkshop.compatibility.dshVersions`; the monthly Compat workflow now runs against `0.1.5-rc.2`. The peer range `>=0.1.2-rc.1 <0.2.0 || >=0.1.5-alpha.1 <0.2.0` is unchanged, so no supported host line is dropped.

## [0.2.13] - 2026-09-10

### Changed

- Pin the `@deepseek-ai/dsh-*` dev/test dependencies to the published `0.1.5-rc.1` line and record `0.1.5-rc.1` in `dshWorkshop.compatibility.dshVersions`; the monthly Compat workflow now runs against `0.1.5-rc.1`. The peer range `>=0.1.2-rc.1 <0.2.0 || >=0.1.5-alpha.1 <0.2.0` is unchanged, so no supported host line is dropped.

### Docs

- Refresh the five-language README compatibility baseline to `dsh-v0.1.5-rc.1` (verified 2026-09-10).

## [0.2.12] - 2026-09-09

### Fixed

- Generate the git-backend fork timestamp as strictly 14 digits: `lib/engine.mjs` stripped only `-` and `:` from `toISOString()` before slicing 14 characters, so the ISO `T` survived (`20260909T13523`), `forkFileName`'s `/^\d{14}$/` check failed, and every git-backend conflict fork silently fell back to the epoch stamp `19700101000000`. The stamp now removes `-`, `:` and `T` exactly like `lib/encrypted.mjs`, yielding a real `yyyyMMddHHmmss` UTC stamp; the encrypted backend was already correct and no other behavior changes. Regression tests assert a 14-digit non-epoch stamp on a real fork path plus the acceptance/fallback contract in `test/paths.test.mjs`.

## [0.2.11] - 2026-09-09

### Fixed

- Map fork files to sessions with the host's real session-store layout `$DSH_HOME/sessions/<projectKey>/<sessionId>/<file>`: the session-level fork lookup treated the project key as a session id, so on harness `dsh-v0.1.5-alpha.1` (session format V3, zstd by default) every session-level fork and its `sync/conflict` event was silently skipped while the fork files were still kept. `lib/paths.mjs` now exposes `sessionIdOfForkPath` and `decodeSegment` (the inverse of the host's `~XXXX` segment escaping), `handleForks` uses them and warns instead of staying silent when a fork path cannot be mapped, and the legacy flat `<mirrorDir>/<sessionId>/<file>` layout still maps when the last segment looks like a file. The byte-level mirror, merge, and encryption path is unchanged; no new dependencies.

## [0.2.10] - 2026-09-09

### Fixed

- Restore a green `pnpm install --frozen-lockfile` on pnpm 11: pnpm 11 enables `minimumReleaseAge` by default (1440 minutes) and its frozen-install lockfile supply-chain verification pass ignores `minimumReleaseAgeExclude` — both the exact `@deepseek-ai/dsh-*@0.1.5-alpha.1` entries and an `@deepseek-ai/*` pattern — so the freshly published `0.1.5-alpha.1` dev/test pins failed with `ERR_PNPM_MINIMUM_RELEASE_AGE_VIOLATION` (17 lockfile entries, reproduced locally against a clean store). `pnpm-workspace.yaml` now sets `minimumReleaseAge: 0`, matching the sibling PerryLink plugins, and keeps the exclusion list as the documented intent. The `v0.2.9` tag already carried that list and still failed at the Install step in both Release and CI, which is why `0.2.9` never reached npm; no runtime behavior changes.

## [0.2.9] - 2026-09-09

### Changed

- Align the `@deepseek-ai/dsh-*` peer ranges to `>=0.1.2-rc.1 <0.2.0 || >=0.1.5-alpha.1 <0.2.0` and pin the dev/test dependencies to the published `0.1.5-alpha.1` line: adaptation to DeepSeek Harness `dsh-v0.1.5-alpha.1` (session format V3, `ctx.agent` removal, `Inbox` type-only interface); runtime behavior is unchanged for every supported host line.
- Record `0.1.5-alpha.1` in `dshWorkshop.compatibility.dshVersions`.

### Docs

- Refresh the five-language README compatibility baseline to `dsh-v0.1.5-alpha.1` (verified 2026-09-09).

## [0.2.8] - 2026-09-07

### Docs

- Fix the DSH plugin badge URL: shields.io rejects the four-segment static badge form with "404 badge not found"; the label now uses the documented double-dash form (`dsh--plugin`), rendering identically; no behavior change.


## [0.2.7] - 2026-09-07

### Fixed

- Align the `@deepseek-ai/dsh-*` peer ranges to `>=0.1.2-rc.1 <0.2.0`: the older `>=0.1.0-rc.8 <0.2.0` band resolved to only the `0.1.0-rc.8` prerelease under registry-driven resolution and broke fresh tarball installs; no behavior change.

### Docs

- Refresh the five-language README support-version wording: the verified GitHub tag `dsh-v0.1.3-alpha.1` now leads the compatibility claim, while npm `0.1.2-rc.1` stays the published dependency-pin line (peers `>=0.1.2-rc.1 <0.2.0`); no behavior change.


## [0.2.6] - 2026-09-04

### Fixed

- Remove the `storage` / `storage-json` / `storage-domain` rows from the bundle patch: the shipped profiles compose that stack through `dsh-base`, so the inserted rows collided with the same ids and made the profile refuse to boot (`duplicate loader entry id: storage`). The patch now mounts only the `session-sync` row; bare profiles compose the storage stack themselves. The manifest test asserts the new patch contract (plugin row only).

## [0.2.4] - 2026-09-04

### Changed

- Align the devDependency pins to the published dsh `0.1.2-rc.1` line (12 `@deepseek-ai/dsh-*` packages), the `dshWorkshop` compatibility list, and the compat workflow's CLI/base/headless installs; the five-language READMEs record the rc.1 facts. No behavior change: the adaptive `sync/*` event gate stays closed on `0.1.2-rc.1` (`Session.append` still cannot stamp the `ignorable` marker).

## [0.2.3] - 2026-09-02

### Docs

- Sync the five-language READMEs to the 0.1.2-alpha.5 facts; no behavior change.

## [0.2.2] - 2026-09-02

### Changed

- Aligned the devDependency pins to the published dsh `0.1.2-alpha.5` line (12 `@deepseek-ai/dsh-*` packages) and raised the compat probe pins to `0.1.2-alpha.5`. The session-event adaptive gate stays closed on `0.1.2-alpha.5` (`Session.append` still cannot stamp the `ignorable` envelope), so behavior is unchanged.

## [0.2.1] - 2026-09-01

### Changed

- Aligned the devDependency pins to the published dsh `0.1.2-alpha.3` line (11 `@deepseek-ai/dsh-*` packages), aligned `cordis`/`schemastery` to `^4.0.2`/`^3.18.2`, and widened the `dsh-commands`/`dsh-subprocess` peers to the standard `>=0.1.0-rc.8 <0.2.0` range. The session-event adaptive gate stays closed on `0.1.2-alpha.3` (`Session.append` still cannot stamp the `ignorable` envelope), so behavior is unchanged.
- The CI compat probe now verifies against the dsh CLI and `dsh-base`/`dsh-headless` at `0.1.2-alpha.3`; the five-language READMEs and `AGENTS.md` document the alpha.3 target.

## [0.2.0] - 2026-08-26

### Added

- `backend: encrypted` — an age-encrypted git transport that encrypts mirror content before push and decrypts it before merging, with graceful fallback to plaintext git (plus an explicit warning) when `age`/`ageRecipient`/`ageIdentity` is unavailable. `object-storage` remains a reserved placeholder that fails loud at load.
- Transport backend seam (`lib/backend.mjs`: a documented `status`/`push`/`pull` contract and a pure `selectEncryptionMode`), `lib/age.mjs` (`detectAge` + file-based `ageEncrypt`/`ageDecrypt`), and `lib/encrypted.mjs` (`EncryptedBackend`). The plaintext `git` backend and its zero-dependency default path are unchanged.

### Docs

- README threat model describing the encryption boundary: age protects the mirror content, while keys/age identities stay user-managed and the remote remains a private repository in practice.

## [0.1.4] - 2026-08-23

### Fixed

- Corrected the stale test count in the Development sections (the repo runs 10 `node --test` files, not 6 suites) across the five-language READMEs and `AGENTS.md`, and fixed `ARCHITECTURE.md`'s periodic-pull description to match the code (`ctx.effect` wrapping `setInterval`, not `ctx.setInterval`). Documentation only — no behavior change.

## [0.1.3] - 2026-08-22

### Changed

- Upgraded every `@deepseek-ai/dsh-*` devDependency to `0.1.1-rc.2`: devDependencies pin exact `0.1.1-rc.2`, peerDependencies keep their `>=0.1.0-rc.8 <0.2.0` range (the plugin uses no rc.2-only API), and `dshWorkshop.compatibility.dshVersions` now lists `0.1.1-rc.2`. The session-event adaptive gate stays closed on `0.1.1-rc.2` (the harness still does not record `sync/*` types, and rc.2 `Session.append` takes no `ignorable` envelope for non-surface types), so sessions keep loading unchanged.
- CI compat probe now verifies the plugin against `@deepseek-ai/dsh-base`/`dsh-headless` at `0.1.1-rc.2`; the five-language READMEs, `AGENTS.md`, `ARCHITECTURE.md`, and `THIRD_PARTY_NOTICES.md` document the `0.1.1-rc.2` target.

## [0.1.2] - 2026-08-21

### Changed

- Upgraded every `@deepseek-ai/dsh-*` dependency to `0.1.0-rc.8`: devDependencies pin exact `0.1.0-rc.8`, peerDependencies widen to `>=0.1.0-rc.8 <0.2.0`, and `dshWorkshop.compatibility.dshVersions` now lists `0.1.0-rc.8`. The session-event adaptive gate stays closed on rc.8 (the harness still does not record `sync/*` types, and rc.8 `Session.append` takes no `ignorable` envelope for non-surface types), so sessions keep loading unchanged.
- CI compat probe now verifies the plugin against `@deepseek-ai/dsh-base`/`dsh-headless` at `0.1.0-rc.8`; the five-language READMEs, `AGENTS.md`, `ARCHITECTURE.md`, and `THIRD_PARTY_NOTICES.md` document the rc.8 target.

### Fixed

- `scripts/loader-runner.mjs` calls the rc.8 `commands.execute(agent, line, images, signal)` signature (empty image batch), so the real-Loader composition tests run against the rc.8 commands registry.

## [0.1.1] - 2026-08-17

### Fixed

- The bundle patch now composes the storage stack (`@deepseek-ai/dsh-storage` + `dsh-storage-json` + `dsh-storage-domain`) and declares all three packages, so a bare profile gets the `storageDomain` service the plugin injects instead of hanging with `pending (waiting for service: storageDomain)`.

## [0.1.0] - 2026-08-16

### Added

- Cross-device session sync for DeepSeek Harness: a dedicated git mirror of the session store, `push`/`pull`/`status` with append-only keep-both + fork conflict resolution (never silently overwrites), the `/sync` command (`status`/`pull`/`push`/`diff`/`log`/`help`), and the `sync_pull`/`sync_push`/`sync_status` model tools.
- Configurable auto modes: pull on mount (`autoPullOnStart`), push after each closed turn (`autoPushOnTurnEnd`), and periodic pull (`pullIntervalMinutes`), all reversible Cordis effects.
- Confirmation gate for mutating operations (`confirmVia: auto`/`userQuestions`/`approval`, fail closed when no answerer); read-only surfaces never ask.
- `sync/push`, `sync/pull`, and `sync/conflict` session events behind an adaptive gate (append only when the host records the types or supports the `ignorable` envelope).
- Session-level conflict fork with a persistent `user/message` notice when a diverged session is live and has no open turn.
- `session-sync` storage-domain metadata (device id, last pull/push, last push head, last error).
- Schemastery configuration with fail-loud bounds; every tunable documented in `cordis.patch.yml` and the five-language READMEs.
