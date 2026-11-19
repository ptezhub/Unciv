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
  `POST /admin/token` and map to a civilization name. Before any token exists the agent routes
  run as the current player's civilization (single-player mode). Once at least one token exists
  every agent route requires a known token: missing or unknown → `401 UNAUTHORIZED`. `POST /new`
  clears all tokens, so tokens never carry over into the next game.
- Object ids in the agent surface: `unit-<int>` (engine `MapUnit.id`), `city-<uuid>` (engine
  `City.id`), and bare civilization names. `/city/{id}` additionally accepts a raw city id or
  a city name.

## HTTP routes

| Method | Path | Body / query | Response |
|---|---|---|---|
| GET | `/health` | — | `{"ok":true}` |
| GET | `/tools` | — | `{"tools":[{name,kind,description,schema}]}` |
| GET | `/guide` | `?name&category` | `{"entries":[{name,category,steps,text}]}` |
| GET | `/preview_attack` | `?unit&city&x&y` | damage estimate, no state change |
| GET | `/targets` | `?unit` | attackable tiles with damage ranges |
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
| GET | `/trade` | `?civ` | " |
| GET | `/religion` | — | " |
| GET | `/citystates` | — | " |
| GET | `/spies` | — | " |
| GET | `/techs` | — | " |
| GET | `/victory` | — | " |
| GET | `/briefing` | — | " |
| GET | `/actions` | `?unit=<int>` | `{unit,actions:[{type,title,available}],truncated}` |
| POST | `/act` | `{type, unit?}` | legacy shim, see below |
| POST | `/move` | `{unit,x,y}` | legacy shim |
| POST | `/endturn` | — | same as `{"type":"end_turn"}` intent |
| POST | `/new` | `{players,humans,difficulty,speed,civ,seed,era,size}` | state of the new game |
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
  Civilization actions (`declare_war`, `make_peace`, `propose_trade`, `respond_trade`,
  `gift_city_state`, `pledge_protect`) accept the civilization name in `target` as well as in
  `params.civ`.
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
| `trade_proposed` | `civ`, `give:[offer]`, `want:[offer]` |
| `trade_accepted` / `trade_declined` | `civ`, `give:[offer]`, `want:[offer]` |
| `beliefs_chosen` | `state`, `religion`, `beliefs:[name]` |
| `city_state_gift` | `civ`, `gold`, `influenceGained`, `influence` |
| `pledged_protection` / `withdrew_protection` | `civ` |
| `worked_tiles_set` | `city`, `worked:[[x,y],…]` |
| `nuke` | `unit`, `at:[x,y]` |
| `unit_upgraded` | `unit`, `from`, `to`, `goldCost` |
| `spy_moved` | `spy`, `to` |
| `specialists_set` | `city`, `specialists:{name:count}` |
| `city_annexed` | `city`, `name` |
| `city_captured` | `city`, `name`, `from`, `action`, `isPuppet` |
| `city_bombarded` | `city`, `at`, `damageDealt`, `damageTaken` |
| `turn_advanced` | `from`, `to`, `currentCiv`, `warnings:[string]` |

`turn_advanced.warnings` is advisory and only covers things an agent commonly forgets: units
still holding movement, cities producing nothing, captures still pending. It never blocks the
turn.

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
| `ID_REUSED` | the same `id` arrived with a different action; nothing was applied | — |
| `NOT_AN_ACTION` | `type` is a `read` tool | — |
| `STALE` | `turn` does not match the current turn | — |
| `BAD_PARAMS` | a required param is missing, or `target` is not `unit-<int>` / `city-<uuid>` | — |
| `UNKNOWN_ID` | unknown item, tech, promotion, focus, policy, civ or city | substring matches, max 8 (city: `{id,name}` objects) |
| `NOT_YOUR_UNIT` | target unit is not in the acting civ | the caller's unit ids |
| `NOT_YOUR_CITY` | target city is not in the acting civ | the caller's city ids |
| `NOT_AVAILABLE` | the action is not legal right now (no promotion pending, cannot research, already at war, cannot afford, …) | — |
| `ILLEGAL_TARGET` | move is blocked, attack target out of reach, found-city illegal | attack: reachable `"x,y"` strings |
| `NOT_AVAILABLE` (attack) | target is a foreign city/unit and you are not at war — the message says to `declare_war` first | reachable tiles |
| `INTERNAL` | anything uncaught inside `apply` | — |

`NOT_YOUR_TURN` from the design draft is **not** implemented: the host does not reject an
action because it is another civilization's turn. It resolves the actor from the token and
acts for that civilization.

`UNKNOWN_ID` is the one to build retry logic around — it usually means a typo, and the
candidates are there to be retried.

## Tool table

48 tools. `/tools` and MCP `tools/list` are generated from the same map, so this table cannot
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
| `get_victory_progress` | — | MCP only |
| `get_tile` | `x`, `y` | `GET /tile?x&y` |
| `get_trade_offers` | `civ` (optional) | `GET /trade?civ` |
| `get_techs` | — | `GET /techs` |
| `get_briefing` | — | `GET /briefing` |
| `get_guide` | `name?`, `category?` | `GET /guide` |
| `preview_attack` | `unit` or `city`, `x`, `y` | `GET /preview_attack` |
| `get_targets` | `unit` | `GET /targets` |
| `get_religion` | — | `GET /religion` |
| `get_city_states` | — | `GET /citystates` |
| `get_spies` | — | `GET /spies` |

Act (`kind: act`):

| Tool | Params | Notes |
|---|---|---|
| `move_unit` | `unit`, `x`, `y` | moves toward the tile: exactly if it is reachable this turn, otherwise as far as the path allows. The event carries `arrived`, `tilesLeft` and `blockedBy` (set when the destination is occupied, which is why a move can stop short and still report ok). A tile the unit can never reach is `ILLEGAL_TARGET`; no movement left is `NOT_AVAILABLE` |
| `found_city` | `unit` | settler must be able to found here |
| `build_improvement` | `improvement` | worker starts an improvement on its own tile; it must stay there, one turn of progress per turn |
| `disband_unit` | — | removes the unit |
| `set_city_production` | `city`, `item`, `count`, `mode` | `mode` defaults to `replace` (clears the queue first); `append` keeps what is already queued. The event echoes the engine's real queue, so `queued` is never a guess |
| `set_research` | `tech`, `techs?` | `techs` queues several in order, so research does not idle for a turn when one completes |
| `attack` | `unit`, `x`, `y` | melee/ranged; target must be reachable this turn |
| `bombard` | `unit`, `x`, `y` | ranged only, unit stays put |
| `promote_unit` | `unit`, `promotion` | must be in the unit's available promotions |
| `unit_action` | `unit`, `action`, `id` | a `UnitActionType` name: `Fortify`, `Sleep`, `Explore`, `Automate`, `Pillage`, …; `id` selects the concrete target (unique). `CreateImprovement` is not here — the engine keeps it in the UI layer, use `build_improvement`. `Automate` does not just set a flag: the host runs one automation pass immediately and returns where the unit ended up and what it started, because nothing would drive the flag in a headless game |
| `set_city_focus` | `city`, `focus` | a `CityFocus` name |
| `purchase_construction` | `city`, `item`, `currency?` | `Gold` (default) or `Faith`. Price and eligibility come from the engine (`getStatBuyCost` + `isConstructionPurchaseAllowed`), so an unaffordable buy is rejected instead of driving gold negative |
| `declare_war` | `civ` | must have met them and be allowed to declare |
| `make_peace` | `civ` | must currently be at war |
| `adopt_policy` | `policy` | must not be adopted already |
| `propose_trade` | `civ`, `give[]`, `want[]` | each entry `{name,type?,amount?}`; only offers the engine itself lists for that pair are accepted, everything else comes back as `NOT_AVAILABLE` with the offending names. Replaces any offer already pending to that civ |
| `respond_trade` | `civ`, `accept?`, `index?` | answers a request that civ sent us; `accept` defaults to `true`, `index` picks among several (`0` is the oldest) |
| `choose_beliefs` | `beliefs[]`, `unit?`, `name?`, `displayName?` | pantheon / founding / enhancing in one call. With `unit` (a Great Prophet) the prophet is spent first. The count and types must match `mustChoose` from `get_religion` exactly |
| `gift_city_state` | `civ`, `gold` | gold gift for influence; `gold` defaults to 250 |
| `pledge_protect` | `civ`, `protect?` | `protect:false` withdraws; the engine blocks withdrawal for 10 turns after pledging |
| `set_worked_tiles` | `city`, `tiles[[x,y]]`, `lock?`, `autoFill?` | replaces the worked set; tiles must be workable by that city and the count may not exceed population. `lock` (default true) pins them, `autoFill` (default true) spends leftover population on the engine's own ranking |
| `bombard_city` | `city`, `x`, `y` | the city's own ranged attack; free, once per turn, needs a target inside its bombard range |
| `nuke` | `unit`, `x`, `y` | unit must be a nuclear weapon; target must pass `Nuke.mayUseNuke` (range, war status) |
| `upgrade_unit` | `unit`, `to?` | `to` is required when several upgrades are listed |
| `spy_move` | `spy`, `city?` | `spy` is a name or an index; `city` omitted or `hideout` recalls the spy |
| `set_city_specialists` | `city`, `specialists:{name:count}` | switches the city to manual specialists; names omitted from the object are cleared, `{}` clears all. Free population is rebalanced immediately |
| `annex_city` | `city` | turns one of your puppets into a directly controlled city |
| `capture_city` | `city`, `action?` | resolves a city you just took: `puppet` (default), `annex`, `raze`, `liberate`. Only works while the capture is still pending — see below |

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
| `tools/list` | `{tools:[{name,description,inputSchema}]}` — 48 entries |
| `tools/call` | `{content:[{type:"text",text:"<tool JSON as a string>"}],isError:bool}` |
| anything else | `-32601 Method not found` |

- Parse failure → `-32700` with `id: null`.
- `tools/call` without `name` → `-32602`.
- The tool's own JSON is serialized into `text` as a string, not embedded as an object.
- `isError` is a substring check on that JSON (`"ok":false` or `"error"`), so a tool result
  that merely mentions `error` in a message can be flagged. Treat `isError` as a hint and read
  the payload.
- `arguments` are flattened into an intent: `unit` / `city` / `civ` / `policy` becomes
  `target`, the rest becomes `params` as real JSON, so nested arrays and objects
  (`give`, `want`, `beliefs`, `tiles`) survive the trip unchanged. MCP and HTTP produce
  identical results.

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
 "units":[{"id":"unit-17","name":"Warrior","x":9,"y":0,"hp":100,"moves":2.0}],
 "stats":{"goldPerTurn":9.0,"sciencePerTurn":14.0,"culturePerTurn":3.0,"faithPerTurn":2.0,
          "productionPerTurn":0.0,"foodPerTurn":0.0,"happiness":4,"unitSupply":7},
 "pendingCaptures":[],"seed":777,"gameId":"1b2c…",
 "difficulty":"Prince","speed":"Quick"}
```

`pendingCaptures` lists cities you have taken but not yet resolved with `capture_city`;
each entry is `{city,name}`. `stats` are the projected per-turn yields for the coming turn —
they read as zero before the first `end_turn`. `seed` is the map seed and `gameId` is the
engine's per-game id (starting unit placement derives from it, so only the map and the
civilization are reproducible from `seed`).

`GET /units` — `{"units":[{id,name,x,y,hp,moves,maxMoves,canAttack,military,civilian,embarked,range,xp,canPromote,promotions:[…],canUpgradeTo:[…]}]}`.
`canAttack` is the engine's own predicate; it does not mean a target is in range.
`canUpgradeTo` lists only upgrades whose requirements are met right now, i.e. exactly what
`upgrade_unit` would accept. `availablePromotions` is filled only when `canPromote` is true.

`GET /cities` — `{"cities":[{id,name,pop,x,y,production}]}`.

`GET /city/{id}` — accepts `city-<uuid>`, a raw id, or a name:

```json
{"city":"city-3f2a…","name":"Rome","pop":4,"at":[8,-1],"focus":"Default",
 "production":"Monument","queue":["Monument","Warrior"],
 "workedTiles":[[7,-1],[9,-1]],
 "stats":{"food":6.0,"production":8.0,"gold":4.0,"science":7.0,"culture":2.0,"faith":0.0,"happiness":0.0},
 "health":200,"maxHealth":200,"isPuppet":false,"isCapital":true,"connected":true,
 "avoidGrowth":false,"inResistance":false,
 "buildings":["Palace","Monument","Library"],
 "specialists":{"Scientist":1},"maxSpecialists":{"Scientist":2},"manualSpecialists":true,
 "turnsToGrowth":7,"foodToNextPop":24,"turnsToBuild":5,"remainingWork":64,
 "defense":52,"garrison":"Rifleman","canBombard":true,
 "purchasable":[{"item":"Worker","gold":220},{"item":"Temple","gold":300,"faith":200}],
 "religion":{"majority":"Buddhism","holyCityOf":"Buddhism"},
 "buildable":["Monument","Warrior",…]}
```

`buildable` is capped at 40 entries, filtered by `canAddToQueue`. `turnsToBuild` is the
remaining turns for the current item (`null` when nothing is being built or production is
zero). `purchasable` lists up to 20 items you can buy *right now*, with the price in each
currency that would work — so a purchase attempt never has to be trial and error. Unknown id →
`{"ok":false,"error":{"code":"UNKNOWN_ID","message":…,"candidates":[{"id":…,"name":…}]}}`.
Note the candidates here are objects, unlike intent errors where they are strings.

`GET /map?cx&cy&radius` — `radius` defaults to 3 and is clamped to `0..12`; `cx`/`cy` default
to `0,0`. Only tiles explored by the acting civilization are returned:
`{"center":[cx,cy],"radius":r,"tiles":[{x,y,terrain,visible,resource?,owner?,city?,units?}]}`.
`units` lists foreign units standing on a tile you can see — `{id,owner,name,hp,military}` —
which is how you notice an incoming army before it reaches a city.
`resource` is omitted when the tile has none; `owner` is the civilization owning the tile;
`city` appears **only on a city centre tile** — the engine's own `Tile.getCity()` returns the
owning city for every tile in its territory, which is not what an attacker wants.
`visible` is current sight, not just explored.

`GET /events?limit` — `limit` defaults to 20, clamped to `1..200`; the newest `N` are returned:
`{"total":N,"events":[{category,text}]}`. `category` is a `Notification.Category` name.

`GET /diplomacy` — `{"me":"Rome","known":[{civ,isCityState,atWar,canDeclareWar}]}`. Only met
civilizations appear; the acting civilization is excluded.

`GET /policies` — `{"adopted":[…],"culture":N,"cultureNeededForNextPolicy":M,"canAdoptPolicy":bool,"adoptableBranches":[…],"tree":[{branch,policies:[{name,adopted,requires,ready}]}]}`.
`tree` covers every branch in the ruleset, so sub-policies are visible before the branch is
opened; `ready` means prerequisites are met (culture is a separate question).

`GET /targets?unit=` — every tile this unit can hit this turn, each with the same damage ranges
as `preview_attack`. Use it instead of computing hex geometry: `{"unit","name","range",
"targets":[{x,y,type,damageToDefender,damageToAttacker}]}`.

Hex geometry, for the record: distance is `max(|dx|,|dy|)` when dx and dy have the same sign,
and `|dx|+|dy|` when they have opposite signs. Neighbours of (x,y) are (x±1,y), (x,y±1),
(x+1,y-1) and (x-1,y+1).

`GET /preview_attack` — what the client shows when you hover an enemy: strengths, the damage
range in both directions, whether either side dies, and whether the target is in range.

```json
{"target":[8,8],"targetType":"unit","distance":1,"range":1,"inRange":true,
 "attackerStrength":14,"defenderStrength":10,
 "damageToDefender":{"min":28,"max":42},"damageToAttacker":{"min":16,"max":24},
 "defenderHp":51,"attackerHp":85,"wouldKillDefender":false,"wouldKillAttacker":false}
```

`min`/`max` come from the engine's own damage formula at randomness 0 and 1, so a real hit lands
inside the range. Pass `unit` for a unit attack or `city` for the city's own bombardment. It
never moves, spends movement or changes the turn — the conformance suite asserts that.

`GET /guide` — the game's own tutorial and civilopedia articles, loaded from
`jsons/Tutorials.json` at boot. Works without a loaded game:
`{"entries":[{name,category,steps:[…],text}]}`. `steps` are the short in-game prompts, `text`
is the full article. Filter with `?name=` or `?category=` (case-insensitive substring). This is
the strategy material a model should get once, not every turn.

`GET /briefing` — the whole turn in one read, meant to be handed to a model as the turn
prompt. It aggregates what the client would show:

```json
{"turn":42,"civ":"Rome","era":"Classical era","difficulty":"Prince","speed":"Quick",
 "gold":310,"goldPerTurn":9.0,"sciencePerTurn":14.0,
 "culture":88,"culturePerTurn":3.0,"cultureForNextPolicy":45,
 "faith":12,"faithPerTurn":2.0,"happiness":4,
 "research":{"current":"Pottery","turnsLeft":3,"available":4},
 "cities":[{"id","name","pop","at","foodPerTurn","turnsToGrowth","production","turnsToBuild",
            "isPuppet","inResistance","health","canBombard","enemyAdjacent"}],
 "unitsNeedingOrders":[{"id","name","x","y","moves","military","civilian","canAttack","canPromote",
                        "canBuild":["Farm","Mine",…],"workingOn":"Farm","turnsLeft":3,
                        "attackable":[{x,y,type}]}],
 "diplomacy":{"known":[{civ,isCityState,atWar,canDeclareWar,influence}]},
 "cityStates":[{civ,influence,ally}],
 "religion":{"state","faith","hasFreeBeliefs"},
 "pendingCaptures":["city-…"],
 "events":[{category,text}],
 "warnings":["1 座城没有在生产任何东西","没有在研究的科技"]}
```

`unitsNeedingOrders` is only the units that still have movement, so an empty list means the
turn is ready to end. A worker also carries `canBuild` (what it can start on its own tile),
`workingOn` and `turnsLeft`; a unit that can attack carries `attackable`, the tiles it can hit
this turn, so nothing has to compute hex geometry. Both are capped, so use `get_targets` or
`build_improvement`'s error for the full picture. `enemyAdjacent` is true when a foreign military unit stands next to a
city. `warnings` is the same list `end_turn` returns, so a model that reads the briefing can
act before it loses a turn.

`GET /techs` — `{"researching","sciencePerTurn","available":[{name,cost,remaining,turns}]}`.
Only techs whose prerequisites are satisfied are listed. `remaining` accounts for science
already invested, and `turns` is computed from it (`null` while science output is zero).

`GET /tile?x&y` — the tile plus `yields:{food,production,gold,science,culture,faith}` for the
acting civilization, so worked-tile changes can be checked against real numbers.

`GET /trade?civ` — `{"pending":[{civ,proposedByMe,give:[offer],want:[offer]}]}`; with `civ` also
`"with"`, `"youCanGive"`, `"theyCanGive"`. `give` is what the other side hands over, `want`
is what they ask for. An offer is `{name,type,amount,duration}`; `duration` is `-1` for
one-shot items. Gold entries carry the whole treasury as `amount` — clamp it yourself.

`GET /religion` — `{"enabled":false}` when the ruleset or starting era disables religion.
Otherwise `{"enabled":true,"state":"None|Pantheon|FoundingReligion|Religion|EnhancingReligion|EnhancedReligion","faith","faithForPantheon","religion","beliefs":[{name,type}],"availableBeliefs":[{name,type}],"mustChoose":{type:count}}`.
`mustChoose` is what the next `choose_beliefs` call has to supply.

`GET /citystates` — `{"cityStates":[{civ,influence,ally,atWar,canPledgeProtection,giftInfluence:[{gold,influence}],quests:[{name,description,influence,turnsLeft}]}]}`.
Only city-states the acting civilization has met appear. `influence` is read from the
city-state's side of the relationship, which is where the engine keeps it. `turnsLeft` is
`-1` for quests without a deadline.

`GET /spies` — `{"spies":[{name,rank,action,turnsRemaining,city,cityId,setUp}]}`. `city` is
`null` in the hideout. `action` is a `SpyAction` name.

`GET /actions?unit=N` — `{"unit":"unit-N","actions":[{type,title,available}],"truncated":null}`.
`type` is a `UnitActionType` name; `available` is whether invoking it would do something now.
`truncated` is the exception class name if enumeration threw part-way, otherwise `null` —
with the headless guards in `UnitActions` this should stay `null`.

`GET /admin/civs` — `{"turn":N,"civs":[{civ,human,alive,submitted,hasToken}]}`. `submitted`
means that civilization has ended its turn and the host is waiting for the rest.

`POST /admin/token` — `{"civ":"Rome"}` or `{"civ":"Rome","token":"fixed-value"}`. Without
`token` a random UUID is generated. Tokens live in memory only; a restart invalidates them.

`POST /new` — `players` (default 2), `humans` (default 1), `difficulty`, `speed`, `civ`,
`seed`, `era`, `size`. Map size defaults to `Small` and accepts any `MapSize.Predefined`
name (`Duel`, `Tiny`, `Small`, `Medium`, `Large`, `Huge`). `era` accepts a ruleset era name
(`Ancient era` … `Future era`); it changes the starting techs and units, and eras that
disable religion make `get_religion` return `{"enabled":false}`. City-states =
`players - 1`, first player is `Human`, the rest alternate per `humans`. Returns the state
projection. A non-zero `seed` makes the map reproducible.

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

- City capture is deferred: a headless human civilization queues the decision instead of
  letting `automateCityConquer` decide, so `get_state.pendingCaptures` lists the city and
  `capture_city` picks annex/puppet/raze/liberate. If the agent does not resolve it before
  `end_turn`, the host falls back to the engine default (puppet) so a city can never be left
  at zero health with its old owner.
- Espionage exposes movement and status only: there is no way to start a coup, rig an
  election, or pick a specific tech to steal — the engine chains those automatically after a
  spy establishes a network.
- No unit gifting, no city trading UI helpers, no "what would this civ accept" evaluation
  endpoint. `propose_trade` validates against the engine's offer list but does not predict
  whether the AI will say yes.
- No per-civilization turn enforcement (`NOT_YOUR_TURN` is unimplemented).
- Tokens and the idempotency set are in memory; a restart loses both.
- One game per process. `/load` replaces it; there is no way to hold two.
- `POST /save` writes whatever path the caller gives — there is no path sandbox. Run the host
  where that is acceptable.
