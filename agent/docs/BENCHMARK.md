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

**I failure cost** — four failing actions in a row (bad move, unbuildable item, unaffordable
purchase, malformed intent) must leave turn, gold, production, position and movement untouched.
Plus an intent fuzz set and a latency floor (`/new` under 15s, a turn under 3s on a Duel map).

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
