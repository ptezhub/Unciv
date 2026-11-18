# Conformance benchmark

The point of this suite is narrow: an agent should lose points for playing badly, never for
the harness misbehaving. Every check here is about the interface, not about strategy.

```
python3 agent/tests/conformance.py --port 8200        # starts and stops its own host
python3 agent/tests/conformance.py --keep             # leave it running to poke at
```

Exit code is 0 only at 100. Failures print a group id, so a regression names itself.

## Scoring

| Group | Weight | Question |
|---|---|---|
| P0 | 5 per check | A failure here makes a turn unplayable or silently wrong |
| P1 | 2 per check | Costs extra calls, or a wrong belief about the board |
| P2 | 1 per check | Polish |

`score = 100 * sum(passed * weight) / sum(all * weight)`.

P0 is reserved for things that corrupt state or make the surface unusable: a 5xx, a token
bypass, an action that reports success and changes nothing, a failed action that still costs
the turn.

## Groups

**A contract** — `/health`, `/tools` shape (unique names, valid schemas, known kinds), MCP
`tools/list` identical to HTTP, `initialize` handshake.

**B auth** — single-player fallback before any token exists; after `POST /admin/token` a
missing or unknown token is 401; unknown civilization is 404 with candidates; `POST /new`
invalidates tokens.

**C validation** — malformed JSON, unknown tool, read tool submitted as an intent, stale
`turn`, someone else's unit or city, tiles outside the map. All must be documented error
codes with HTTP 200, never 5xx.

**D state** — same `id` twice applies once; a different payload under the same `id` is
refused (`ID_REUSED`) instead of silently dropped; `end_turn` advances; save/load round-trips
turn and cities; a missing save path is 404.

**E effects** — for each mutating action, the effect is visible in a follow-up read: founded
city appears in `/cities`, production set is what `/city/{id}` says, research matches, a move's
`to` matches `/units`, worked tiles match, a rejected purchase leaves gold untouched, an
unknown specialist lists the valid ones, an invalid trade offer echoes the names, `declare_war`
shows up as `atWar`, `capture_city` without a pending capture refuses with a list.

**F consistency** — `/cities` and `/city/{id}` agree on production and population; unit ids in
`/state` and `/units` are the same set; a Gold offer never exceeds the treasury.

**G robustness** — a dozen malformed query strings (`?cx=abc`, `?limit=abc`, `?unit=abc`,
radius out of range) must not produce 5xx.

**H determinism** — the same `seed` gives the same civilization and the same starting army.
Starting *positions* come from `gameId`, which the engine randomises per game, so they are not
compared; `/state` echoes `seed` and `gameId` so a benchmark can record which game it got.

**J MCP arguments** — arrays and nested objects sent through MCP `tools/call` reach the kernel
intact (a bad tile comes back as a coordinate, not as a stringified list), and an action run
over MCP returns the same event shape as the same action over HTTP. This is the check that
would have caught the earlier flattening bug.

**I failure cost** — four failing actions in a row (bad move, unbuildable item, unaffordable
purchase, malformed intent) must leave turn, gold, production, position and movement untouched.
Plus an intent fuzz set and a latency floor (`/new` under 15s, a turn under 3s on a Duel map).

## The other half: a game that actually runs

`conformance.py` checks the interface. `play_smoke.py` checks that a whole game runs through
it:

```
python3 agent/tests/play_smoke.py --port 8240 --turns 50 --players 3 --size Small
```

It starts its own host, plays N turns with a deliberately dumb heuristic (found a city, build
the first available item from a fixed list, research the cheapest tech, walk military units
outward, buy the cheapest purchasable), and prints one JSON object:

```json
{"turns_played": 50, "civ": "Persia", "cities": 1, "population": 5, "techs": 11,
 "gold": 94, "happiness": 2, "units": 4, "known_civs": 4,
 "requests": 341, "harness_errors": [], "ok": true}
```

`harness_errors` must stay empty — any 5xx or `INTERNAL` is a harness fault, not a playing
mistake. The rest is the metric set to compare between models: turns survived, cities,
population, techs, happiness, contacts. A model that plays badly should show worse numbers
with `ok: true`; `ok: false` means the harness failed it.

## Sieges, and why the harness is not to blame

Two soak runs failed to take a city, and both times the cause was a game rule, not the API.
Worth writing down so a benchmark run is not scored as a harness failure:

- A city heals **20 HP every turn**, unconditionally (`CityTurnManager`). Damage below 20 per
  turn is zero progress.
- Same-era city defence is around 95. Infantry (70) deals ~25 and takes ~38; melee is a losing
  trade. Ranged is the only way in, and a single artillery piece nets ~8 HP per turn.
- Ranged attacks require the target to be **in view** (`viewableTiles`), even with Indirect
  Fire, so the effective range is shorter than `/units.range` suggests unless something is
  spotting. `/units` reports engine range; the attack error now says so when nothing is
  visible.
- Units are **disbanded** when the treasury cannot pay upkeep, the turn after they appear. An
  agent that queues artillery at -7.5 gold per turn loses it silently unless it reads events.
  `end_turn` now warns about negative income and the number of turns left.

The recipe that should work: two or three cities of production, artillery at 2 tiles with a
spotter, gold per turn positive before queueing them, and a melee unit to walk in once the city
is at zero. `capture_city` itself has only been exercised on its error path so far.

## What this does not cover

Game-state-dependent paths: capturing a city, launching a nuke, stealing a tech, upgrading a
unit. Those need a game far enough along; they are exercised by the soak runs under
`workspace/notes/unciv-soak*.md` instead. If a check here needs a specific board, it belongs
in a scenario script, not in this suite.

## Adding a check

```python
check("E", "E11 my thing", "P1", lambda: my_thing(h))
```

`check` catches assertion errors and unexpected exceptions as failures, so a broken check
scores zero rather than crashing the run. Prefer asserting on a read-back over asserting on
the action's own response: the whole point is that the two agree.
