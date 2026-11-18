#!/usr/bin/env python3
"""Play a fixed number of turns through the HTTP API with a dumb heuristic bot.

This is the skeleton of a play benchmark: it does not try to play well, it tries
to prove that a whole game runs without the harness getting in the way. Every
request is checked for 5xx and INTERNAL; the final block is the metric set a
benchmark would compare between models.

    python3 play_smoke.py --port 8240 --turns 40 --seed 4242 [--keep]

Output: one JSON object on stdout (plus progress on stderr).
"""
import argparse
import json
import os
import subprocess
import sys
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

BUILD_ORDER = ["Monument", "Worker", "Settler", "Warrior", "Library", "Shrine",
               "Granary", "Archer", "Walls", "Market", "University"]


class Bot:
    def __init__(self, base):
        self.base = base
        self.errors = []
        self.calls = 0

    def req(self, method, path, body=None):
        self.calls += 1
        data = None if body is None else json.dumps(body).encode()
        r = urllib.request.Request(self.base + path, data=data, method=method)
        try:
            with urllib.request.urlopen(r, timeout=30) as resp:
                return resp.status, json.loads(resp.read())
        except urllib.error.HTTPError as e:
            raw = e.read()
            if e.code >= 500:
                self.errors.append(f"{method} {path} -> {e.code}")
            try:
                return e.code, json.loads(raw)
            except Exception:  # noqa: BLE001
                return e.code, {}
        except Exception as e:  # noqa: BLE001
            self.errors.append(f"{method} {path} -> {type(e).__name__}")
            return 0, {}

    def get(self, path):
        return self.req("GET", path)[1]

    def act(self, type_, target=None, params=None):
        body = {"type": type_, "params": params or {}}
        if target:
            body["target"] = target
        status, d = self.req("POST", "/intent", body)
        if d.get("error", {}).get("code") == "INTERNAL":
            self.errors.append(f"intent {type_} -> INTERNAL: {d['error'].get('message')}")
        return d


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8240)
    ap.add_argument("--turns", type=int, default=40)
    ap.add_argument("--seed", type=int, default=4242)
    ap.add_argument("--players", type=int, default=2)
    ap.add_argument("--size", default="Duel")
    ap.add_argument("--era", default=None)
    ap.add_argument("--keep", action="store_true")
    args = ap.parse_args()

    files = f"/tmp/unciv-play-{args.port}"
    os.makedirs(files, exist_ok=True)
    env = dict(os.environ, UNCIV_AGENT_PORT=str(args.port), UNCIV_AGENT_FILES=files)
    proc = subprocess.Popen([JAVA, "-Xmx2g", "-cp", CP, "uncivagent.Agent"],
                            cwd=ASSETS, env=env,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    base = f"http://127.0.0.1:{args.port}"
    try:
        for _ in range(120):
            try:
                urllib.request.urlopen(base + "/health", timeout=2)
                break
            except Exception:  # noqa: BLE001
                time.sleep(0.25)
        bot = Bot(base)
        body = {"players": args.players, "humans": 1, "seed": args.seed, "size": args.size}
        if args.era:
            body["era"] = args.era
        bot.req("POST", "/new", body)

        # 落地
        units = bot.get("/units")["units"]
        settler = next((u for u in units if u["name"] == "Settler"), None)
        if settler:
            bot.act("found_city", settler["id"])

        last_turn = -1
        for _ in range(args.turns):
            # 每回合只读这一条：简报就是喂给模型的提示词
            brief = bot.get("/briefing")
            turn = brief.get("turn", 0)

            if brief["research"]["current"] is None:
                techs = bot.get("/techs").get("available", [])
                if techs:
                    bot.act("set_research", params={"tech": min(techs, key=lambda t: t["cost"])["name"]})

            for city in brief["cities"]:
                if city["production"] is None:
                    detail = bot.get("/city/" + city["id"])
                    buildable = detail.get("buildable", [])
                    pick = next((b for b in BUILD_ORDER if b in buildable), None)
                    if pick:
                        bot.act("set_city_production", city["id"], {"item": pick, "count": 2})
                if brief["gold"] > 200:
                    detail = bot.get("/city/" + city["id"])
                    buys = sorted(detail.get("purchasable", []), key=lambda x: x.get("gold", 10 ** 9))
                    if buys and brief["gold"] > buys[0].get("gold", 10 ** 9):
                        bot.act("purchase_construction", city["id"], {"item": buys[0]["item"]})

            for u in brief["unitsNeedingOrders"]:
                if u["civilian"]:
                    continue
                bot.act("move_unit", u["id"], {"x": u["x"] + 4, "y": u["y"] - 2})

            r = bot.act("end_turn")
            ev = (r.get("events") or [{}])[0]
            if ev.get("type") == "turn_advanced":
                last_turn = ev.get("to", turn)

        state = bot.get("/state")
        cities = bot.get("/cities").get("cities", [])
        metrics = {
            "seed": args.seed,
            "era": args.era,
            "turns_played": last_turn,
            "civ": state.get("civ"),
            "cities": len(cities),
            "population": sum(c.get("pop", 0) for c in cities),
            "techs": state.get("techCount"),
            "gold": state.get("gold"),
            "happiness": state.get("stats", {}).get("happiness"),
            "units": len(bot.get("/units").get("units", [])),
            "known_civs": len(bot.get("/diplomacy").get("known", [])),
            "requests": bot.calls,
            "harness_errors": bot.errors,
            "ok": not bot.errors,
        }
        print(json.dumps(metrics, ensure_ascii=False, indent=2))
        sys.exit(0 if not bot.errors else 1)
    finally:
        if not args.keep:
            proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()


if __name__ == "__main__":
    main()
