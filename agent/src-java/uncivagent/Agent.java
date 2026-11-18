package uncivagent;

import com.badlogic.gdx.files.FileHandle;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.unciv.UncivGame;
import com.unciv.logic.GameInfo;
import com.unciv.logic.GameStarter;
import com.unciv.logic.city.City;
import com.unciv.logic.civilization.Civilization;
import com.unciv.logic.civilization.Notification;
import com.unciv.logic.civilization.PlayerType;
import com.unciv.logic.files.FileConversions;
import com.unciv.logic.map.HexCoord;
import com.unciv.logic.map.MapParameters;
import com.unciv.logic.map.MapSize;
import com.unciv.logic.map.mapunit.MapUnit;
import com.unciv.logic.map.mapunit.actions.UnitActions;
import com.unciv.logic.map.tile.Tile;
import com.unciv.models.UnitAction;
import com.unciv.models.UnitActionType;
import com.unciv.models.metadata.GameParameters;
import com.unciv.models.metadata.GameSetupInfo;
import com.unciv.models.metadata.GameSettings;
import com.unciv.models.metadata.Player;
import com.unciv.models.ruleset.RulesetCache;
import com.unciv.view.GameView;
import com.unciv.view.MapUnitView;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unciv 权威宿主。
 *
 * 三条入口：
 *   1. Agent 接口：/intent（动作结构体）、/mcp（JSON-RPC）、若干只读路由
 *   2. 管理面：/new /load /save /admin/token
 *   3. 官方客户端协议：/isalive /auth /files/{name}（PUT/GET/DELETE）
 *
 * 多文明模式：用 /admin/token 给每个文明发 token，请求带 Authorization: Bearer <token>
 * 或 X-Agent-Token，宿主据此决定视角与权限。没有 token 时退回单人模式（当前回合文明）。
 */
public class Agent {

    private static UncivGame game;
    private static GameInfo gameInfo;

    /** token -> 文明名 */
    private static final Map<String, String> tokenToCiv = new LinkedHashMap<>();
    /** 本回合已提交的文明名 */
    private static final LinkedHashSet<String> submitted = new LinkedHashSet<>();
    /** 官方客户端文件目录 */
    private static File fileRoot;

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(env("UNCIV_AGENT_PORT", "8099"));
        String save = System.getenv("UNCIV_AGENT_SAVE");
        String root = env("UNCIV_AGENT_FILES", "/home/node/.openclaw/state/unciv-host/files");
        fileRoot = new File(root);
        fileRoot.mkdirs();

        boot();
        if (save != null) loadSave(save);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", Agent::handle);
        server.setExecutor(null);
        server.start();
        System.out.println("[unciv-agent] listening on 0.0.0.0:" + port
                + " cwd=" + new File(".").getAbsolutePath()
                + " files=" + fileRoot.getAbsolutePath());
        if (gameInfo == null) System.out.println("[unciv-agent] 无存档，等 POST /load 或 POST /new");
    }

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isEmpty() ? d : v;
    }

    private static void boot() {
        game = new UncivGame(true);
        UncivGame.Companion.setCurrent(game);
        game.setSettings(new GameSettings());
        RulesetCache.INSTANCE.loadRulesets(true, false);
        System.out.println("[unciv-agent] 规则集加载完成，共 " + RulesetCache.INSTANCE.size() + " 个");
    }

    private static void loadSave(String path) {
        GameInfo gi = FileConversions.INSTANCE.readJson(new FileHandle(new File(path)), GameInfo.class);
        if (gi == null) throw new IllegalStateException("读不出存档: " + path);
        gi.setTransients();
        gameInfo = gi;
        game.setGameInfo(gi);
        submitted.clear();
        System.out.println("[unciv-agent] 载入存档 " + path + " 回合 " + gi.getTurns());
    }

    // ---------------- 身份 ----------------

    private static String tokenOf(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) return auth.substring(7).trim();
        String h = ex.getRequestHeaders().getFirst("X-Agent-Token");
        return h == null ? null : h.trim();
    }

    /** 解析出这次请求代表哪个文明；单人模式退回当前回合文明 */
    private static Civilization actorFor(HttpExchange ex) {
        GameInfo gi = gameInfo;
        if (gi == null) return null;
        String token = tokenOf(ex);
        if (token != null && tokenToCiv.containsKey(token)) {
            String civName = tokenToCiv.get(token);
            for (Civilization c : gi.getCivilizations()) {
                if (c.getCivName().equals(civName)) return c;
            }
        }
        return gi.getCurrentPlayerCiv();
    }

    private static List<Civilization> humanCivs(GameInfo gi) {
        List<Civilization> out = new ArrayList<>();
        for (Civilization c : gi.getCivilizations()) {
            if (c.isHuman() && c.isAlive()) out.add(c);
        }
        return out;
    }

    // ---------------- 回合推进 ----------------

    private static final Kernel.UncivGameRef TURN = new Kernel.UncivGameRef() {
        @Override public void advanceTurn(GameInfo gi, Civilization actor) {
            List<Civilization> humans = humanCivs(gi);
            if (humans.size() <= 1) {
                gi.nextTurn(null, false);
                return;
            }
            submitted.add(actor.getCivName());
            for (Civilization h : humans) {
                if (!submitted.contains(h.getCivName())) return;   // 还有人没交，等
            }
            // 都交了：一路推到下一个回合，让 AI 文明自己走完
            submitted.clear();
            int before = gi.getTurns();
            int guard = 0;
            do {
                gi.nextTurn(null, false);
                guard++;
            } while (guard < 64 && (gi.getTurns() == before || !gi.getCurrentPlayerCiv().isHuman()));
        }
    };

    // ---------------- HTTP ----------------

    private static void handle(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            String method = ex.getRequestMethod().toUpperCase();

            // ---- 官方客户端协议 ----
            if (path.equals("/isalive")) { send(ex, 200, "{\"online\":true}"); return; }
            if (path.equals("/auth")) { handleAuth(ex, method); return; }
            if (path.startsWith("/files/")) { handleFiles(ex, path.substring(7), method); return; }

            // ---- 探活与管理 ----
            if (path.equals("/health")) { send(ex, 200, "{\"ok\":true}"); return; }
            if (path.equals("/tools")) { send(ex, 200, toolsJson()); return; }

            if (path.equals("/admin/civs")) {
                StringBuilder sb = new StringBuilder("{\"turn\":").append(need().getTurns()).append(",\"civs\":[");
                boolean firstCiv = true;
                for (Civilization c : need().getCivilizations()) {
                    if (!firstCiv) sb.append(',');
                    firstCiv = false;
                    String tok = null;
                    for (Map.Entry<String, String> e : tokenToCiv.entrySet()) {
                        if (e.getValue().equals(c.getCivName())) tok = e.getKey();
                    }
                    sb.append("{\"civ\":").append(quote(c.getCivName()))
                      .append(",\"human\":").append(c.isHuman())
                      .append(",\"alive\":").append(c.isAlive())
                      .append(",\"submitted\":").append(submitted.contains(c.getCivName()))
                      .append(",\"hasToken\":").append(tok != null).append('}');
                }
                send(ex, 200, sb.append("]}").toString());
                return;
            }

            if (path.equals("/admin/token") && method.equals("POST")) {
                Map<String, Object> body = Json.obj(Json.parse(readBody(ex)));
                String civ = Json.str(body, "civ");
                if (civ == null) { send(ex, 400, "{\"error\":\"需要 civ\"}"); return; }
                String token = Json.str(body, "token");
                if (token == null) token = java.util.UUID.randomUUID().toString();
                tokenToCiv.put(token, civ);
                send(ex, 200, "{\"ok\":true,\"civ\":" + Kernel.q(civ) + ",\"token\":" + Kernel.q(token) + "}");
                return;
            }

            // ---- Agent 接口 ----
            Civilization actor = actorFor(ex);

            if (actor == null && (path.equals("/intent") || path.equals("/mcp") || path.equals("/state")
                    || path.equals("/units") || path.equals("/cities") || path.equals("/map")
                    || path.equals("/events") || path.equals("/diplomacy") || path.equals("/policies")
                    || path.startsWith("/city/") || path.equals("/actions")
                    || path.equals("/act") || path.equals("/move") || path.equals("/endturn"))) {
                actor = need().getCurrentPlayerCiv();
            }

            if (path.equals("/intent") && method.equals("POST")) {
                send(ex, 200, Kernel.handleIntent(readBody(ex), need(), TURN, actor));
                return;
            }
            if (path.equals("/mcp") && method.equals("POST")) {
                String out = Kernel.handleMcp(readBody(ex), need(), TURN, actor);
                if (out.isEmpty()) { ex.sendResponseHeaders(202, -1); ex.close(); return; }
                send(ex, 200, out);
                return;
            }

            if (path.equals("/state")) { send(ex, 200, stateJson(need(), actor)); return; }
            if (path.equals("/units")) { send(ex, 200, unitsJson(need(), actor)); return; }
            if (path.equals("/cities")) { send(ex, 200, citiesJson(need(), actor)); return; }
            if (path.equals("/map")) {
                send(ex, 200, mapJson(need(), actor, intq(ex, "cx"), intq(ex, "cy"), intq(ex, "radius")));
                return;
            }
            if (path.equals("/events")) { send(ex, 200, eventsJson(need(), actor, intq(ex, "limit"))); return; }
            if (path.equals("/diplomacy")) { send(ex, 200, diplomacyJson(need(), actor)); return; }
            if (path.equals("/policies")) { send(ex, 200, policiesJson(need(), actor)); return; }
            if (path.startsWith("/city/")) {
                send(ex, 200, cityDetailsJson(need(), actor, path.substring(6)));
                return;
            }

            if (path.equals("/actions") && method.equals("GET")) {
                String u = query(ex, "unit");
                if (u == null) { send(ex, 400, err("需要 ?unit=<id>")); return; }
                send(ex, 200, actionsJson(need(), actor, Integer.parseInt(u)));
                return;
            }

            // 兼容旧接口（内部转成 Intent）
            if (path.equals("/act") && method.equals("POST")) {
                Map<String, Object> b = Json.obj(Json.parse(readBody(ex)));
                String type = Json.str(b, "type");
                Integer unit = Json.integer(b, "unit");
                if (type == null) { send(ex, 400, err("需要 type")); return; }
                String intent = "{\"type\":" + Kernel.q(normalizeAction(type))
                        + ",\"target\":" + (unit == null ? "null" : Kernel.q("unit-" + unit))
                        + ",\"params\":{}}";
                send(ex, 200, Kernel.handleIntent(intent, need(), TURN, actor));
                return;
            }

            if (path.equals("/move") && method.equals("POST")) {
                Map<String, Object> b = Json.obj(Json.parse(readBody(ex)));
                Integer unit = Json.integer(b, "unit");
                Integer x = Json.integer(b, "x");
                Integer y = Json.integer(b, "y");
                if (unit == null || x == null || y == null) { send(ex, 400, err("需要 unit、x、y")); return; }
                String intent = "{\"type\":\"move_unit\",\"target\":" + Kernel.q("unit-" + unit)
                        + ",\"params\":{\"x\":" + x + ",\"y\":" + y + "}}";
                send(ex, 200, Kernel.handleIntent(intent, need(), TURN, actor));
                return;
            }

            if (path.equals("/endturn") && method.equals("POST")) {
                send(ex, 200, Kernel.handleIntent("{\"type\":\"end_turn\",\"params\":{}}", need(), TURN, actor));
                return;
            }

            // ---- 存档管理 ----
            if (path.equals("/new") && method.equals("POST")) {
                Map<String, Object> b = Json.obj(Json.parse(readBody(ex)));
                Integer players = Json.integer(b, "players");
                Integer seed = Json.integer(b, "seed");
                Integer humans = Json.integer(b, "humans");
                send(ex, 200, newGameJson(players == null ? 2 : players,
                        humans == null ? 1 : humans,
                        Json.str(b, "difficulty"), Json.str(b, "speed"),
                        Json.str(b, "civ"), seed == null ? 0L : seed.longValue()));
                return;
            }
            if (path.equals("/load") && method.equals("POST")) {
                Map<String, Object> b = Json.obj(Json.parse(readBody(ex)));
                String p = Json.str(b, "path");
                if (p == null) { send(ex, 400, err("需要 path")); return; }
                loadSave(p);
                send(ex, 200, stateJson(need(), need().getCurrentPlayerCiv()));
                return;
            }
            if (path.equals("/save") && method.equals("POST")) {
                Map<String, Object> b = Json.obj(Json.parse(readBody(ex)));
                String p = Json.str(b, "path");
                if (p == null) { send(ex, 400, err("需要 path")); return; }
                FileConversions.INSTANCE.writeJson(new FileHandle(new File(p)), need(), false);
                send(ex, 200, "{\"ok\":true,\"path\":" + Kernel.q(p) + "}");
                return;
            }

            send(ex, 404, err("没有这个接口: " + path));
        } catch (Throwable t) {
            t.printStackTrace();
            StringBuilder tb = new StringBuilder(t.toString());
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(3, st.length); i++) tb.append(" | ").append(st[i]);
            try { send(ex, 500, err(tb.toString())); } catch (Throwable ignored) { }
        }
    }

    /** 旧的 /act 用驼峰名，Intent 用小写，这里做一次映射 */
    private static String normalizeAction(String type) {
        switch (type) {
            case "FoundCity": return "found_city";
            case "Sleep":
            case "Fortify":
            case "Explore":
            case "Automate":
            case "Pillage":
            case "SetUp":
            case "Disband":
                return "unit_action";
            default: return type;
        }
    }

    // ---- 官方客户端文件协议 ----

    private static void handleAuth(HttpExchange ex, String method) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Basic ")) {
            ex.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"unciv\"");
            try { send(ex, 401, "{\"error\":\"需要 Basic 认证\"}"); } catch (IOException ignored) { }
            return;
        }
        try {
            String pair = new String(Base64.getDecoder().decode(auth.substring(6)), StandardCharsets.UTF_8);
            int i = pair.indexOf(':');
            String user = i > 0 ? pair.substring(0, i) : pair;
            send(ex, 200, "{\"ok\":true,\"user\":" + Kernel.q(user) + "}");
        } catch (Exception e) {
            try { send(ex, 400, err("认证头无法解析")); } catch (IOException ignored) { }
        }
    }

    private static void handleFiles(HttpExchange ex, String name, String method) throws IOException {
        if (name.isEmpty() || name.contains("/") || name.contains("..")) {
            send(ex, 400, err("非法文件名"));
            return;
        }
        File f = new File(fileRoot, name);
        switch (method) {
            case "GET": {
                if (!f.exists()) { send(ex, 404, err("没有这个文件")); return; }
                byte[] data = Files.readAllBytes(f.toPath());
                ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                ex.sendResponseHeaders(200, data.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(data); }
                return;
            }
            case "PUT": {
                byte[] data = ex.getRequestBody().readAllBytes();
                if (data.length > 32 * 1024 * 1024) { send(ex, 413, err("文件太大")); return; }
                Files.write(f.toPath(), data);
                send(ex, 200, "{\"ok\":true,\"name\":" + Kernel.q(name) + ",\"bytes\":" + data.length + "}");
                return;
            }
            case "DELETE": {
                if (!f.exists()) { send(ex, 404, err("没有这个文件")); return; }
                f.delete();
                send(ex, 200, "{\"ok\":true}");
                return;
            }
            default:
                send(ex, 405, err("不支持的方法"));
        }
    }

    // ---------------- 工具与输出 ----------------

    private static String toolsJson() {
        StringBuilder sb = new StringBuilder("{\"tools\":[");
        boolean first = true;
        for (Kernel.Tool t : Kernel.tools().values()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"name\":").append(Kernel.q(t.name))
              .append(",\"kind\":").append(Kernel.q(t.kind))
              .append(",\"description\":").append(Kernel.q(t.description))
              .append(",\"schema\":").append(t.schema).append('}');
        }
        return sb.append("]}").toString();
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String query(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return null;
        for (String pair : q.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0 && pair.substring(0, i).equals(key))
                return URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    private static Integer intq(HttpExchange ex, String key) {
        String v = query(ex, key);
        return v == null ? null : Integer.valueOf(v);
    }

    private static String quote(String s) { return Kernel.q(s); }

    private static String err(String msg) { return "{\"error\":" + quote(msg) + "}"; }

    private static GameInfo need() {
        if (gameInfo == null) throw new IllegalStateException("没有载入游戏，先 POST /load 或 /new");
        return gameInfo;
    }

    // ---------------- 读投影 ----------------

    static String stateJson(GameInfo gi, Civilization c) {
        StringBuilder cities = new StringBuilder();
        for (City city : c.getCities()) {
            if (cities.length() > 0) cities.append(',');
            cities.append("{\"id\":").append(quote("city-" + city.getId()))
                  .append(",\"name\":").append(quote(city.getName()))
                  .append(",\"pop\":").append(city.getPopulation().getPopulation())
                  .append(",\"x\":").append(city.getLocation().getX())
                  .append(",\"y\":").append(city.getLocation().getY())
                  .append(",\"production\":").append(quote(city.getCityConstructions().getCurrentConstruction().getName()))
                  .append('}');
        }
        StringBuilder units = new StringBuilder();
        Iterator<MapUnit> it = c.getUnits().getCivUnits().iterator();
        while (it.hasNext()) {
            MapUnit u = it.next();
            if (units.length() > 0) units.append(',');
            HexCoord p = u.getTile().getPosition();
            units.append("{\"id\":").append(quote("unit-" + u.getId()))
                 .append(",\"name\":").append(quote(u.getName()))
                 .append(",\"x\":").append(p.getX())
                 .append(",\"y\":").append(p.getY())
                 .append(",\"hp\":").append(u.getHealth())
                 .append(",\"moves\":").append(u.getCurrentMovement()).append('}');
        }
        String researching = c.getTech().currentTechnologyName();
        return "{\"turn\":" + gi.getTurns()
                + ",\"civ\":" + quote(c.getCivName())
                + ",\"alive\":" + c.isAlive()
                + ",\"defeated\":" + c.isDefeated()
                + ",\"gold\":" + c.getGold()
                + ",\"researching\":" + quote(researching == null ? "" : researching)
                + ",\"techCount\":" + c.getTech().getTechsResearched().size()
                + ",\"cities\":[" + cities + "]"
                + ",\"units\":[" + units + "]}";
    }

    static String unitsJson(GameInfo gi, Civilization c) {
        GameView gv = new GameView(gi, c, false);
        StringBuilder sb = new StringBuilder("{\"units\":[");
        boolean first = true;
        Iterator<MapUnit> it = c.getUnits().getCivUnits().iterator();
        while (it.hasNext()) {
            MapUnit u = it.next();
            MapUnitView v = gv.getMapUnitView(u);
            if (!first) sb.append(',');
            first = false;
            HexCoord p = u.getTile().getPosition();
            sb.append("{\"id\":").append(quote("unit-" + v.getId()))
              .append(",\"name\":").append(quote(v.getName()))
              .append(",\"x\":").append(p.getX())
              .append(",\"y\":").append(p.getY())
              .append(",\"hp\":").append(v.getUnitHealth())
              .append(",\"moves\":").append(v.getCurrentMovement())
              .append(",\"maxMoves\":").append(v.getMaxMovement())
              .append(",\"canAttack\":").append(v.canAttack())
              .append('}');
        }
        return sb.append("]}").toString();
    }

    static String citiesJson(GameInfo gi, Civilization c) {
        StringBuilder sb = new StringBuilder("{\"cities\":[");
        boolean first = true;
        for (City city : c.getCities()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(quote("city-" + city.getId()))
              .append(",\"name\":").append(quote(city.getName()))
              .append(",\"pop\":").append(city.getPopulation().getPopulation())
              .append(",\"x\":").append(city.getLocation().getX())
              .append(",\"y\":").append(city.getLocation().getY())
              .append(",\"production\":").append(quote(city.getCityConstructions().getCurrentConstruction().getName()))
              .append('}');
        }
        return sb.append("]}").toString();
    }

    static String cityDetailsJson(GameInfo gi, Civilization c, String cityId) {
        City target = null;
        StringBuilder cand = new StringBuilder();
        for (City city : c.getCities()) {
            if (cand.length() > 0) cand.append(',');
            cand.append("{\"id\":").append(quote("city-" + city.getId()))
                .append(",\"name\":").append(quote(city.getName())).append('}');
            if (("city-" + city.getId()).equals(cityId) || city.getId().equals(cityId)
                    || city.getName().equals(cityId)) target = city;
        }
        if (target == null) {
            return "{\"ok\":false,\"error\":{\"code\":\"UNKNOWN_ID\",\"message\":"
                    + quote("没有这个城市: " + cityId) + ",\"candidates\":[" + cand + "]}}";
        }

        com.unciv.models.ruleset.Ruleset rs = gi.getRuleset();
        List<String> pool = new ArrayList<>();
        pool.addAll(rs.getBuildings().keySet());
        pool.addAll(rs.getUnits().keySet());
        StringBuilder buildable = new StringBuilder();
        int shown = 0;
        for (String name : pool) {
            if (shown >= 40) break;
            try {
                com.unciv.models.ruleset.IConstruction con = target.getCityConstructions().getConstruction$unciv_core(name);
                if (con != null && target.getCityConstructions().canAddToQueue(con,
                        target.getCityConstructions().getConstructionQueue())) {
                    if (shown > 0) buildable.append(',');
                    buildable.append(quote(name));
                    shown++;
                }
            } catch (Throwable ignored) { }
        }
        StringBuilder queue = new StringBuilder();
        for (String q : target.getCityConstructions().getConstructionQueue()) {
            if (queue.length() > 0) queue.append(',');
            queue.append(quote(q));
        }
        return "{\"city\":" + quote("city-" + target.getId())
                + ",\"name\":" + quote(target.getName())
                + ",\"pop\":" + target.getPopulation().getPopulation()
                + ",\"at\":[" + target.getLocation().getX() + "," + target.getLocation().getY() + "]"
                + ",\"focus\":" + quote(target.getCityFocus().name())
                + ",\"production\":" + quote(target.getCityConstructions().getCurrentConstruction().getName())
                + ",\"queue\":[" + queue + "]"
                + ",\"buildable\":[" + buildable + "]}";
    }

    static String mapJson(GameInfo gi, Civilization c, Integer cx, Integer cy, Integer radius) {
        int r = radius == null ? 3 : Math.max(0, Math.min(radius, 12));
        int centerX = cx == null ? 0 : cx;
        int centerY = cy == null ? 0 : cy;
        StringBuilder sb = new StringBuilder("{\"center\":[").append(centerX).append(',').append(centerY)
                .append("],\"radius\":").append(r).append(",\"tiles\":[");
        boolean first = true;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                Tile t = gi.getTileMap().get(new HexCoord(centerX + dx, centerY + dy));
                if (t == null || !t.isExplored(c)) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"x\":").append(t.getPosition().getX())
                  .append(",\"y\":").append(t.getPosition().getY())
                  .append(",\"terrain\":").append(quote(t.baseTerrain))
                  .append(",\"visible\":").append(t.isVisible(c));
                String res = t.getResource();
                if (res != null) sb.append(",\"resource\":").append(quote(res));
                sb.append('}');
            }
        }
        return sb.append("]}").toString();
    }

    static String eventsJson(GameInfo gi, Civilization c, Integer limit) {
        int n = limit == null ? 20 : Math.max(1, Math.min(limit, 200));
        List<Notification> all = c.getNotifications();
        int start = Math.max(0, all.size() - n);
        StringBuilder sb = new StringBuilder("{\"total\":").append(all.size()).append(",\"events\":[");
        for (int i = start; i < all.size(); i++) {
            Notification no = all.get(i);
            if (i > start) sb.append(',');
            sb.append("{\"category\":").append(quote(no.getCategory().name()))
              .append(",\"text\":").append(quote(no.getText())).append('}');
        }
        return sb.append("]}").toString();
    }

    static String diplomacyJson(GameInfo gi, Civilization c) {
        StringBuilder sb = new StringBuilder("{\"me\":" + quote(c.getCivName()) + ",\"known\":[");
        boolean first = true;
        Iterator<Civilization> it = c.getKnownCivs().iterator();
        while (it.hasNext()) {
            Civilization other = it.next();
            if (other == c) continue;
            com.unciv.logic.civilization.diplomacy.DiplomacyManager dm = c.getDiplomacyManager(other);
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"civ\":").append(quote(other.getCivName()))
              .append(",\"atWar\":").append(c.isAtWarWith(other))
              .append(",\"canDeclareWar\":").append(dm != null && dm.canDeclareWar())
              .append('}');
        }
        return sb.append("]}").toString();
    }

    static String policiesJson(GameInfo gi, Civilization c) {
        StringBuilder adopted = new StringBuilder();
        for (String p : c.getPolicies().getAdoptedPolicies()) {
            if (adopted.length() > 0) adopted.append(',');
            adopted.append(quote(p));
        }
        StringBuilder branches = new StringBuilder();
        for (com.unciv.models.ruleset.PolicyBranch b : c.getPolicies().getAdoptableBranches()) {
            if (branches.length() > 0) branches.append(',');
            branches.append(quote(b.getName()));
        }
        return "{\"adopted\":[" + adopted + "],\"adoptableBranches\":[" + branches + "]}";
    }

    private static MapUnit findUnit(GameInfo gi, Civilization c, int id) {
        Iterator<MapUnit> it = c.getUnits().getCivUnits().iterator();
        while (it.hasNext()) {
            MapUnit u = it.next();
            if (u.getId() == id) return u;
        }
        return null;
    }

    private static String actionsJson(GameInfo gi, Civilization c, int unitId) {
        MapUnit unit = findUnit(gi, c, unitId);
        if (unit == null) return err("没有这个单位: " + unitId);
        StringBuilder sb = new StringBuilder("{\"unit\":").append(quote("unit-" + unitId)).append(",\"actions\":[");
        Iterator<UnitAction> it = UnitActions.INSTANCE.getUnitActions(unit).iterator();
        boolean first = true;
        String truncated = null;
        try {
            while (it.hasNext()) {
                UnitAction a = it.next();
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"type\":").append(quote(a.getType().name()))
                  .append(",\"title\":").append(quote(a.getTitle()))
                  .append(",\"available\":").append(a.getAction() != null)
                  .append('}');
            }
        } catch (Throwable t) {
            truncated = t.getClass().getSimpleName();
        }
        sb.append("],\"truncated\":").append(truncated == null ? "null" : quote(truncated)).append('}');
        return sb.toString();
    }

    // ---------------- 新局 ----------------

    private static String newGameJson(int players, int humans, String difficulty, String speed, String civ, long seed) {
        GameParameters gp = new GameParameters();
        if (difficulty != null) gp.setDifficulty(difficulty);
        if (speed != null) gp.setSpeed(speed);
        gp.setNumberOfCityStates(Math.max(0, players - 1));
        ArrayList<Player> ps = new ArrayList<>();
        ps.add(new Player(civ == null ? "Random" : civ, PlayerType.Human, "agent0"));
        for (int i = 1; i < players; i++) {
            boolean isHuman = i < humans;
            ps.add(new Player("Random", isHuman ? PlayerType.Human : PlayerType.AI,
                    (isHuman ? "agent" : "ai") + i));
        }
        gp.setPlayers(ps);

        MapParameters mp = new MapParameters();
        mp.setMapSize(new MapSize(MapSize.Predefined.Small));
        if (seed != 0L) mp.setSeed(seed);

        GameInfo gi = GameStarter.Companion.startNewGame(new GameSetupInfo(gp, mp));
        gameInfo = gi;
        game.setGameInfo(gi);
        submitted.clear();
        return stateJson(gi, gi.getCurrentPlayerCiv());
    }
}
