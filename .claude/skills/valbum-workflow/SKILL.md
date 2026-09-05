---
name: valbum-workflow
description: The working method for developing VAlbum2 — orchestrating sub-agents across the two toolchains (Maven backend and the Flutter app), adversarial probe reviews, the verification gate, the run loop against the demo server, GitHub-issue triage, the commit discipline, and the data/format doctrines. Load at the start of any session doing feature or bug work on this repository.
---

# The VAlbum2 working method

This skill is the meta-knowledge of how a session on this repository should run. It complements,
never duplicates, `CLAUDE.md` (build facts, gotchas, conventions) and `CONTRIBUTING.md` (the
public build instructions). Read both first, then `ROADMAP.md` — the direction record (vision,
phases, decisions log). The GitHub issue tracker **is** the work queue; each issue names its
roadmap phase (see §1 and §8).

## 1. The operating loop

Work is a pipeline, not a pile:

1. **Intake**: user reports (chat or GitHub issues) and the open issues at `haumacher/valbum2`.
   Bugs outrank queued features. Triage every new GitHub issue with a diagnosis comment *before*
   fixing; use the existing labels (`status:in-progress`, `priority:p0`).
2. **Reproduce before dispatching.** A backend report gets a headless reproduction first — a JUnit
   test against the fixture album at `image-server/src/test/fixtures/test-album`, or the demo
   server (§7) hit with `curl .../valbum/data/<path>?type=json`. A Flutter report gets a widget
   test with an injected HTTP client (the test binding answers every real request with 400 — see
   §4). Know whether it is a real bug, an already-fixed one, or your own probe error before an
   agent burns tokens. If the main tree is agent-owned, reproduce in a git worktree at HEAD
   (`git worktree add <scratch>/repro <commit>`), never in the live tree.
3. **Dispatch** one sub-agent per coherent package (§2). Sequential by default; parallel only with
   disjoint file ownership stated in both briefs (§3). The two toolchains give a natural split.
4. **Review with a novel probe** (§4). Non-negotiable.
5. **Gate** (§5), **commit to master and push** (§6), **rerun the app** (§7), close the issue with
   the fixing commit plus a summary comment naming the regression test.
6. **Before ever finishing: `gh issue list -R haumacher/valbum2`.** Stop only when the issue list
   holds nothing actionable. Anything you defer becomes an issue, so a crashed session loses nothing.

Track packages with TaskCreate/TaskUpdate; keep statuses honest (in_progress on dispatch,
completed only after the commit is pushed).

## 2. Agent briefs — what makes them work

Use Opus for feature/bug packages (standing directive: Opus or cheaper; Sonnet only for genuinely
mechanical chores). Run in background; you'll be notified. A brief that produces a good delivery
contains, in order:

- **Grounding**: "Read CLAUDE.md and CONTRIBUTING.md. Study <the specific modules, the specific
  classes, and the specific tests that are the behavioral contract>." Name the module the work
  lives in (`image-server`, `image-server-shared`, `util-servlet`, `valbum_ui`)
  and whether `model.proto` is in scope.
- **The task as design intent**, not implementation orders — with the load-bearing decisions
  made: what is stored in a sidecar file vs derived at request time, what the JSON protocol
  carries vs what the client computes, what refuses vs heals. Quote the user's own words where
  they designed the feature; their design is usually better than your draft.
- **User fixtures embedded verbatim** as required regression tests: a folder layout, a sidecar
  file, a JSON response, a Flutter screen description — whatever the report contained. Extend the
  fixture album only with tiny generated images (see `GenerateTestAlbum`), never real photos.
- **Explicit acceptance tests**, including exact values where computable (layout row heights,
  thumbnail sizes, JSON field presence). "read → write sidecar → read again is equal" belongs in
  almost every backend brief.
- **The build discipline block** (the point is not wasting runs, not avoiding them):
  - Maven: the edit loop runs the **owning module's tests only**
    (`mvn -q -pl :image-server test -Dtest=TestPreviewCache`); a change in `image-server-shared`
    needs `-am` or a prior `install` of it, and any `model.proto` change needs the shared module
    rebuilt **before** anything that consumes the generated classes or `resource.dart`. The full
    `mvn clean install` runs **twice per delivery**: once before the first edit (only when taking
    over foreign or uncommitted work) and once at the end before the report.
  - Flutter: the edit loop runs `flutter analyze` and the **targeted** test file
    (`flutter test test/<file>_test.dart`); the full `flutter analyze && flutter test` runs at the end.
  - A run whose inputs did not change is not repeated "to be sure". Batch independent lookups
    (several greps in one call) instead of one grep per turn.
- **The standing rules block**: ALL existing tests green in both toolchains; `mvn spotless:apply`
  on touched Java; Dart is formatted by the PostToolUse hook (never reformat `resource.dart`);
  **never hand-edit generated code** (`resource.dart`, the msgbuf Java model) — edit `model.proto`;
  Java source/target follows the root pom (1.8 until issue #11); **the server never modifies, moves or deletes an original
  photo or video** — all state goes to sidecar files; do NOT commit; **nothing half-done — cut whole
  items only, refuse in-app with a visible reason, and report every cut**.
- **Ask for a report**: mechanism chosen and why, alternatives rejected, cuts, test count
  before/after per toolchain. The report is your review input; a vague report predicts a vague
  delivery.

**Reworks go back to the same agent** via SendMessage with the failing probe path and the demand:
*fix it generally, not to the probe*. If several rework rounds fail, question the architecture,
not the tests.

**Stalled agents are real.** If a background agent's transcript goes quiet: arm a Monitor on the
output file's mtime (10-minute quiet threshold, until-loop, not tail -f). **The tasks-dir output
path is a symlink — stat it with `-L`**, or the monitor reads the symlink's own never-changing
mtime and cries wolf in exact 600 s increments. Verify before acting on any event: a quiet
transcript with a busy `mvn` or `dart` process is a long build, not a stall. On silence: nudge-resume via SendMessage ("Resume exactly where you left off: <last
visible step>"). If the transcript stays frozen after a resume, the agent is dead — take the
remainder over yourself; its uncommitted work is usually further along than the last message
suggests (`git status`, then run its tests before redoing anything).

**Crash recovery.** The crashed session's transcript is
`~/.claude/projects/<project-dir>/<session-id>.jsonl`; its sub-agents' transcripts are
`<session-id>/subagents/agent-*.jsonl` beside it; `~/.claude/tasks/<session-id>/` holds the task
list. Pull the last `Agent` tool_use out of the session transcript with a short python filter —
that is the brief, verbatim — and the last tool calls out of the newest sub-agent transcript to see
where it died. The agent's uncommitted work is in the tree: `git status`, `mvn -q compile`, then
**re-dispatch the same brief with a resume preamble** stating exactly what exists, what compiles,
and what is not started; never redo the work. The demo server does **not** survive a crash
(`ss -ltnp | grep 9090`; restart per §7). Check `gh issue list` immediately — reports filed during
the crash are untriaged.

## 3. Concurrency — the rules

- **Never let two agents edit the same file.** When parallelizing, write an explicit FILE BOUNDARY
  paragraph into *both* briefs ("do not touch X, Y, Z; if you must, STOP and report").
- **`model.proto` has one owner per round.** It generates both the Java model and
  `valbum_ui/lib/resource.dart`, so a proto change crosses the toolchain boundary. Whoever changes
  the proto owns the regeneration and the compile fallout on *both* sides; the other agent must
  not touch the proto, and must not rebuild the shared module while the proto is mid-edit.
- **Concurrent Maven runs in one tree collide** on `target/` and on `~/.m2` installs of the same
  SNAPSHOT. Never run `mvn` in a tree an agent is mid-edit in; give a second agent its own git
  worktree (§10) and have it use `mvn verify` (not `install`) so it cannot overwrite the main
  tree's installed artifacts.
- The Flutter app and the backend are independent trees under one repo: a Flutter agent in
  `valbum_ui/` and a backend agent in the Maven modules can share a working tree **only** if
  neither touches `model.proto` and the backend agent does not run a build that regenerates
  `resource.dart` while the Flutter agent is testing.
- A commit made while an agent has uncommitted work in the tree must be **selective**
  (`git add <files>`).

## 4. The probe review (the quality mechanism)

After a delivery reports done and before committing: **write a test the agent never saw**,
composing the new feature with pre-existing features — sidecar read/write round-trips, nested
album folders, videos next to images, the layout algorithm at odd viewport widths, a folder with
  no index picture. Good probes ask
"is the mechanism general?" not "does the happy path work?". Keep passing probes as permanent
tests (named `Test<Feature>Probe` in Java, `<feature>_probe_test.dart` in Flutter).

**Expect your own probes to be wrong first.** The recurring traps — check these before blaming the
delivery:

- **Widget tests cannot reach the network**: under `TestWidgetsFlutterBinding` every HttpClient
  request returns 400 and nothing is sent. A probe that pumps `VAlbumApp()` and waits for album
  data will find nothing; inject a fake client or test the widget below the fetch. (The shipped
  `test/widget_test.dart` is the Flutter starter template — it counts a counter that does not
  exist and fails; it is a known project defect, not your regression.)
- **The backend URL is hardcoded** in `valbum_ui/lib/main.dart` (`localhost:9090/valbum/data`);
  a probe against a server on another port is probing the wrong thing.
- **Generated code drifts by design**: after a proto change, `resource.dart` and the Java model
  are rewritten on the next Maven build — a probe compiled against the old shape fails to compile,
  which is the build telling you to rebuild the shared module, not a defect.
- **Stale module installs**: a test in `image-server` that behaves as if a shared-module change
  never happened is running against the old `~/.m2` artifact — rebuild with `-am` or `install`
  the shared module.
- **Fixture album contents**: `test-album` has real folder names with spaces and dates and a
  `generated` subfolder — quote paths; and the demo server is started with this fixture, so a
  probe that writes sidecar files there dirties the tree (`git status` before committing).
- **Analyzer noise in generated Dart**: `flutter analyze` reports infos/warnings inside
  `resource.dart` (underscored locals, unknown doc directives). Those are generator output; fix
  them in the generator or ignore them, never by hand. The bar is **zero errors**, and no *new*
  warnings in hand-written files.

- **Request paths in a `MockClient` handler are percent-encoded.** A fixture folder named
  `2002-03-03 Schlosspark Karlsruhe` arrives as `.../2002-03-03%20Schlosspark%20Karlsruhe/`; a
  handler matching on the name with spaces never hits and serves its fallback (usually the
  listing) for the album path — the app then renders a listing where the probe expects an album,
  and the route looks right while the screen looks wrong. Match on `Uri.decodeComponent(path)` or
  on the date prefix.
- **The Bash tool resets its working directory between calls.** A chain that relies on an earlier
  `cd` runs in the repo root (or its parent); use absolute paths or one `cd` per command, and
  never `git add -A` after a relative `cd ..`.
- **`pkill -f <pattern>` matches your own shell** when the pattern appears in the command line you
  are running, and kills the rest of the chain (exit 144). Anchor it (`pkill -f "^java -jar"`).
- **The Playwright MCP writes into the repository root** (`.playwright-mcp/`, `recording.json`),
  not the scratchpad; both are in `.gitignore` now — never `git add -A` while a browser session is
  open in another agent.
- **A merge is not gated by the commits it merges.** Run both gates on the merged tree before
  pushing, even when every branch was green on its own.

When a probe legitimately fails: send it back (§2). When it exposes something deeper than the
package (a protocol gap, a format hazard), fix small ones yourself with a regression test; spawn a
package for large ones. Two classics to stay paranoid about: silent refusals (nothing may decline
without a visible message), and anything that writes next to the user's photos (must be a sidecar
file with a known name; never a rename, resize or delete of an original).

## 5. The verification gate (before every commit)

```bash
mvn spotless:apply                                  # then check its output for errors
mvn clean install                                   # both toolchains' generated code included
cd valbum_ui && flutter analyze && flutter test
```

All green or no commit: Maven build success, analyzer with zero errors, every Flutter test passing
(except the known starter-template test until it is replaced — say so explicitly in the report).
Both toolchains must be run even when only one side changed, because `model.proto` couples them.
Never trust a green result from a tree with uncommitted foreign edits; check `git status` first.

## 6. Committing

- **Commit to `master` and push to `origin/master` every time** (standing directive; CLAUDE.md
  records this convention). Feature branches and PRs are for external contributors. `Closes #N`
  in the body closes the issue.
- House style: an imperative one-line title stating the change, then a paragraph of the why
  (never a bullet changelog), ending with the Co-Authored-By line from the harness rules.
- The agents don't commit; you do, after review. Regenerated `resource.dart` and Java model files
  ride the same commit as the `model.proto` change that produced them.

## 7. The run loop

Two things to run, always in this order:

```bash
mvn exec:java@test-server -pl :image-server        # backend API on http://localhost:9090/valbum/data/
cd valbum_ui && flutter run -d chrome              # the Flutter app with hot reload, against localhost:9090/valbum/data
```

The server also hosts the built web app: `cd valbum_ui && flutter build web`, then `mvn install`
bundles `valbum_ui/build/web` into the jar (a missing build is skipped, not an error), or pass
`--webroot valbum_ui/build/web` to the jar to serve a directory. The served `index.html` gets its
`<base href>` rewritten to the context path, and any extension-less path falls back to the app.

The demo server serves the fixture album; after a backend change, rebuild and restart it (it does
not hot-reload). It creates an empty `.upload` directory in the fixture album — remove it before
committing. The Flutter app
hot-reloads (`r` in the `flutter run` terminal). Tell the user the commit hash and what to try.
Never leave a broken build running — the served demo is the shared reference for bug reports.

## 8. Data and format doctrines (enforce in every brief and review)

- **Originals are sacred.** The server reads the photo tree and writes only sidecar files beside
  it. No code path may rename, rewrite, resize in place, or delete a user file. A probe that finds
  one is a p0.
- **Sidecar files are a persisted format.** A stored field's meaning is frozen the moment a build
  that writes it might have shipped. Semantic changes need a compatible read path for old files
  and a test that loads a fixture written by the older build; in-build round-trip tests prove
  nothing across builds.
- **`model.proto` is the single source of truth** for the wire model. Never edit generated output;
  never let the Java and Dart sides drift by hand.
- **One client, one protocol.** The Flutter app is the only front end (the GWT client was removed
  in ROADMAP Phase 0). A protocol change lands with the Dart consumer adjusted in the same commit.
- **Java source/target** follows the root pom (1.8 until issue #11 raises it to 21).
- **Refusals speak**: no route may decline silently; the UI shows the reason.
- **Everything generic**: issues are exercises of general mechanisms — if a fix is shaped like the
  bug report, it isn't done.
- **Decisions live in ROADMAP.md, issues and commit messages.** Direction-level decisions go into
  the roadmap's decisions log; the commit message is the as-built note and the issue comment is
  the rationale; deliberate cuts become issues, never silence.

## 9. Working with this user

They are an expert (Java, software architecture) and the project's author, testing continuously.
Their bug reports come as folder layouts, screenshots or pasted JSON — embed them verbatim as
regressions. Their feature sketches are usually complete designs — restate the design back
crisply, adopt it, credit it in the commit message. When they overrule a recorded decision, the reversal is
documented in the issue, not litigated. Answer their questions with the mechanism, then the plan,
then queue position. Run notices always include the commit hash and what to try.

## 10. Two agents at once — the worktree pattern

Parallel packages work when, and only when, they are physically separated:

- **One agent in the main tree, the other in a git worktree**
  (`git worktree add -b <branch> <scratch>/wt-x HEAD`, run **from this repo**), each with a FILE
  BOUNDARY paragraph. Separate trees keep their `target/` and `build/` output apart. The worktree
  agent uses `mvn verify`, never `install`, so it cannot overwrite the main tree's `~/.m2`
  artifacts; if it needs a changed shared module, it builds with `-am`.
- **`model.proto` has one writer** (§3). If both packages need model changes, serialize them.
- **Merging**: commit the worktree branch yourself (house-style message), merge it into master
  after the main-tree package is committed, run the full gate (§5) on the merged tree — generated-
  code and build-file changes are exactly where merges break — then push.
- After a merge, `git ls-files` the new directories the report names; a `.gitignore` rule
  (`target/`, `build/`) can swallow a wrongly named source directory.
- **A session limit kills every running agent at once** (HTTP 429 mid-flight). Their uncommitted
  work is in the trees; when the limit resets, `SendMessage` each one "Resume exactly where you
  left off: <its last visible step, read from the transcript's last tool calls>" rather than
  re-dispatching. Make the stall monitor grep the transcript tail for "session limit" so the two
  cases are told apart.
