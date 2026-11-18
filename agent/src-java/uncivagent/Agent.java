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

    /** 回合结束时把没被 capture_city 处理的占城按傀儡处理 */
    private static void resolveStaleCaptures(GameInfo gi, Civilization actor) {
        List<com.unciv.logic.civilization.PopupAlert> pending = new ArrayList<>();
        for (com.unciv.logic.civilization.PopupAlert a : actor.getPopupAlerts())
            if (a.getType() == com.unciv.logic.civilization.AlertType.CityConquered) pending.add(a);
        for (com.unciv.logic.civilization.PopupAlert a : pending) {
            City city = null;
            Iterator<City> it = gi.getCities().iterator();
            while (it.hasNext()) {
                City c = it.next();
                if (c.getId().equals(a.getValue())) city = c;
            }
            try {
                if (city != null && city.getCiv() != actor) city.puppetCity(actor);
            } catch (Throwable t) {
                System.out.println("[unciv-agent] 自动处理占城失败: " + t);
            }
            actor.getPopupAlerts().remove(a);
        }
    }

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isEmpty() ? d : v;
    }

    private static void boot() {
        game = new UncivGame(true);
        UncivGame.Companion.setCurrent(game);
        game.setSettings(new GameSettings());
        // 无头环境没有客户端那套 files 初始化，声音/存档辅助会碰 UncivGame.files。
        // 给一个真实的 UncivFiles 指向当前工作目录，找不到音频文件就静默跳过。
        try {
            if (com.badlogic.gdx.Gdx.files != null)
                game.setFiles(new com.unciv.logic.files.UncivFiles(
                        com.badlogic.gdx.Gdx.files, null));
        } catch (Throwable t) {
            System.out.println("[unciv-agent] files 初始化失败: " + t);
        }
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
        File sidecar = new File(path + ".submitted");
        if (sidecar.exists()) {
            try {
                for (String line : Files.readAllLines(sidecar.toPath(), StandardCharsets.UTF_8)) {
                    if (!line.trim().isEmpty()) submitted.add(line.trim());
                }
            } catch (IOException ignored) { }
        }
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
        if (token != null) {
            String civName = tokenToCiv.get(token);
            if (civName == null) return null;              // 未知 token，直接拒绝
            for (Civilization c : gi.getCivilizations()) {
                if (c.getCivName().equals(civName)) return c;
            }
            return null;                                    // token 指向的文明不在本局
        }
        if (!tokenToCiv.isEmpty()) return null;             // 多文明模式必须带 token
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
            // 有没处理的占城就按引擎默认的"傀儡"落地，避免城市卡在 0 血、旧主仍持有
            resolveStaleCaptures(gi, actor);
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
                boolean exists = false;
                for (Civilization c : need().getCivilizations()) {
                    if (c.getCivName().equals(civ)) exists = true;
                }
                if (!exists) {
                    StringBuilder names = new StringBuilder();
                    for (Civilization c : need().getCivilizations()) {
                        if (names.length() > 0) names.append(',');
                        names.append(quote(c.getCivName()));
                    }
                    send(ex, 404, "{\"error\":{\"code\":\"UNKNOWN_ID\",\"message\":"
                            + quote("本局没有这个文明: " + civ) + ",\"candidates\":[" + names + "]}}");
                    return;
                }
                String token = Json.str(body, "token");
                if (token == null) token = java.util.UUID.randomUUID().toString();
                tokenToCiv.put(token, civ);
                send(ex, 200, "{\"ok\":true,\"civ\":" + Kernel.q(civ) + ",\"token\":" + Kernel.q(token) + "}");
                return;
            }

            // ---- Agent 接口 ----
            Civilization actor = actorFor(ex);

            if (isAgentPath(path) && actor == null && !tokenToCiv.isEmpty()) {
                send(ex, 401, "{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"多文明模式必须带有效 token\"}}");
                return;
            }
            if (actor == null && isAgentPath(path)) {
                actor = need().getCurrentPlayerCiv();
            }

            if (path.equals("/intent") && method.equals("POST")) {
                send(ex, 200, Kernel.handleIntent(readBody(ex), need(), TURN, actor));
                return;
            }
            if (path.equals("/mcp") && method.equals("POST")) {
                String out = Kernel.handleMcp(readBody(ex), gameInfo, TURN, actor);
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
            if (path.equals("/trade")) { send(ex, 200, tradeOffersJson(need(), actor, query(ex, "civ"))); return; }
            if (path.equals("/religion")) { send(ex, 200, religionJson(need(), actor)); return; }
            if (path.equals("/citystates")) { send(ex, 200, cityStatesJson(need(), actor)); return; }
            if (path.equals("/spies")) { send(ex, 200, spiesJson(need(), actor)); return; }
            if (path.equals("/tile")) {
                send(ex, 200, tileJson(need(), actor, intq(ex, "x"), intq(ex, "y")));
                return;
            }
            if (path.startsWith("/city/")) {
                send(ex, 200, cityDetailsJson(need(), actor, path.substring(6)));
                return;
            }

            if (path.equals("/actions") && method.equals("GET")) {
                Integer u = intq(ex, "unit");
                if (u == null) { send(ex, 400, err("需要 ?unit=<id>")); return; }
                send(ex, 200, actionsJson(need(), actor, u));
                return;
            }

            // 兼容旧接口（内部转成 Intent）
            if (path.equals("/act") && method.equals("POST")) {
                Map<String, Object> b = Json.obj(Json.parse(readBody(ex)));
                String type = Json.str(b, "type");
                Integer unit = Json.integer(b, "unit");
                if (type == null) { send(ex, 400, err("需要 type")); return; }
                String id = Json.str(b, "id");
                String params = id == null ? "{}" : "{\"id\":" + Kernel.q(id) + "}";
                String intent = "{\"type\":" + Kernel.q(normalizeAction(type))
                        + ",\"target\":" + (unit == null ? "null" : Kernel.q("unit-" + unit))
                        + ",\"params\":" + params + "}";
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
                        Json.str(b, "civ"), seed == null ? 0L : seed.longValue(),
                        Json.str(b, "era"), Json.str(b, "size")));
                return;
            }
            if (path.equals("/load") && method.equals("POST")) {
                Map<String, Object> b = Json.obj(Json.parse(readBody(ex)));
                String p = Json.str(b, "path");
                if (p == null) { send(ex, 400, err("需要 path")); return; }
                if (!new File(p).exists()) {
                    send(ex, 404, "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":" + quote("存档不存在: " + p) + "}}");
                    return;
                }
                try {
                    loadSave(p);
                } catch (Throwable t) {
                    send(ex, 400, "{\"error\":{\"code\":\"BAD_SAVE\",\"message\":" + quote("读不出这个存档: " + t.getMessage()) + "}}");
                    return;
                }
                send(ex, 200, stateJson(need(), need().getCurrentPlayerCiv()));
                return;
            }
            if (path.equals("/save") && method.equals("POST")) {
                Map<String, Object> b = Json.obj(Json.parse(readBody(ex)));
                String p = Json.str(b, "path");
                if (p == null) { send(ex, 400, err("需要 path")); return; }
                FileConversions.INSTANCE.writeJson(new FileHandle(new File(p)), need(), false);
                try {
                    Files.write(new File(p + ".submitted").toPath(),
                            String.join("\n", submitted).getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) { }
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

    private static boolean isAgentPath(String path) {
        return path.equals("/intent") || path.equals("/state") || path.equals("/units")
                || path.equals("/cities") || path.equals("/map") || path.equals("/events")
                || path.equals("/diplomacy") || path.equals("/policies") || path.equals("/trade")
                || path.equals("/religion") || path.equals("/citystates") || path.equals("/spies")
                || path.equals("/tile")
                || path.startsWith("/city/") || path.equals("/actions")
                || path.equals("/act") || path.equals("/move") || path.equals("/endturn");
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
        if (v == null || v.trim().isEmpty()) return null;
        try {
            return Integer.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return null;   // 参数写错不该是 500，交给下游的默认值/校验
        }
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
        StringBuilder pending = new StringBuilder();
        for (com.unciv.logic.civilization.PopupAlert alert : c.getPopupAlerts()) {
            if (alert.getType() != com.unciv.logic.civilization.AlertType.CityConquered) continue;
            String cname = null;
            Iterator<City> cit = gi.getCities().iterator();
            while (cit.hasNext()) {
                City cc = cit.next();
                if (cc.getId().equals(alert.getValue())) cname = cc.getName();
            }
            if (pending.length() > 0) pending.append(',');
            pending.append("{\"city\":").append(quote("city-" + alert.getValue()))
                   .append(",\"name\":").append(cname == null ? "null" : quote(cname)).append('}');
        }
        String researching = c.getTech().currentTechnologyName();
        long mapSeed = 0L;
        try {
            mapSeed = gi.getTileMap().getMapParameters().getSeed();
        } catch (Throwable ignored) { }
        return "{\"turn\":" + gi.getTurns()
                + ",\"seed\":" + mapSeed
                + ",\"gameId\":" + quote(gi.getGameId())
                + ",\"civ\":" + quote(c.getCivName())
                + ",\"alive\":" + c.isAlive()
                + ",\"defeated\":" + c.isDefeated()
                + ",\"gold\":" + c.getGold()
                + ",\"researching\":" + quote(researching == null ? "" : researching)
                + ",\"techCount\":" + c.getTech().getTechsResearched().size()
                + ",\"stats\":{" + civStatsJson(c) + "}"
                + ",\"cities\":[" + cities + "]"
                + ",\"units\":[" + units + "]"
                + ",\"pendingCaptures\":[" + pending + "]}";
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
            StringBuilder promos = new StringBuilder();
            for (String promo : u.getPromotions().getPromotions()) {
                if (promos.length() > 0) promos.append(',');
                promos.append(quote(promo));
            }
            StringBuilder ups = new StringBuilder();
            try {
                for (com.unciv.logic.map.mapunit.PossibleUpgrade pu : u.getUpgrade().getUpgradesOfKind(false)) {
                    if (!pu.isShownToPlayer()) continue;
                    if (ups.length() > 0) ups.append(',');
                    ups.append(quote(pu.getUpgradedUnit().getName()));
                }
            } catch (Throwable ignored) { }
            sb.append("{\"id\":").append(quote("unit-" + v.getId()))
              .append(",\"name\":").append(quote(v.getName()))
              .append(",\"x\":").append(p.getX())
              .append(",\"y\":").append(p.getY())
              .append(",\"hp\":").append(v.getUnitHealth())
              .append(",\"moves\":").append(v.getCurrentMovement())
              .append(",\"maxMoves\":").append(v.getMaxMovement())
              .append(",\"canAttack\":").append(v.canAttack())
              .append(",\"military\":").append(u.isMilitary())
              .append(",\"civilian\":").append(u.isCivilian())
              .append(",\"embarked\":").append(u.isEmbarked())
              .append(",\"range\":").append(u.getRange())
              .append(",\"xp\":").append(u.getPromotions().getXP())
              .append(",\"canPromote\":").append(u.getPromotions().canBePromoted())
              .append(",\"promotions\":[").append(promos).append(']')
              .append(",\"canUpgradeTo\":[").append(ups).append(']')
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
        com.unciv.models.stats.Stats stats = target.getCityStats().getCurrentCityStats();
        return "{\"city\":" + quote("city-" + target.getId())
                + ",\"name\":" + quote(target.getName())
                + ",\"pop\":" + target.getPopulation().getPopulation()
                + ",\"at\":[" + target.getLocation().getX() + "," + target.getLocation().getY() + "]"
                + ",\"focus\":" + quote(target.getCityFocus().name())
                + ",\"production\":" + quote(target.getCityConstructions().getCurrentConstruction().getName())
                + ",\"queue\":[" + queue + "]"
                + ",\"workedTiles\":" + workedTilesJson(target)
                + ",\"stats\":{\"food\":" + round1(stats.getFood())
                + ",\"production\":" + round1(stats.getProduction())
                + ",\"gold\":" + round1(stats.getGold())
                + ",\"science\":" + round1(stats.getScience())
                + ",\"culture\":" + round1(stats.getCulture())
                + ",\"faith\":" + round1(stats.getFaith())
                + ",\"happiness\":" + round1(stats.getHappiness()) + "}"
                + ",\"health\":" + target.getHealth()
                + ",\"maxHealth\":" + target.getMaxHealth$unciv_core()
                + ",\"isPuppet\":" + target.isPuppet()
                + ",\"isCapital\":" + target.isCapital()
                + ",\"connected\":" + target.isConnectedToCapital()
                + ",\"avoidGrowth\":" + target.getAvoidGrowth()
                + ",\"inResistance\":" + target.isInResistance()
                + ",\"buildings\":" + buildingsJson(gi, target)
                + ",\"specialists\":" + counterJson(target.getPopulation().getSpecialistAllocations())
                + ",\"maxSpecialists\":" + counterJson(target.getPopulation().getMaxSpecialists())
                + ",\"manualSpecialists\":" + target.getManualSpecialists()
                + ",\"turnsToGrowth\":" + (target.getPopulation().getNumTurnsToNewPopulation() == null
                        ? "null" : target.getPopulation().getNumTurnsToNewPopulation())
                + ",\"foodToNextPop\":" + target.getPopulation().getFoodToNextPopulation()
                + ",\"religion\":{\"majority\":" + (target.getReligion().getMajorityReligionName() == null
                        ? "null" : quote(target.getReligion().getMajorityReligionName()))
                + ",\"holyCityOf\":" + (target.getReligion().getReligionThisIsTheHolyCityOf() == null
                        ? "null" : quote(target.getReligion().getReligionThisIsTheHolyCityOf())) + "}"
                + ",\"turnsToBuild\":" + turnsToBuild(target)
                + ",\"purchasable\":" + purchasableJson(gi, target)
                + ",\"buildable\":[" + buildable + "]}";
    }

    private static String civStatsJson(Civilization c) {
        StringBuilder sb = new StringBuilder();
        try {
            com.unciv.models.stats.Stats st = c.getStats().getStatsForNextTurn();
            sb.append("\"goldPerTurn\":").append(round1(st.getGold()))
              .append(",\"sciencePerTurn\":").append(round1(st.getScience()))
              .append(",\"culturePerTurn\":").append(round1(st.getCulture()))
              .append(",\"faithPerTurn\":").append(round1(st.getFaith()))
              .append(",\"productionPerTurn\":").append(round1(st.getProduction()))
              .append(",\"foodPerTurn\":").append(round1(st.getFood()));
            sb.append(",\"happiness\":").append(c.getStats().getHappiness());
            sb.append(",\"unitSupply\":").append(c.getStats().getUnitSupply());
        } catch (Throwable e) {
            sb.append("\"error\":").append(quote(String.valueOf(e)));
        }
        return sb.toString();
    }

    private static String round1(float v) {
        return String.valueOf(Math.round(v * 10f) / 10f);
    }

    private static String counterJson(com.unciv.models.Counter<String> c) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : c.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append(quote(e.getKey())).append(':').append(e.getValue());
        }
        return sb.append('}').toString();
    }

    private static String buildingsJson(GameInfo gi, City city) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String name : gi.getRuleset().getBuildings().keySet()) {
            boolean built;
            try {
                built = city.getCityConstructions().isBuilt(name);
            } catch (Throwable e) {
                continue;
            }
            if (!built) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append(quote(name));
        }
        return sb.append(']').toString();
    }

    private static String turnsToBuild(City city) {
        try {
            String item = city.getCityConstructions().getCurrentConstruction().getName();
            if (item == null || item.isEmpty() || item.equals("Nothing")) return "null";
            int remaining = city.getCityConstructions().getRemainingWork(item, false);
            float perTurn = city.getCityStats().getCurrentCityStats().getProduction();
            if (perTurn <= 0.01f) return "null";
            int turns = (int) Math.ceil(remaining / perTurn);
            return String.valueOf(Math.max(turns, 0));
        } catch (Throwable ignored) {
            return "null";
        }
    }

    /** 现在真的能买的项（含价格），省得 agent 反复试错 */
    private static String purchasableJson(GameInfo gi, City city) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        try {
            java.util.List<String> pool = new ArrayList<>();
            pool.addAll(gi.getRuleset().getBuildings().keySet());
            pool.addAll(gi.getRuleset().getUnits().keySet());
            int shown = 0;
            for (String name : pool) {
                if (shown >= 20) break;
                com.unciv.models.ruleset.IConstruction con = city.getCityConstructions().getConstruction$unciv_core(name);
                if (!(con instanceof com.unciv.models.ruleset.INonPerpetualConstruction)) continue;
                com.unciv.models.ruleset.INonPerpetualConstruction npc =
                        (com.unciv.models.ruleset.INonPerpetualConstruction) con;
                Integer gold = npc.getStatBuyCost(city, com.unciv.models.stats.Stat.Gold);
                Integer faith = npc.getStatBuyCost(city, com.unciv.models.stats.Stat.Faith);
                boolean goldOk = gold != null && city.getCityConstructions()
                        .isConstructionPurchaseAllowed(npc, com.unciv.models.stats.Stat.Gold, gold);
                boolean faithOk = faith != null && city.getCityConstructions()
                        .isConstructionPurchaseAllowed(npc, com.unciv.models.stats.Stat.Faith, faith);
                if (!goldOk && !faithOk) continue;
                shown++;
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"item\":").append(quote(name));
                if (goldOk) sb.append(",\"gold\":").append(gold);
                if (faithOk) sb.append(",\"faith\":").append(faith);
                sb.append('}');
            }
        } catch (Throwable ignored) { }
        return sb.append(']').toString();
    }

    private static String workedTilesJson(City city) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Tile t : Kernel.workedTilesOf(city)) {
            HexCoord p = t.getPosition();
            if (!first) sb.append(',');
            first = false;
            sb.append('[').append(p.getX()).append(',').append(p.getY()).append(']');
        }
        return sb.append(']').toString();
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
                Tile t = Kernel.tileAt(gi, centerX + dx, centerY + dy);
                if (t == null || !t.isExplored(c)) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"x\":").append(t.getPosition().getX())
                  .append(",\"y\":").append(t.getPosition().getY())
                  .append(",\"terrain\":").append(quote(t.baseTerrain))
                  .append(",\"visible\":").append(t.isVisible(c));
                String res = t.getResource();
                if (res != null) sb.append(",\"resource\":").append(quote(res));
                Civilization owner = t.getOwner();
                if (owner != null) sb.append(",\"owner\":").append(quote(owner.getCivName()));
                try {
                    City tc = t.isCityCenter() ? t.getCity() : null;
                    if (tc != null) sb.append(",\"city\":{\"id\":").append(quote("city-" + tc.getId()))
                            .append(",\"name\":").append(quote(tc.getName()))
                            .append(",\"owner\":").append(quote(tc.getCiv().getCivName()))
                            .append(",\"pop\":").append(tc.getPopulation().getPopulation())
                            .append(",\"hp\":").append(tc.getHealth()).append('}');
                } catch (Throwable ignored) { }
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

    static String victoryJson(GameInfo gi, Civilization c) {
        StringBuilder sb = new StringBuilder("{\"turn\":").append(gi.getTurns()).append(",\"victories\":[");
        boolean first = true;
        try {
            for (com.unciv.models.ruleset.Victory v : gi.getRuleset().getVictories().values()) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"name\":").append(quote(v.getName()))
                  .append(",\"enabled\":").append(gi.getGameParameters().getVictoryTypes().contains(v.getName()))
                  .append('}');
            }
        } catch (Throwable t) {
            return "{\"error\":{\"code\":\"INTERNAL\",\"message\":" + quote(String.valueOf(t)) + "}}";
        }
        return sb.append("]}").toString();
    }

    static String tileJson(GameInfo gi, Civilization c, Integer x, Integer y) {
        if (x == null || y == null) return "{\"error\":{\"code\":\"BAD_PARAMS\",\"message\":\"需要 x 与 y\"}}";
        Tile t = Kernel.tileAt(gi, x, y);
        if (t == null) return "{\"error\":{\"code\":\"UNKNOWN_ID\",\"message\":" + quote("没有这个格子") + "}}";
        StringBuilder feat = new StringBuilder();
        for (String f : t.getTerrainFeatures()) {
            if (feat.length() > 0) feat.append(',');
            feat.append(quote(f));
        }
        StringBuilder cityJson = new StringBuilder("null");
        try {
            // getCity() 返回的是"拥有这块地的城"，任何领土格都非空；只有中心格才是那座城
            City tc = t.isCityCenter() ? t.getCity() : null;
            if (tc != null) {
                cityJson = new StringBuilder("{\"id\":").append(quote("city-" + tc.getId()))
                        .append(",\"name\":").append(quote(tc.getName()))
                        .append(",\"owner\":").append(quote(tc.getCiv().getCivName()))
                        .append(",\"pop\":").append(tc.getPopulation().getPopulation())
                        .append(",\"hp\":").append(tc.getHealth())
                        .append('}');
            }
        } catch (Throwable ignored) { }
        return "{\"x\":" + t.getPosition().getX() + ",\"y\":" + t.getPosition().getY()
                + ",\"terrain\":" + quote(t.baseTerrain)
                + ",\"features\":[" + feat + "]"
                + ",\"explored\":" + t.isExplored(c)
                + ",\"visible\":" + t.isVisible(c)
                + ",\"resource\":" + (t.getResource() == null ? "null" : quote(t.getResource()))
                + ",\"improvement\":" + (t.getImprovement() == null ? "null" : quote(t.getImprovement()))
                + ",\"city\":" + cityJson
                + "}";
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

    static String tradeOffersJson(GameInfo gi, Civilization c, String withCiv) {
        StringBuilder pending = new StringBuilder();
        for (com.unciv.logic.trade.TradeRequest r : c.getTradeRequests()) {
            Civilization from = gi.getCivilization(r.getRequestingCiv());
            if (pending.length() > 0) pending.append(',');
            pending.append("{\"civ\":").append(quote(from == null ? r.getRequestingCiv() : from.getCivName()))
                   .append(",\"give\":").append(Kernel.offersJson(r.getTrade().getTheirOffers()))
                   .append(",\"want\":").append(Kernel.offersJson(r.getTrade().getOurOffers()))
                   .append('}');
        }
        StringBuilder sb = new StringBuilder("{\"pending\":[").append(pending).append(']');
        if (withCiv != null) {
            Civilization other = null;
            for (Civilization x : gi.getCivilizations()) {
                if (x == c) continue;
                if (x.getCivName().equals(withCiv) || x.getCivID().equals(withCiv)) other = x;
            }
            if (other == null) {
                return "{\"error\":{\"code\":\"UNKNOWN_ID\",\"message\":" + quote("没有这个文明: " + withCiv) + "}}";
            }
            com.unciv.logic.trade.TradeLogic tl = new com.unciv.logic.trade.TradeLogic(c, other);
            sb.append(",\"with\":").append(quote(other.getCivName()))
              .append(",\"youCanGive\":").append(Kernel.offersJson(tl.getOurAvailableOffers()))
              .append(",\"theyCanGive\":").append(Kernel.offersJson(tl.getTheirAvailableOffers()));
        }
        return sb.append('}').toString();
    }

    static String religionJson(GameInfo gi, Civilization c) {
        if (!gi.isReligionEnabled()) return "{\"enabled\":false}";
        com.unciv.logic.civilization.managers.ReligionManager rm = c.getReligionManager();
        com.unciv.models.Religion rel = rm.getReligion();

        StringBuilder beliefs = new StringBuilder();
        if (rel != null) {
            Iterator<com.unciv.models.ruleset.Belief> it = rel.getAllBeliefsOrdered().iterator();
            while (it.hasNext()) {
                com.unciv.models.ruleset.Belief b = it.next();
                if (beliefs.length() > 0) beliefs.append(',');
                beliefs.append("{\"name\":").append(quote(b.getName()))
                       .append(",\"type\":").append(quote(b.getType().name())).append('}');
            }
        }

        java.util.HashSet<String> used = new java.util.HashSet<>();
        for (com.unciv.models.Religion r : gi.getReligions().values()) {
            Iterator<com.unciv.models.ruleset.Belief> it = r.getAllBeliefsOrdered().iterator();
            while (it.hasNext()) used.add(it.next().getName());
        }
        StringBuilder available = new StringBuilder();
        for (Map.Entry<String, com.unciv.models.ruleset.Belief> e : gi.getRuleset().getBeliefs().entrySet()) {
            if (used.contains(e.getKey())) continue;
            if (available.length() > 0) available.append(',');
            available.append("{\"name\":").append(quote(e.getKey()))
                     .append(",\"type\":").append(quote(e.getValue().getType().name())).append('}');
        }

        StringBuilder must = new StringBuilder("{");
        try {
            com.unciv.logic.civilization.managers.ReligionState state = rm.getReligionState();
            com.unciv.models.Counter<com.unciv.models.ruleset.BeliefType> cnt;
            if (state == com.unciv.logic.civilization.managers.ReligionState.None
                    || state == com.unciv.logic.civilization.managers.ReligionState.Pantheon) {
                must.append("\"Pantheon\":1");
            } else {
                cnt = state == com.unciv.logic.civilization.managers.ReligionState.EnhancingReligion
                        ? rm.getBeliefsToChooseAtEnhancing() : rm.getBeliefsToChooseAtFounding();
                boolean first = true;
                for (Map.Entry<com.unciv.models.ruleset.BeliefType, Integer> e : cnt.entrySet()) {
                    if (e.getValue() == null || e.getValue() <= 0) continue;
                    if (!first) must.append(',');
                    first = false;
                    must.append(quote(e.getKey().name())).append(':').append(e.getValue());
                }
            }
        } catch (Throwable ignored) { }
        must.append('}');

        return "{\"enabled\":true,\"state\":" + quote(rm.getReligionState().name())
                + ",\"faith\":" + rm.getStoredFaith()
                + ",\"faithForPantheon\":" + rm.faithForPantheon(0)
                + ",\"religion\":" + (rel == null ? "null"
                        : quote(rel.getDisplayName() == null ? rel.getName() : rel.getDisplayName()))
                + ",\"beliefs\":[" + beliefs + "]"
                + ",\"availableBeliefs\":[" + available + "]"
                + ",\"mustChoose\":" + must + "}";
    }

    static String cityStatesJson(GameInfo gi, Civilization c) {
        StringBuilder sb = new StringBuilder("{\"cityStates\":[");
        boolean first = true;
        for (Civilization cs : gi.getCivilizations()) {
            if (!cs.isCityState() || !c.knows(cs)) continue;
            if (!first) sb.append(',');
            first = false;

            com.unciv.logic.civilization.diplomacy.DiplomacyManager dm = cs.getDiplomacyManager(c);
            int influence = dm == null ? 0 : (int) dm.getInfluence();
            Civilization ally = cs.getAllyCiv();

            sb.append("{\"civ\":").append(quote(cs.getCivName()))
              .append(",\"influence\":").append(influence)
              .append(",\"ally\":").append(ally == null ? "null" : quote(ally.getCivName()))
              .append(",\"atWar\":").append(c.isAtWarWith(cs))
              .append(",\"canPledgeProtection\":").append(cs.getCityStateFunctions().otherCivCanPledgeProtection(c))
              .append(",\"giftInfluence\":[");
            int[] tiers = {250, 500, 1000};
            for (int i = 0; i < tiers.length; i++) {
                if (i > 0) sb.append(',');
                int gain = cs.getCityStateFunctions().influenceGainedByGift(c, tiers[i]);
                sb.append("{\"gold\":").append(tiers[i]).append(",\"influence\":").append(gain).append('}');
            }
            sb.append("],\"quests\":[");
            boolean firstQuest = true;
            try {
                Iterator<com.unciv.logic.civilization.managers.quests.AssignedQuest> it =
                        cs.getQuestManager().getAssignedQuestsFor(c).iterator();
                while (it.hasNext()) {
                    com.unciv.logic.civilization.managers.quests.AssignedQuest aq = it.next();
                    if (!firstQuest) sb.append(',');
                    firstQuest = false;
                    sb.append("{\"name\":").append(quote(aq.getQuestName()))
                      .append(",\"description\":").append(quote(aq.getDescription()))
                      .append(",\"influence\":").append((int) aq.getInfluence())
                      .append(",\"turnsLeft\":").append(aq.doesExpire() ? aq.getRemainingTurns() : -1)
                      .append('}');
                }
            } catch (Throwable ignored) { }
            sb.append("]}");
        }
        return sb.append("]}").toString();
    }

    static String spiesJson(GameInfo gi, Civilization c) {
        StringBuilder sb = new StringBuilder("{\"spies\":[");
        boolean first = true;
        for (com.unciv.models.Spy s : c.getEspionageManager().getSpyList()) {
            if (!first) sb.append(',');
            first = false;
            City loc = s.getCityOrNull();
            sb.append("{\"name\":").append(quote(s.getName()))
              .append(",\"rank\":").append(s.getRank())
              .append(",\"action\":").append(quote(s.getAction().name()))
              .append(",\"turnsRemaining\":").append(s.getTurnsRemainingForAction())
              .append(",\"city\":").append(loc == null ? "null" : quote(loc.getName()))
              .append(",\"cityId\":").append(loc == null ? "null" : quote("city-" + loc.getId()))
              .append(",\"setUp\":").append(s.isSetUp()).append('}');
        }
        return sb.append("]}").toString();
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

    private static java.util.Collection<com.unciv.models.ruleset.nation.Nation> gi_nations() {
        try {
            return com.unciv.models.ruleset.RulesetCache.INSTANCE.getVanillaRuleset().getNations().values();
        } catch (Throwable ignored) { }
        return java.util.Collections.emptyList();
    }

    private static java.util.List<String> gi_eras(GameParameters gp) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            for (com.unciv.models.ruleset.tech.Era e : com.unciv.UncivGame.Current.getGameInfo().getRuleset().getEras().values())
                out.add(e.getName());
        } catch (Throwable ignored) { }
        if (out.isEmpty()) {
            for (String s : new String[]{"Ancient era","Classical era","Medieval era","Renaissance era",
                    "Industrial era","Modern era","Atomic era","Information era","Future era"}) out.add(s);
        }
        return out;
    }

    private static String newGameJson(int players, int humans, String difficulty, String speed, String civ, long seed, String era, String size) {
        GameParameters gp = new GameParameters();
        if (difficulty != null) gp.setDifficulty(difficulty);
        if (speed != null) gp.setSpeed(speed);
        if (era != null) {
            String match = null;
            for (String name : gi_eras(gp)) if (name.equalsIgnoreCase(era)) match = name;
            if (match == null) return "{\"error\":{\"code\":\"UNKNOWN_ID\",\"message\":" + quote("没有这个时代: " + era) + "}}";
            gp.setStartingEra(match);
        }
        gp.setNumberOfCityStates(Math.max(0, players - 1));
        ArrayList<Player> ps = new ArrayList<>();
        String humanNation = civ;
        if (humanNation == null || humanNation.equalsIgnoreCase("Random")) {
            // 同一个 seed 必须给同一个文明，否则复现不了
            ArrayList<String> majors = new ArrayList<>();
            try {
                for (com.unciv.models.ruleset.nation.Nation n : gi_nations()) {
                    if (!n.isCityState() && !n.isBarbarian() && !n.isSpectator()) majors.add(n.getName());
                }
            } catch (Throwable ignored) { }
            if (majors.isEmpty()) humanNation = "Random";
            else {
                java.util.Collections.sort(majors);
                long s2 = seed == 0L ? 1L : Math.abs(seed);
                humanNation = majors.get((int) (s2 % majors.size()));
            }
        }
        ps.add(new Player(humanNation, PlayerType.Human, "agent0"));
        for (int i = 1; i < players; i++) {
            boolean isHuman = i < humans;
            ps.add(new Player("Random", isHuman ? PlayerType.Human : PlayerType.AI,
                    (isHuman ? "agent" : "ai") + i));
        }
        gp.setPlayers(ps);

        MapParameters mp = new MapParameters();
        MapSize.Predefined preset = MapSize.Predefined.Small;
        if (size != null) {
            for (MapSize.Predefined p : MapSize.Predefined.values())
                if (p.name().equalsIgnoreCase(size)) preset = p;
        }
        mp.setMapSize(new MapSize(preset));
        if (seed != 0L) mp.setSeed(seed);

        GameInfo gi = GameStarter.Companion.startNewGame(new GameSetupInfo(gp, mp));
        // 默认把所有胜利条件打开（GameParameters 默认是空列表，等于全关）
        try {
            gi.getGameParameters().getVictoryTypes().clear();
            gi.getGameParameters().getVictoryTypes().addAll(gi.getRuleset().getVictories().keySet());
        } catch (Throwable ignored) { }
        gameInfo = gi;
        game.setGameInfo(gi);
        submitted.clear();
        tokenToCiv.clear();   // 旧 token 不能跨局复用
        return stateJson(gi, gi.getCurrentPlayerCiv());
    }
}
