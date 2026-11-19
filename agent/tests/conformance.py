#!/usr/bin/env python3
"""Unciv agent host conformance suite.

Starts its own host process, runs a battery of checks against the HTTP surface,
prints a scorecard and exits non-zero when a P0/P1 check fails.

    python3 conformance.py [--port 8200] [--keep]

Checks are grouped by what a benchmark actually cares about:
  A contract      the documented surface exists and is self-consistent
  B auth          identity cannot be bypassed or leaked
  C validation    bad input is a documented error, never a 5xx
  D state         idempotency, turn advance, save/load
  E effects       every action's effect is visible in a read
  F consistency   two endpoints never disagree about the same fact
  G robustness    odd parameters do not crash the host
  H determinism   same seed, same start
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

JAVA = "/home/node/.openclaw/cache/jdk/jdk-21.0.5+11/bin/java"
CP = ":".join([
    "/home/node/.openclaw/src/unciv/core/build/libs/core-5.0.1.jar",
    "/home/node/.openclaw/cache/unciv/Unciv.jar",
    "/home/node/.openclaw/src/unciv/agent-classes",
])
ASSETS = "/home/node/.openclaw/src/unciv/android/assets"

RESULTS = []


def check(group, cid, severity, fn):
    try:
        ok, detail = fn()
    except AssertionError as e:
        ok, detail = False, str(e)
    except Exception as e:  # noqa: BLE001 - a crash is a failed check, not a suite crash
        ok, detail = False, f"{type(e).__name__}: {e}"
    RESULTS.append((group, cid, severity, bool(ok), detail))
    mark = "ok  " if ok else "FAIL"
    print(f"  [{mark}] {cid}: {detail}")
    return ok


class Host:
    def __init__(self, port):
        self.port = port
        self.base = f"http://127.0.0.1:{port}"
        self.proc = None
        self.files = tempfile.mkdtemp(prefix="unciv-conf-")

    def start(self):
        env = dict(os.environ, UNCIV_AGENT_PORT=str(self.port), UNCIV_AGENT_FILES=self.files)
        self.proc = subprocess.Popen(
            [JAVA, "-Xmx2g", "-cp", CP, "uncivagent.Agent"],
            cwd=ASSETS, env=env,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        for _ in range(120):
            try:
                if self.get("/health")[0] == 200:
                    return
            except Exception:  # noqa: BLE001
                pass
            time.sleep(0.25)
        raise RuntimeError("host did not start")

    def stop(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.proc.kill()

    def req(self, method, path, body=None, headers=None):
        data = None if body is None else (body if isinstance(body, bytes) else json.dumps(body).encode())
        req = urllib.request.Request(self.base + path, data=data, method=method)
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                raw = r.read()
                return r.status, raw
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def get(self, path, headers=None):
        status, raw = self.req("GET", path, headers=headers)
        try:
            return status, json.loads(raw)
        except Exception:  # noqa: BLE001
            return status, raw

    def post(self, path, body, headers=None):
        status, raw = self.req("POST", path, body, headers)
        try:
            return status, json.loads(raw)
        except Exception:  # noqa: BLE001
            return status, raw

    def intent(self, type_, target=None, params=None, headers=None, iid=None, turn=None):
        body = {"type": type_, "params": params or {}}
        if target is not None:
            body["target"] = target
        if iid is not None:
            body["id"] = iid
        if turn is not None:
            body["turn"] = turn
        return self.post("/intent", body, headers=headers)

    def new_game(self, **kw):
        body = {"players": 2, "humans": 1, "seed": 4242, "size": "Duel"}
        body.update(kw)
        return self.post("/new", body)

    def city(self):
        _, d = self.get("/cities")
        if not d["cities"]:
            raise AssertionError("no city yet")
        return d["cities"][0]["id"]

    def ensure_city(self):
        """Found the starting settler if no city exists yet; returns the city id."""
        _, d = self.get("/cities")
        if d["cities"]:
            return d["cities"][0]["id"]
        _, units = self.get("/units")
        settler = next((u for u in units["units"] if u["name"] == "Settler"), None)
        assert settler, "no settler to found a city with"
        _, r = self.intent("found_city", settler["id"])
        assert r.get("ok"), r
        return self.city()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8200)
    ap.add_argument("--keep", action="store_true")
    args = ap.parse_args()

    host = Host(args.port)
    print(f"starting host on :{args.port} (files {host.files})")
    host.start()
    score = {"P0": [0, 0], "P1": [0, 0], "P2": [0, 0]}
    try:
        run_all(host)
    finally:
        if not args.keep:
            host.stop()
        for group, cid, sev, ok, _ in RESULTS:
            score[sev][0] += 1
            if ok:
                score[sev][1] += 1
        print("\nscorecard")
        total_w = 0.0
        total_ok = 0.0
        weight = {"P0": 5.0, "P1": 2.0, "P2": 1.0}
        for sev in ("P0", "P1", "P2"):
            n, k = score[sev]
            total_w += n * weight[sev]
            total_ok += k * weight[sev]
            print(f"  {sev}: {k}/{n} passed (weight {weight[sev]})")
        pct = 100.0 if total_w == 0 else 100.0 * total_ok / total_w
        print(f"  score: {pct:.1f}/100")
        failures = [r for r in RESULTS if not r[3]]
        if failures:
            print("\nfailures")
            for group, cid, sev, _, detail in failures:
                print(f"  {sev} {group} {cid}: {detail}")
        sys.exit(0 if pct >= 99.9 else 1)


def run_all(h):
    print("\nA contract")
    check("A", "A1 health", "P0", lambda: (h.get("/health")[0] == 200, "GET /health"))
    check("A", "A2 tools shape", "P0", lambda: tools_shape(h))
    check("A", "A3 mcp parity", "P0", lambda: mcp_parity(h))
    check("A", "A4 mcp initialize", "P1", lambda: mcp_init(h))

    print("\nB auth")
    check("B", "B1 open before tokens", "P0", lambda: open_before_tokens(h))
    check("B", "B2 token required", "P0", lambda: token_required(h))
    check("B", "B3 unknown token", "P0", lambda: unknown_token(h))
    check("B", "B4 unknown civ", "P1", lambda: unknown_civ(h))
    check("B", "B5 new clears tokens", "P0", lambda: new_clears_tokens(h))

    print("\nC validation")
    check("C", "C1 bad json", "P0", lambda: bad_json(h))
    check("C", "C2 unknown action", "P1", lambda: unknown_action(h))
    check("C", "C3 read as intent", "P1", lambda: read_as_intent(h))
    check("C", "C4 stale turn", "P1", lambda: stale_turn(h))
    check("C", "C5 unknown unit", "P1", lambda: unknown_unit(h))
    check("C", "C6 unknown city", "P1", lambda: unknown_city(h))
    check("C", "C7 out of map tile", "P1", lambda: out_of_map(h))

    print("\nD state")
    check("D", "D1 idempotency", "P0", lambda: idempotency(h))
    check("D", "D2 turn advance", "P0", lambda: turn_advance(h))
    check("D", "D3 save/load", "P0", lambda: save_load(h))

    print("\nE effects")
    check("E", "E1 found city", "P0", lambda: found_city(h))
    check("E", "E2 production", "P0", lambda: production(h))
    check("E", "E3 research", "P0", lambda: research(h))
    check("E", "E4 move", "P0", lambda: move(h))
    check("E", "E5 worked tiles", "P0", lambda: worked_tiles(h))
    check("E", "E6 purchase", "P0", lambda: purchase(h))
    check("E", "E7 specialists", "P1", lambda: specialists(h))
    check("E", "E8 trade", "P1", lambda: trade(h))
    check("E", "E9 war", "P0", lambda: war(h))
    check("E", "E10 capture guard", "P1", lambda: capture_guard(h))

    print("\nF consistency")
    check("F", "F1 production agree", "P1", lambda: production_agree(h))
    check("F", "F2 unit ids agree", "P1", lambda: unit_ids_agree(h))
    check("F", "F3 city counts agree", "P1", lambda: city_counts_agree(h))
    check("F", "F4 trade gold bound", "P2", lambda: trade_gold_bound(h))

    print("\nG robustness")
    check("G", "G1 odd params", "P0", lambda: odd_params(h))

    print("\nH determinism")
    check("H", "H1 same seed", "P1", lambda: same_seed(h))

    print("\nJ mcp arguments")
    check("J", "J1 array args survive", "P0", lambda: mcp_arrays(h))
    check("J", "J2 mcp act matches http", "P1", lambda: mcp_act_parity(h))
    check("J", "J3 briefing complete", "P0", lambda: briefing_shape(h))
    check("J", "J4 guide available", "P1", lambda: guide_shape(h))
    check("J", "J5 attack preview is read-only", "P0", lambda: preview_readonly(h))
    check("J", "J6 worker tools refuse cleanly", "P1", lambda: worker_tools(h))

    print("\nI failure cost")
    check("I", "I1 failed action is free", "P0", lambda: failed_action_free(h))
    check("I", "I2 id reuse rejected", "P0", lambda: id_reuse(h))
    check("I", "I3 no-move attack", "P1", lambda: no_move_attack(h))
    check("I", "I4 intent fuzz", "P1", lambda: intent_fuzz(h))
    check("I", "I5 latency", "P2", lambda: latency(h))


# ---------------- A ----------------

def tools_shape(h):
    status, d = h.get("/tools")
    assert status == 200, f"status {status}"
    tools = d["tools"]
    names = [t["name"] for t in tools]
    assert len(names) == len(set(names)), "duplicate tool names"
    for t in tools:
        for field in ("name", "kind", "description", "schema"):
            assert field in t, f"{t.get('name')} missing {field}"
        assert t["kind"] in ("read", "act", "turn"), f"{t['name']} bad kind"
        if isinstance(t["schema"], str):
            json.loads(t["schema"])
    return True, f"{len(tools)} tools, schemas parse"


def mcp_parity(h):
    _, http = h.get("/tools")
    _, mcp = h.post("/mcp", {"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
    a = sorted(t["name"] for t in http["tools"])
    b = sorted(t["name"] for t in mcp["result"]["tools"])
    assert a == b, f"http {len(a)} vs mcp {len(b)}: {set(a) ^ set(b)}"
    return True, f"{len(a)} names identical on both surfaces"


def mcp_init(h):
    _, d = h.post("/mcp", {"jsonrpc": "2.0", "id": 1, "method": "initialize"})
    assert "result" in d, d
    assert d["result"]["protocolVersion"], "no protocolVersion"
    return True, d["result"]["protocolVersion"]


# ---------------- B ----------------

def open_before_tokens(h):
    h.new_game()
    status, d = h.get("/state")
    assert status == 200 and d.get("civ"), f"{status} {d}"
    return True, "single-player fallback works"


def token_required(h):
    _, d = h.post("/admin/token", {"civ": h.get("/state")[1]["civ"]})
    status, _ = h.get("/state")
    assert status == 401, f"expected 401 without token, got {status}"
    return True, "token enforced"


def unknown_token(h):
    status, d = h.get("/state", headers={"Authorization": "Bearer nope"})
    assert status == 401, f"expected 401, got {status} {d}"
    return True, "unknown token rejected"


def unknown_civ(h):
    status, d = h.post("/admin/token", {"civ": "Atlantis"})
    assert status == 404, f"expected 404, got {status}"
    assert d["error"]["code"] == "UNKNOWN_ID" and d["error"]["candidates"], d
    return True, "404 with candidates"


def new_clears_tokens(h):
    h.new_game(seed=999)
    status, _ = h.get("/state")
    assert status == 200, f"old token still enforced after /new ({status})"
    return True, "tokens cleared on /new"


# ---------------- C ----------------

def bad_json(h):
    status, d = h.post("/intent", b"{not json")
    assert status == 200, f"status {status}"
    assert d["error"]["code"] == "BAD_JSON", d
    return True, "BAD_JSON"


def unknown_action(h):
    _, d = h.intent("no_such_tool")
    assert d["error"]["code"] == "UNKNOWN_ACTION", d
    return True, "UNKNOWN_ACTION"


def read_as_intent(h):
    _, d = h.intent("get_state")
    assert d["error"]["code"] == "NOT_AN_ACTION", d
    return True, "NOT_AN_ACTION"


def stale_turn(h):
    _, d = h.intent("end_turn", turn=999)
    assert d["error"]["code"] == "STALE", d
    return True, "STALE"


def unknown_unit(h):
    _, d = h.intent("move_unit", "unit-99999", {"x": 0, "y": 0})
    assert d["error"]["code"] == "NOT_YOUR_UNIT", d
    return True, "NOT_YOUR_UNIT"


def unknown_city(h):
    _, d = h.intent("set_city_production", "city-nope", {"item": "Monument"})
    assert d["error"]["code"] == "NOT_YOUR_CITY", d
    return True, "NOT_YOUR_CITY"


def out_of_map(h):
    status, d = h.get("/tile?x=9999&y=9999")
    assert status == 200, f"status {status}"
    assert d.get("error", {}).get("code") == "UNKNOWN_ID", d
    status, d = h.get("/map?cx=0&cy=0&radius=99")
    assert status == 200, f"/map radius 99 -> {status}"
    return True, "no crash outside the map"


# ---------------- D ----------------

def idempotency(h):
    h.new_game(seed=1001)
    _, units = h.get("/units")
    settler = next(u for u in units["units"] if u["name"] == "Settler")
    body = {"type": "found_city", "target": settler["id"], "params": {}, "id": "conf-1"}
    _, first = h.post("/intent", body)
    _, second = h.post("/intent", body)
    assert first.get("ok"), first
    assert second.get("duplicate"), second
    _, cities = h.get("/cities")
    assert len(cities["cities"]) == 1, f"idempotency leaked: {len(cities['cities'])} cities"
    return True, "second call is a no-op"


def turn_advance(h):
    before = h.get("/state")[1]["turn"]
    _, d = h.intent("end_turn")
    after = h.get("/state")[1]["turn"]
    assert after > before, f"turn stuck at {before}"
    assert d["events"][0]["type"] == "turn_advanced", d
    return True, f"{before} -> {after}"


def save_load(h):
    path = os.path.join(h.files, "conf-save.json")
    _, before = h.get("/state")
    assert h.post("/save", {"path": path})[0] == 200
    _, d = h.post("/load", {"path": path})
    assert d.get("turn") == before["turn"], d
    assert [c["name"] for c in d["cities"]] == [c["name"] for c in before["cities"]]
    status, d = h.post("/load", {"path": "/nope/none.json"})
    assert status == 404 and d["error"]["code"] == "NOT_FOUND", f"{status} {d}"
    return True, "roundtrip + 404 on missing file"


# ---------------- E ----------------

def found_city(h):
    _, cities = h.get("/cities")
    assert len(cities["cities"]) == 1
    city = cities["cities"][0]
    _, detail = h.get("/city/" + city["id"])
    assert detail["name"] == city["name"], detail
    return True, city["name"]


def production(h):
    cid = h.city()
    _, d = h.intent("set_city_production", cid, {"item": "Monument"})
    assert d.get("ok"), d
    _, detail = h.get("/city/" + cid)
    assert detail["production"] == "Monument", detail["production"]
    before = detail["production"]
    _, bad = h.intent("set_city_production", cid, {"item": "Nuclear Plant"})
    assert bad["error"]["code"] == "NOT_AVAILABLE", bad
    assert bad["error"].get("candidates"), "no candidates on unbuildable item"
    _, after = h.get("/city/" + cid)
    assert after["production"] == before, f"failed set changed production to {after['production']}"
    return True, "accepted, rejected-with-candidates, state restored"


def research(h):
    _, d = h.intent("set_research", params={"tech": "Pottery"})
    assert d.get("ok"), d
    assert h.get("/state")[1]["researching"] == "Pottery"
    _, bad = h.intent("set_research", params={"tech": "Nonsense"})
    assert bad["error"]["code"] == "UNKNOWN_ID", bad
    return True, "Pottery set, typo rejected"


def move(h):
    _, units = h.get("/units")
    mil = [u for u in units["units"] if u["military"]]
    assert mil, "no military unit"
    u = mil[0]
    _, d = h.intent("move_unit", u["id"], {"x": u["x"] + 2, "y": u["y"]})
    assert d.get("ok"), d
    ev = d["events"][0]
    _, after = h.get("/units")
    now = next(x for x in after["units"] if x["id"] == u["id"])
    assert (now["x"], now["y"]) == tuple(ev["to"]), f"event says {ev['to']}, read says {now['x']},{now['y']}"
    assert ev["arrived"] == ((now["x"], now["y"]) == (u["x"] + 2, u["y"])), "arrived flag disagrees"
    return True, f"event and read agree on {ev['to']}"


def worked_tiles(h):
    cid = h.city()
    _, detail = h.get("/city/" + cid)
    cx, cy = detail["at"]
    _, bad = h.intent("set_worked_tiles", cid, {"tiles": [[cx + 40, cy + 40]]})
    assert bad["error"]["code"] == "ILLEGAL_TARGET", bad
    _, ok = h.intent("set_worked_tiles", cid, {"tiles": [[cx + 1, cy]], "autoFill": False})
    if ok.get("ok"):
        _, after = h.get("/city/" + cid)
        assert [cx + 1, cy] in after["workedTiles"], after["workedTiles"]
        return True, "assigned and readable"
    assert ok["error"]["code"] in ("ILLEGAL_TARGET", "NOT_AVAILABLE"), ok
    return True, "tile not workable here, documented error"


def purchase(h):
    cid = h.city()
    gold = h.get("/state")[1]["gold"]
    _, d = h.intent("purchase_construction", cid, {"item": "Library"})
    assert d["error"]["code"] == "NOT_AVAILABLE", d
    after = h.get("/state")[1]["gold"]
    assert after == gold, f"failed purchase changed gold {gold} -> {after}"
    assert after >= 0, "gold went negative"
    return True, f"rejected, gold unchanged at {gold}"


def specialists(h):
    cid = h.city()
    _, d = h.intent("set_city_specialists", cid, {"specialists": {"Nonsense": 1}})
    assert d["error"]["code"] == "UNKNOWN_ID", d
    assert d["error"].get("candidates"), "no candidate specialist names"
    return True, "unknown specialist lists valid ones"


def trade(h):
    _, dip = h.get("/diplomacy")
    known = [k["civ"] for k in dip["known"]]
    if not known:
        return True, "nobody met yet, skipped"
    _, d = h.intent("propose_trade", params={"civ": known[0], "give": [{"name": "Nonsense"}]})
    assert d["error"]["code"] == "NOT_AVAILABLE", d
    assert d["error"].get("candidates"), "invalid offer not echoed"
    return True, "invalid offer rejected with names"


def war(h):
    _, dip = h.get("/diplomacy")
    known = [k["civ"] for k in dip["known"]]
    if not known:
        return True, "nobody met yet, skipped"
    _, d = h.intent("declare_war", params={"civ": known[0]})
    assert d.get("ok") or d["error"]["code"] in ("NOT_AVAILABLE", "UNKNOWN_ID"), d
    if d.get("ok"):
        _, dip2 = h.get("/diplomacy")
        at_war = next(k for k in dip2["known"] if k["civ"] == known[0])["atWar"]
        assert at_war, "declare_war said ok but diplomacy still says peace"
    return True, "war declared and visible" if d.get("ok") else "documented refusal"


def capture_guard(h):
    _, d = h.intent("capture_city", "city-does-not-exist", {"action": "annex"})
    assert d["error"]["code"] == "NOT_AVAILABLE", d
    assert isinstance(d["error"].get("candidates"), list), d
    return True, "no pending capture -> NOT_AVAILABLE with list"


# ---------------- F ----------------

def production_agree(h):
    _, cities = h.get("/cities")
    for c in cities["cities"]:
        _, detail = h.get("/city/" + c["id"])
        assert c["production"] == detail["production"], f"{c['name']}: {c['production']} vs {detail['production']}"
        assert c["pop"] == detail["pop"], f"{c['name']}: pop differs"
    return True, "cities and city details agree"


def unit_ids_agree(h):
    _, state = h.get("/state")
    _, units = h.get("/units")
    a = sorted(u["id"] for u in state["units"])
    b = sorted(u["id"] for u in units["units"])
    assert a == b, f"{set(a) ^ set(b)}"
    return True, f"{len(a)} ids agree"


def city_counts_agree(h):
    _, state = h.get("/state")
    _, cities = h.get("/cities")
    assert len(state["cities"]) == len(cities["cities"])
    return True, f"{len(cities['cities'])} cities"


def trade_gold_bound(h):
    gold = h.get("/state")[1]["gold"]
    _, dip = h.get("/diplomacy")
    if not dip["known"]:
        return True, "skipped, nobody met"
    _, t = h.get("/trade?civ=" + dip["known"][0]["civ"])
    offers = [o for o in t.get("youCanGive", []) if o["type"] == "Gold"]
    if offers:
        assert offers[0]["amount"] <= gold, f"offer {offers[0]['amount']} > gold {gold}"
    return True, "offer bounded by treasury"


# ---------------- G ----------------

def odd_params(h):
    paths = [
        "/map", "/map?cx=abc&cy=def", "/map?cx=-99999&cy=99999&radius=-5",
        "/events?limit=0", "/events?limit=99999", "/events?limit=abc",
        "/tile", "/tile?x=&y=", "/city/", "/trade?civ=Nope",
        "/actions?unit=abc", "/actions",
    ]
    bad = []
    for p in paths:
        status, _ = h.get(p)
        if status >= 500:
            bad.append(f"{p} -> {status}")
    assert not bad, "; ".join(bad)
    return True, f"{len(paths)} odd requests, no 5xx"


# ---------------- H ----------------

def same_seed(h):
    _, a = h.new_game(seed=777)
    _, b = h.new_game(seed=777)
    assert a["seed"] == 777 and b["seed"] == 777, f"seed not echoed: {a['seed']} {b['seed']}"
    assert a["civ"] == b["civ"], f"civ differs: {a['civ']} vs {b['civ']}"
    # 起始单位落点由 gameId 派生（引擎随机），只有地图和文明是 seed 决定的
    ta = sorted((u["name"] for u in a["units"]))
    tb = sorted((u["name"] for u in b["units"]))
    assert ta == tb, f"starting army differs: {ta} vs {tb}"
    return True, f"{a['civ']} on seed 777, same army"


# ---------------- J ----------------

def mcp_call(h, name, args):
    status, d = h.post("/mcp", {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                                "params": {"name": name, "arguments": args}})
    assert status == 200, f"MCP {name} -> {status}"
    assert "result" in d, d
    return json.loads(d["result"]["content"][0]["text"])


def mcp_arrays(h):
    cid = h.ensure_city()
    _, detail = h.get("/city/" + cid)
    cx, cy = detail["at"]
    # 数组参数必须原样到达内核：坏坐标应当被逐项拒绝，而不是被当成字符串
    out = mcp_call(h, "set_worked_tiles", {"city": cid, "tiles": [[cx + 40, cy + 40]]})
    assert out["error"]["code"] == "ILLEGAL_TARGET", out
    assert f"{cx + 40},{cy + 40}" in out["error"]["message"], out
    # 嵌套对象同理
    out = mcp_call(h, "choose_beliefs", {"beliefs": ["No Such Belief"]})
    assert out["error"]["code"] in ("UNKNOWN_ID", "NOT_AVAILABLE"), out
    return True, "arrays and objects reach the kernel intact"


def preview_readonly(h):
    h.new_game(seed=8181)
    cid = h.ensure_city()
    _, units = h.get("/units")
    mil = next((u for u in units["units"] if u["military"]), None)
    if mil is None:
        return True, "no military unit, skipped"
    _, before = h.get("/state")
    _, b = h.get("/preview_attack?unit=" + mil["id"] + "&x=" + str(mil["x"] + 1) + "&y=" + str(mil["y"]))
    # 没敌人就是错误体，有敌人就是预览体，两种都不能改状态
    if "error" in b:
        assert b["error"]["code"] in ("ILLEGAL_TARGET", "UNKNOWN_ID", "BAD_PARAMS"), b
    else:
        for f in ("target", "targetType", "distance", "range", "inRange",
                  "damageToDefender", "damageToAttacker"):
            assert f in b, f"preview missing {f}"
    _, after = h.get("/state")
    _, units2 = h.get("/units")
    now = next(u for u in units2["units"] if u["id"] == mil["id"])
    assert after["turn"] == before["turn"], "preview advanced the turn"
    assert now["moves"] == mil["moves"], "preview spent movement"
    return True, "preview changes nothing"


def worker_tools(h):
    """工人能力在 UI 层，内核自己实现了 build_improvement；这里只验证拒绝得干净。"""
    h.new_game(seed=9191)
    h.ensure_city()
    _, tools = h.get("/tools")
    names = {t["name"] for t in tools["tools"]}
    assert "build_improvement" in names and "disband_unit" in names, sorted(names)
    _, units = h.get("/units")
    mil = next((u for u in units["units"] if u["military"]), None)
    assert mil, "no military unit"
    _, r = h.intent("build_improvement", mil["id"], {"improvement": "Farm"})
    assert not r.get("ok"), r
    assert r["error"]["code"] == "NOT_AVAILABLE", r
    assert "工人" in r["error"]["message"], r
    _, r2 = h.intent("build_improvement", mil["id"], {"improvement": "NoSuchThing"})
    assert not r2.get("ok") and r2["error"]["code"] in ("NOT_AVAILABLE", "UNKNOWN_ID"), r2
    _, acts = h.get("/actions?unit=" + str(mil["id"].split("-")[-1]))
    assert all(a["type"] != "build_improvement" for a in acts["actions"]), acts
    return True, "non-worker refused, no phantom action"


def guide_shape(h):
    status, g = h.get("/guide")
    assert status == 200, f"/guide -> {status}"
    assert g["entries"], "no tutorial entries"
    names = [e["name"] for e in g["entries"]]
    assert len(names) > 20, f"only {len(names)} entries"
    _, one = h.get("/guide?name=New%20Game")
    assert one["entries"] and one["entries"][0]["text"], "filter or text missing"
    return True, f"{len(names)} entries, filter works"


def briefing_shape(h):
    cid = h.ensure_city()
    _, b = h.get("/briefing")
    for field in ("turn", "civ", "gold", "goldPerTurn", "happiness", "research",
                  "cities", "unitsNeedingOrders", "diplomacy", "religion",
                  "pendingCaptures", "events", "warnings"):
        assert field in b, f"briefing missing {field}"
    _, state = h.get("/state")
    assert b["turn"] == state["turn"], "turn disagrees with /state"
    assert b["gold"] == state["gold"], "gold disagrees with /state"
    assert len(b["cities"]) == len(state["cities"]), "city count disagrees"
    assert any(c["id"] == cid for c in b["cities"]), "own city missing"
    # 简报里的城市必须能直接拿去调用动作
    city = next(c for c in b["cities"] if c["id"] == cid)
    assert city["production"] is None or isinstance(city["production"], str), city
    return True, f"{len(b['cities'])} cities, {len(b['unitsNeedingOrders'])} units need orders"


def mcp_act_parity(h):
    cid = h.ensure_city()
    _, http = h.intent("set_city_production", cid, {"item": "Monument"})
    mcp = mcp_call(h, "set_city_production", {"city": cid, "item": "Monument"})
    assert http.get("ok") and mcp.get("ok"), f"{http} / {mcp}"
    assert http["events"][0]["type"] == mcp["events"][0]["type"], "event types differ"
    return True, "same event shape on both surfaces"


# ---------------- I ----------------

def failed_action_free(h):
    """An action that errors must not cost the turn, movement, gold or production."""
    h.new_game(seed=5150)
    cid = h.ensure_city()
    h.intent("set_city_production", cid, {"item": "Monument"})
    before = h.get("/state")[1]
    _, units = h.get("/units")
    u = next(x for x in units["units"] if x["military"])
    h.intent("move_unit", u["id"], {"x": 9999, "y": 9999})
    h.intent("set_city_production", cid, {"item": "Nuclear Plant"})
    h.intent("purchase_construction", cid, {"item": "Library"})
    after = h.get("/state")[1]
    assert after["turn"] == before["turn"], f"turn moved {before['turn']} -> {after['turn']}"
    assert after["gold"] == before["gold"], f"gold moved {before['gold']} -> {after['gold']}"
    _, detail = h.get("/city/" + cid)
    assert detail["production"] == "Monument", f"production changed to {detail['production']}"
    _, units2 = h.get("/units")
    u2 = next(x for x in units2["units"] if x["id"] == u["id"])
    assert (u2["x"], u2["y"]) == (u["x"], u["y"]), "failed move still moved the unit"
    assert u2["moves"] == u["moves"], f"failed move spent movement {u['moves']} -> {u2['moves']}"
    return True, "four failures, nothing spent"


def id_reuse(h):
    cid = h.ensure_city()
    _, first = h.intent("set_city_production", cid, {"item": "Monument"}, iid="reuse-1")
    assert first.get("ok"), first
    _, second = h.intent("set_city_production", cid, {"item": "Warrior"}, iid="reuse-1")
    assert second.get("error", {}).get("code") == "ID_REUSED", second
    _, detail = h.get("/city/" + cid)
    assert detail["production"] == "Monument", "reused id silently applied the second action"
    return True, "different payload under one id is refused"


def no_move_attack(h):
    _, units = h.get("/units")
    mil = [x for x in units["units"] if x["military"]]
    if not mil:
        return True, "no military unit, skipped"
    u = mil[0]
    h.intent("move_unit", u["id"], {"x": u["x"] + 2, "y": u["y"]})
    _, after = h.get("/units")
    u2 = next(x for x in after["units"] if x["id"] == u["id"])
    if u2["moves"] > 0:
        return True, "still has movement, nothing to assert"
    _, d = h.intent("attack", u["id"], {"x": u2["x"] + 1, "y": u2["y"]})
    assert not d.get("ok"), "attack accepted with no movement left"
    assert d["error"]["code"] in ("NOT_AVAILABLE", "ILLEGAL_TARGET"), d
    return True, "refused with " + d["error"]["code"]


def intent_fuzz(h):
    bodies = [
        {"type": "move_unit", "target": "unit-0", "params": {"x": "a", "y": None}},
        {"type": "set_city_production", "target": "city-x", "params": {"item": 123}},
        {"type": "propose_trade", "params": {"civ": 1, "give": "nope"}},
        {"type": "choose_beliefs", "params": {"beliefs": "not-a-list"}},
        {"type": "set_worked_tiles", "target": "city-x", "params": {"tiles": "nope"}},
        {"type": "spy_move", "params": {"spy": [], "city": {}}},
        {"type": "capture_city", "target": None, "params": {"action": ["annex"]}},
    ]
    bad = []
    for b in bodies:
        status, d = h.post("/intent", b)
        if status >= 500:
            bad.append(f"{b['type']} -> {status}")
        elif d.get("error", {}).get("code") == "INTERNAL":
            bad.append(f"{b['type']} -> INTERNAL")
    assert not bad, "; ".join(bad)
    return True, f"{len(bodies)} malformed intents, all documented errors"


def latency(h):
    t0 = time.time()
    h.new_game(seed=6161, players=3, size="Small")
    new_ms = (time.time() - t0) * 1000
    cid = h.ensure_city()
    h.intent("set_city_production", cid, {"item": "Monument", "count": 3})
    t0 = time.time()
    for _ in range(10):
        h.intent("end_turn")
    turn_ms = (time.time() - t0) * 1000 / 10
    assert new_ms < 15000, f"/new took {new_ms:.0f}ms"
    assert turn_ms < 3000, f"turn took {turn_ms:.0f}ms"
    return True, f"/new {new_ms:.0f}ms, turn {turn_ms:.0f}ms"


if __name__ == "__main__":
    main()
