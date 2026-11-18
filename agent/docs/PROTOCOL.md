# Protocol

Everything the host speaks. One process, one loaded game, one port. Routes are matched
case-sensitively on the path; a trailing `/` is stripped before matching.

Contents:

1. [Conventions](#conventions)
2. [HTTP routes](#http-routes)
3. [Intent](#intent)
4. [Results and events](#results-and-events)
5. [Error codes](#error-codes)
6. [Tool table](#tool-table)
7. [MCP](#mcp)
8. [Official client file protocol](#official-client-file-protocol)
9. [Read projections](#read-projections)
10. [Turn semantics](#turn-semantics)
11. [Known gaps](#known-gaps)

## Conventions

- All responses are `application/json; charset=utf-8`. Requests with a body are JSON.
- The server runs with a `null` executor: requests are handled one at a time on the dispatch
  thread. No request ever observes a half-applied action, and there is no locking.
- Identity: `Authorization: Bearer <token>` or `X-Agent-Token: <token>`. Tokens are issued by
  `POST /admin/token` and map to a civilization name. With no token — or a token the host does
  not know — the request runs as the current player's civilization (single-player mode). An
  unknown token is not rejected.
- Object ids in the agent surface: `unit-<int>` (engine `MapUnit.id`), `city-<uuid>` (engine
  `City.id`), and bare civilization names. `/city/{id}` additionally accepts a raw city id or
  a city name.

## HTTP routes

| Method | Path | Body / query | Response |
|---|---|---|---|
| GET | `/health` | — | `{"ok":true}` |
| GET | `/tools` | — | `{"tools":[{name,kind,description,schema}]}` |
| GET | `/isalive` | — | `{"online":true}` |
| any | `/auth` | `Authorization: Basic` | `{"ok":true,"user":"<user>"}` |
| GET | `/files/{name}` | — | raw bytes, `text/plain` |
| PUT | `/files/{name}` | raw bytes | `{"ok":true,"name":…,"bytes":N}` |
| DELETE | `/files/{name}` | — | `{"ok":true}` |
| GET | `/admin/civs` | — | `{"turn":N,"civs":[{civ,human,alive,submitted,hasToken}]}` |
| POST | `/admin/token` | `{civ, token?}` | `{"ok":true,"civ":…,"token":…}` |
| POST | `/intent` | Intent | result, see below |
| POST | `/mcp` | JSON-RPC 2.0 | JSON-RPC response, or `202` with no body |
| GET | `/state` | — | see [Read projections](#read-projections) |
| GET | `/units` | — | " |
| GET | `/cities` | — | " |
| GET | `/city/{id}` | — | " |
| GET | `/map` | `?cx&cy&radius` | " |
| GET | `/events` | `?limit` | " |
| GET | `/diplomacy` | — | " |
| GET | `/policies` | — | " |
| GET | `/actions` | `?unit=<int>` | `{unit,actions:[{type,title,available}],truncated}` |
| POST | `/act` | `{type, unit?}` | legacy shim, see below |
| POST | `/move` | `{unit,x,y}` | legacy shim |
| POST | `/endturn` | — | same as `{"type":"end_turn"}` intent |
| POST | `/new` | `{players,humans,difficulty,speed,civ,seed}` | state of the new game |
| POST | `/load` | `{path}` | state |
| POST | `/save` | `{path}` | `{"ok":true,"path":…}` |
| * | anything else | — | `404 {"error":"没有这个接口: <path>"}` |

Anything that touches game state returns `500` with `{"error":"<exception> | <frame> | …"}`
when it throws, and the first three stack frames are inlined into the message.

`/act` is a compatibility shim over `/intent` and is lossy: it forwards `type` and `unit`
only, drops any action id, and maps the old camelCase names (`FoundCity` → `found_city`,
`Sleep`/`Fortify`/`Explore`/`Automate`/`Pillage`/`SetUp`/`Disband` → `unit_action` without the
`action` parameter, which then fails `BAD_PARAMS`). In practice only `found_city` and the
snake_case tool names work. New clients should call `/intent`.

## Intent

```json
{
  "id": "6f1c…",            // optional, string: idempotency key
  "turn": 42,               // optional, int: the turn the caller believes it is
  "type": "move_unit",      // required, must be a tool name of kind "act" or "turn"
  "target": "unit-17",      // optional, "unit-<int>" or "city-<uuid>"
  "params": {"x": 12, "y": 30}
}
```

- `id` — the same id submitted twice applies once. The second call returns
  `{"ok":true,"intent":…,"duplicate":true,"events":[]}`. The store is an insertion-ordered set
  capped at 1000 entries; the oldest key is evicted first.
- `turn` — if present and not equal to the current turn, the request is rejected with `STALE`
  before anything runs. Omit it if the caller does not track turns.
- `type` — resolved against the tool table. A `read` tool is rejected with `NOT_AN_ACTION`.
- `target` — the primary object. Units and cities are looked up inside the acting
  civilization; someone else's unit yields `NOT_YOUR_UNIT` plus the caller's own ids.
- `params` — action-specific, see the [tool table](#tool-table).

There is no `actor` field. The acting civilization comes from the token, never from the body.

## Results and events

Success:

```json
{"ok":true,"intent":"6f1c…","turn":42,"events":[
  {"type":"unit_moved","unit":"unit-17","from":[10,28],"to":[12,30],"moves":1.0}
]}
```

`events` describes what actually happened, so a caller does not need a follow-up read.
Event types and their fields:

| Event | Fields |
|---|---|
| `unit_moved` | `unit`, `from:[x,y]`, `to:[x,y]`, `moves` |
| `city_founded` | `city`, `name`, `at:[x,y]` (fields may be absent if no city appeared) |
| `production_set` | `city`, `item` |
| `research_set` | `tech` |
| `attacked` | `unit`, `target:[x,y]`, `at:[x,y]`, `damageDealt`, `damageTaken` |
| `promoted` | `unit`, `promotion` |
| `unit_action` | `unit`, `action` |
| `city_focus_set` | `city`, `focus` |
| `purchased` | `city`, `item`, `goldLeft` |
| `war_declared` | `civ` |
| `peace_made` | `civ` |
| `policy_adopted` | `policy` |
| `turn_advanced` | `from`, `to`, `currentCiv` |

`turn_advanced` is emitted whenever `end_turn` was accepted, including when the host is still
waiting for other human civilizations and nothing moved. Compare `from` and `to` — see
[Turn semantics](#turn-semantics).

## Error codes

Failure envelope:

```json
{"ok":false,"error":{"code":"UNKNOWN_ID","message":"没有这个可建造项: Monu","candidates":["Monument"]}}
```

| Code | When | `candidates` |
|---|---|---|
| `BAD_JSON` | body is not parseable JSON | — |
| `BAD_INTENT` | no `type` | — |
| `UNKNOWN_ACTION` | `type` not in the tool table, not implemented, or an unknown `UnitActionType` | — |
| `NOT_AN_ACTION` | `type` is a `read` tool | — |
| `STALE` | `turn` does not match the current turn | — |
| `BAD_PARAMS` | a required param is missing, or `target` is not `unit-<int>` / `city-<uuid>` | — |
| `UNKNOWN_ID` | unknown item, tech, promotion, focus, policy, civ or city | substring matches, max 8 (city: `{id,name}` objects) |
| `NOT_YOUR_UNIT` | target unit is not in the acting civ | the caller's unit ids |
| `NOT_YOUR_CITY` | target city is not in the acting civ | the caller's city ids |
| `NOT_AVAILABLE` | the action is not legal right now (no promotion pending, cannot research, already at war, cannot afford, …) | — |
| `ILLEGAL_TARGET` | move is blocked, attack target out of reach, found-city illegal | attack: reachable `"x,y"` strings |
| `INTERNAL` | anything uncaught inside `apply` | — |

`NOT_YOUR_TURN` from the design draft is **not** implemented: the host does not reject an
action because it is another civilization's turn. It resolves the actor from the token and
acts for that civilization.

`UNKNOWN_ID` is the one to build retry logic around — it usually means a typo, and the
candidates are there to be retried.

## Tool table

22 tools. `/tools` and MCP `tools/list` are generated from the same map, so this table cannot
drift from the code.

Read (`kind: read`) — these are not intents; call the HTTP route or the MCP tool:

| Tool | Params | Route |
|---|---|---|
| `get_state` | — | `GET /state` |
| `get_units` | — | `GET /units` |
| `get_cities` | — | `GET /cities` |
| `get_city_details` | `city` | `GET /city/{id}` |
| `get_map` | `cx`, `cy`, `radius` | `GET /map` |
| `get_events` | `limit` | `GET /events` |
| `get_diplomacy` | — | `GET /diplomacy` |
| `get_policies` | — | `GET /policies` |

Act (`kind: act`):

| Tool | Params | Notes |
|---|---|---|
| `move_unit` | `unit`, `x`, `y` | one call, whatever movement is left; no path returned |
| `found_city` | `unit` | settler must be able to found here |
| `set_city_production` | `city`, `item` | name must be in the ruleset; reverts on failure |
| `set_research` | `tech` | replaces the research queue with one tech |
| `attack` | `unit`, `x`, `y` | melee/ranged; target must be reachable this turn |
| `bombard` | `unit`, `x`, `y` | ranged only, unit stays put |
| `promote_unit` | `unit`, `promotion` | must be in the unit's available promotions |
| `unit_action` | `unit`, `action` | a `UnitActionType` name: `Fortify`, `Sleep`, `Explore`, `Automate`, `Pillage`, `SetUp`, `Disband`, … |
| `set_city_focus` | `city`, `focus` | a `CityFocus` name |
| `purchase_construction` | `city`, `item` | gold purchase |
| `declare_war` | `civ` | must have met them and be allowed to declare |
| `make_peace` | `civ` | must currently be at war |
| `adopt_policy` | `policy` | must not be adopted already |

Turn (`kind: turn`):

| Tool | Params | Notes |
|---|---|---|
| `end_turn` | — | see [Turn semantics](#turn-semantics) |

Names are exact. `UNKNOWN_ID` returns up to 8 substring matches, so `Monu` suggests
`Monument`; nothing is fuzzy-matched automatically.

## MCP

`POST /mcp`, one JSON-RPC 2.0 request per call, no session state, no SSE.

| Method | Result |
|---|---|
| `initialize` | `protocolVersion: "2025-06-18"`, `capabilities.tools`, `serverInfo: {name:"unciv-agent",version:"0.1.0"}` |
| `notifications/initialized` | HTTP `202`, empty body |
| `ping` | `{}` |
| `tools/list` | `{tools:[{name,description,inputSchema}]}` — 22 entries |
| `tools/call` | `{content:[{type:"text",text:"<tool JSON as a string>"}],isError:bool}` |
| anything else | `-32601 Method not found` |

- Parse failure → `-32700` with `id: null`.
- `tools/call` without `name` → `-32602`.
- The tool's own JSON is serialized into `text` as a string, not embedded as an object.
- `isError` is a substring check on that JSON (`"ok":false` or `"error"`), so a tool result
  that merely mentions `error` in a message can be flagged. Treat `isError` as a hint and read
  the payload.
- `arguments` are flattened into an intent: `unit` / `city` / `civ` / `policy` becomes
  `target`, the rest becomes `params`. So MCP and HTTP produce identical results.

The handshake is not free-standing: actor resolution runs before the MCP branch, so with no
game loaded `initialize` returns a `500` error envelope. Load or create a game first.

## Official client file protocol

What the Unciv client needs for multiplayer save sync. No game state is involved; these
routes work even with nothing loaded.

- `GET /isalive` → `{"online":true}`. Used as a reachability probe.
- `GET|POST /auth` with `Authorization: Basic base64(user:pass)`. Any password is accepted;
  the user name is echoed back. Missing or non-Basic header → `401` with
  `WWW-Authenticate: Basic realm="unciv"`. Undecodable header → `400`.
- `GET /files/{name}` → file bytes as `text/plain; charset=utf-8`; `404` if absent.
- `PUT /files/{name}` → writes the body; `413` above 32 MiB.
- `DELETE /files/{name}` → `200` or `404`.
- Other methods → `405`.
- `{name}` must be non-empty and must not contain `/` or `..`; otherwise `400`. There are no
  subdirectories. The directory is `UNCIV_AGENT_FILES`, created at boot.

## Read projections

Field lists are exact — no extra fields, no nesting beyond what is shown.

`GET /state`

```json
{"turn":42,"civ":"Rome","alive":true,"defeated":false,"gold":310,
 "researching":"Pottery","techCount":7,
 "cities":[{"id":"city-3f2a…","name":"Rome","pop":4,"x":8,"y":-1,"production":"Monument"}],
 "units":[{"id":"unit-17","name":"Warrior","x":9,"y":0,"hp":100,"moves":2.0}]}
```

`GET /units` — `{"units":[{id,name,x,y,hp,moves,maxMoves,canAttack}]}`. `canAttack` is the
engine's own predicate; it does not mean a target is in range.

`GET /cities` — `{"cities":[{id,name,pop,x,y,production}]}`.

`GET /city/{id}` — accepts `city-<uuid>`, a raw id, or a name:

```json
{"city":"city-3f2a…","name":"Rome","pop":4,"at":[8,-1],"focus":"Default",
 "production":"Monument","queue":["Monument","Warrior"],"buildable":["Monument","Warrior",…]}
```

`buildable` is capped at 40 entries, filtered by `canAddToQueue`. Unknown id →
`{"ok":false,"error":{"code":"UNKNOWN_ID","message":…,"candidates":[{"id":…,"name":…}]}}`.
Note the candidates here are objects, unlike intent errors where they are strings.

`GET /map?cx&cy&radius` — `radius` defaults to 3 and is clamped to `0..12`; `cx`/`cy` default
to `0,0`. Only tiles explored by the acting civilization are returned:
`{"center":[cx,cy],"radius":r,"tiles":[{x,y,terrain,visible,resource?}]}`. `resource` is
omitted when the tile has none. `visible` is current sight, not just explored.

`GET /events?limit` — `limit` defaults to 20, clamped to `1..200`; the newest `N` are returned:
`{"total":N,"events":[{category,text}]}`. `category` is a `Notification.Category` name.

`GET /diplomacy` — `{"me":"Rome","known":[{civ,atWar,canDeclareWar}]}`. Only met
civilizations appear; the acting civilization is excluded.

`GET /policies` — `{"adopted":[…],"adoptableBranches":[…]}`.

`GET /actions?unit=N` — `{"unit":"unit-N","actions":[{type,title,available}],"truncated":null}`.
`type` is a `UnitActionType` name; `available` is whether invoking it would do something now.
`truncated` is the exception class name if enumeration threw part-way, otherwise `null` —
with the headless guards in `UnitActions` this should stay `null`.

`GET /admin/civs` — `{"turn":N,"civs":[{civ,human,alive,submitted,hasToken}]}`. `submitted`
means that civilization has ended its turn and the host is waiting for the rest.

`POST /admin/token` — `{"civ":"Rome"}` or `{"civ":"Rome","token":"fixed-value"}`. Without
`token` a random UUID is generated. Tokens live in memory only; a restart invalidates them.

`POST /new` — `players` (default 2), `humans` (default 1), `difficulty`, `speed`, `civ`,
`seed`. Small map, city-states = `players - 1`, first player is `Human`, the rest alternate
per `humans`. Returns the state projection. A non-zero `seed` makes the map reproducible.

`POST /load` / `POST /save` — absolute server-side paths, no upload. `load` resets the
submitted set; `save` writes the current `GameInfo`.

## Turn semantics

`end_turn` goes through `Kernel` into a host-supplied `advanceTurn` callback, which is where
the multi-civilization rule lives:

- One human civilization: the world advances one `nextTurn`, AI civilizations included.
- More than one human: the acting civilization is marked submitted. If any other human has
  not submitted, the call returns immediately with `turn_advanced` where `from == to` — the
  action was accepted, the world did not move. Once the last human submits, the host clears
  the set and calls `nextTurn` repeatedly until the turn counter changes and the current
  civilization is human again, with a hard cap of 64 iterations.

Clients should therefore treat "`turn_advanced` with `from == to`" as "waiting", and re-read
`/state` or `/admin/civs` rather than assuming the turn changed.

## Known gaps

- No trade proposals, religion, espionage, city-state diplomacy, manual tile assignment,
  nukes, or victory-progress reads.
- No per-civilization turn enforcement (`NOT_YOUR_TURN` is unimplemented).
- Unknown tokens fall back to the current player instead of returning `401`.
- Tokens and the idempotency set are in memory; a restart loses both.
- One game per process. `/load` replaces it; there is no way to hold two.
- `POST /save` writes whatever path the caller gives — there is no path sandbox. Run the host
  where that is acceptable.
