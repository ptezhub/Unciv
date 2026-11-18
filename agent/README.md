# agent — a headless host for Unciv

`agent` runs an Unciv game with no window, no libGDX render loop and no input handling.
It loads a save (or starts a new game), keeps the `GameInfo` in memory, and exposes it over
HTTP/JSON so another program — a script, a bot, an LLM agent — can read the board and submit
moves. The same process also answers MCP `tools/list` / `tools/call`, and serves the file
endpoints the official Unciv client uses for multiplayer sync.

It is a fork-only module. Upstream has no equivalent: `server/` is a save relay that never
runs the game, and `ConsoleLauncher` is a fixed AI-vs-AI balance harness.

## Layout

```
agent/
  src-java/uncivagent/Agent.java   HTTP routing, identity/tokens, read projections, /new /load /save
  src-java/uncivagent/Kernel.java  intent validation + apply, tool table, MCP adapter
  src-java/uncivagent/Json.java    ~140-line JSON reader (no dependencies)
  src/Main.kt                      first single-civ prototype; superseded, not compiled
  docs/PROTOCOL.md                 routes, intent shape, error codes, MCP, file sync
```

`Kernel` is the only place that mutates game state for agent requests. HTTP `/intent`, the
legacy `/act` and `/move` shims, and MCP `tools/call` all funnel into `Kernel.handleIntent`,
so the three surfaces cannot drift apart. Reads go straight to projection functions on
`Agent` and never touch the kernel.

## Build

```
./gradlew :agent:installDist
```

Output: `agent/build/install/agent/bin/agent` plus `lib/` with `core`, gdx and the Kotlin
runtime.

On the machine this fork was developed on, the Gradle wrapper cannot reach
`services.gradle.org` reliably, so the build goes through a wrapper script that pins a local
JDK and Gradle distribution:

```
~/.openclaw/bin/unciv-build :agent:installDist
```

That script sets `JAVA_HOME=~/.openclaw/cache/jdk/jdk-21.0.5+11`,
`GRADLE_USER_HOME=~/.openclaw/cache/gradle-home`, puts `gradle-9.4.1` on `PATH`, and runs
from the repository root. A cold `:core` compile takes about 34 minutes there; once
`:core:jar` exists, `:agent:installDist` is roughly 20 seconds. The module targets Java 17
(the host uses `com.sun.net.httpserver` and `InputStream.readAllBytes`); `core` stays on 1.8
for Android.

## Run

The working directory must be `android/assets` — rulesets are loaded relative to the current
directory, and without them the process starts with zero rulesets and every action fails.

```
cd android/assets
UNCIV_AGENT_PORT=8099 \
UNCIV_AGENT_FILES=$HOME/.openclaw/state/unciv-host/files \
UNCIV_AGENT_SAVE=/abs/path/to/game.json \
  ../agent/build/install/agent/bin/agent
```

| Variable | Default | Meaning |
|---|---|---|
| `UNCIV_AGENT_PORT` | `8099` | listen port, all interfaces |
| `UNCIV_AGENT_FILES` | `/home/node/.openclaw/state/unciv-host/files` | directory behind `/files/{name}`; created on boot |
| `UNCIV_AGENT_SAVE` | unset | save to load at boot; without it the host waits for `POST /load` or `POST /new` |

Quick check:

```
curl -s localhost:8099/health                       # {"ok":true}
curl -s -X POST localhost:8099/new -d '{"players":2,"humans":1,"seed":913}'
curl -s localhost:8099/state
curl -s -X POST localhost:8099/intent \
  -d '{"type":"end_turn","params":{}}'
```

Full route list, intent schema, error codes and MCP methods: [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Differences from upstream

Four changes, on branch `agent-host`:

1. **`settings.gradle.kts`** — adds `"agent"` to `include(...)`.
2. **`core/src/com/unciv/logic/map/mapunit/actions/UnitActions.kt`** — two `GUI.isWorldLoaded()`
   guards. `addEscortAction` calls `GUI.getWorldScreen()` while enumerating actions, so a
   headless host threw NPE before the action list was complete; `addSkipAction` calls
   `switchToNextUnit()`, which is pure UI navigation. Both are no-ops in the desktop and
   Android clients, where the world screen exists whenever those actions are reachable.
3. **`core/src/com/unciv/ui/audio/SoundPlayer.kt`** — two `Gdx.audio == null` guards. A headless
   host has no audio backend, and any unique that plays a sound (ancient ruins, promotions,
   era changes) threw while the action was being applied — `POST /new` could fail outright if a
   starting unit walked onto a ruin. The guards make sound a no-op where there is no backend.
4. **`agent/`** — this module, plus its `.gitignore` entries.

Nothing else in `core` is touched. The host runs on the unmodified upstream game logic, so a
save produced here is an ordinary Unciv save.

## Syncing with upstream

```
git remote add upstream https://github.com/yairm210/Unciv.git   # once
git fetch upstream master
git rebase upstream/master
```

Three commits are expected to conflict, and all are small:

- `settings.gradle.kts`: keep `"agent"` in the `include(...)` list, and re-check whether
  upstream added modules of its own.
- `SoundPlayer.kt`: re-apply the two `Gdx.audio == null` guards; they sit at the top of
  `get()` and inside `Preloader.preload()`.
- `UnitActions.kt`: re-apply the two guards if upstream rewrote the surrounding functions.
  Re-read the code before resolving — the guards exist because of a call into `GUI`, not
  because of line numbers. If upstream ever grows a proper headless path, drop them.

Then rebuild and re-run the smoke checks above. If `getUnitActions` starts returning actions
whose `available` flag is `false` for everything, suspect a ruleset loading failure (wrong
working directory) before suspecting the guards.

## License

Unciv is MPL-2.0 (see `LICENSE` at the repository root). This module is distributed under the
same license. MPL is file-level copyleft: modified upstream files — `UnitActions.kt`,
`settings.gradle.kts` — stay under MPL and their source must remain available, which a public
fork satisfies. New files in `agent/` are also MPL-2.0 here for simplicity; nothing in the
license forces that on independently written files.
