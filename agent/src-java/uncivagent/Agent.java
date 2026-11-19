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
        loadGuide();
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
        try {
            gi.getCurrentPlayerCiv().updateStatsForNextTurn();
        } catch (Throwable ignored) { }
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
            Trace.begin(method, path, ex.getRequestURI().getRawQuery());

            // ---- 官方客户端协议 ----
            if (path.equals("/isalive")) { send(ex, 200, "{\"online\":true}"); return; }
            if (path.equals("/auth")) { handleAuth(ex, method); return; }
            if (path.startsWith("/files/")) { handleFiles(ex, path.substring(7), method); return; }

            // ---- 探活与管理 ----
            if (path.equals("/health")) { send(ex, 200, "{\"ok\":true}"); return; }
            if (path.equals("/tools")) { send(ex, 200, toolsJson()); return; }
            if (path.equals("/guide")) {
                send(ex, 200, guideJson(query(ex, "name"), query(ex, "category")));
                return;
            }

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
            if (path.equals("/techs")) { send(ex, 200, techsJson(need(), actor)); return; }
            if (path.equals("/briefing")) { send(ex, 200, briefingJson(need(), actor)); return; }
            if (path.equals("/stats")) { send(ex, 200, statsJson(need(), actor)); return; }
            if (path.equals("/resources")) { send(ex, 200, resourcesJson(need(), actor)); return; }
            if (path.equals("/preview_move")) {
                send(ex, 200, previewMoveJson(need(), actor, query(ex, "unit"),
                        intq(ex, "x"), intq(ex, "y")));
                return;
            }
            if (path.equals("/reachable")) {
                send(ex, 200, reachableJson(need(), actor, query(ex, "unit")));
                return;
            }
            if (path.equals("/targets")) {
                send(ex, 200, targetsJson(need(), actor, query(ex, "unit")));
                return;
            }
            if (path.equals("/preview_attack")) {
                send(ex, 200, previewAttackJson(need(), actor, query(ex, "unit"), query(ex, "city"),
                        intq(ex, "x"), intq(ex, "y")));
                return;
            }
            if (path.equals("/victory")) { send(ex, 200, victoryJson(need(), actor)); return; }
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
                || path.equals("/techs") || path.equals("/victory") || path.equals("/briefing")
                || path.equals("/preview_attack") || path.equals("/targets")
                || path.equals("/preview_move") || path.equals("/reachable")
                || path.equals("/stats") || path.equals("/resources")
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

    /** 逐请求轨迹：UNCIV_AGENT_TRACE=<file> 打开，一行一个 JSON，够复盘整局。
     *  记的是接口层，不依赖模型自己写报告。 */
    static final class Trace {
        static final String PATH = System.getenv("UNCIV_AGENT_TRACE");
        static final boolean ON = PATH != null && !PATH.isEmpty();
        private static final ThreadLocal<StringBuilder> CUR = new ThreadLocal<>();
        private static final ThreadLocal<Long> T0 = new ThreadLocal<>();

        static void begin(String method, String path, String query) {
            if (!ON) return;
            T0.set(System.currentTimeMillis());
            StringBuilder sb = new StringBuilder("{\"ts\":").append(System.currentTimeMillis())
                    .append(",\"method\":").append(quote(method))
                    .append(",\"path\":").append(quote(path));
            if (query != null && !query.isEmpty()) sb.append(",\"query\":").append(quote(query));
            CUR.set(sb);
        }

        static void body(String body) {
            if (!ON) return;
            StringBuilder sb = CUR.get();
            if (sb == null || body == null || body.isEmpty()) return;
            sb.append(",\"body\":").append(quote(body.length() > 4000 ? body.substring(0, 4000) : body));
        }

        static void end(int status, String response) {
            if (!ON) return;
            StringBuilder sb = CUR.get();
            if (sb == null) return;
            CUR.remove();
            Long t0 = T0.get();
            T0.remove();
            int turn = -1;
            try { turn = gameInfo == null ? -1 : gameInfo.getTurns(); } catch (Throwable ignored) { }
            sb.append(",\"turn\":").append(turn < 0 ? "null" : String.valueOf(turn))
              .append(",\"status\":").append(status)
              .append(",\"bytes\":").append(response == null ? 0
                      : response.getBytes(StandardCharsets.UTF_8).length);
            if (t0 != null) sb.append(",\"ms\":").append(System.currentTimeMillis() - t0);
            sb.append("}\n");
            synchronized (Trace.class) {
                try (java.io.FileWriter fw = new java.io.FileWriter(PATH, true)) {
                    fw.write(sb.toString());
                } catch (Throwable ignored) { }
            }
        }
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        Trace.end(code, body);
    }

    private static String readBody(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Trace.body(body);
        return body;
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
                  .append(",\"production\":").append(productionJson(city))
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
        String difficulty = null;
        String speed = null;
        try {
            difficulty = gi.getGameParameters().getDifficulty();
            speed = gi.getGameParameters().getSpeed();
        } catch (Throwable ignored) { }
        return "{\"turn\":" + gi.getTurns()
                + ",\"difficulty\":" + (difficulty == null ? "null" : quote(difficulty))
                + ",\"speed\":" + (speed == null ? "null" : quote(speed))
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
              .append(",\"availablePromotions\":" + availablePromotions(u))
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
              .append(",\"production\":").append(productionJson(city))
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
                + ",\"production\":" + productionJson(target)
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
                + ",\"remainingWork\":" + remainingWork(target)
                + ",\"defense\":" + cityDefense(target)
                + ",\"garrison\":" + (target.getGarrison() == null ? "null" : quote(target.getGarrison().getName()))
                + ",\"canBombard\":" + target.canBombard()
                + ",\"purchasable\":" + purchasableJson(gi, target)
                + ",\"buildable\":[" + buildable + "]}";
    }

    private static String tileYields(Tile t, Civilization c) {
        try {
            com.unciv.models.stats.Stats st = new com.unciv.logic.map.tile.TileStatFunctions(t).getTileStats(c);
            return "{\"food\":" + round1(st.getFood())
                    + ",\"production\":" + round1(st.getProduction())
                    + ",\"gold\":" + round1(st.getGold())
                    + ",\"science\":" + round1(st.getScience())
                    + ",\"culture\":" + round1(st.getCulture())
                    + ",\"faith\":" + round1(st.getFaith()) + "}";
        } catch (Throwable e) {
            return "null";
        }
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

    private static String availablePromotions(MapUnit u) {
        StringBuilder sb = new StringBuilder("[");
        try {
            if (u.getPromotions().canBePromoted()) {
                boolean first = true;
                Iterator<com.unciv.models.ruleset.unit.Promotion> it =
                        u.getPromotions().getAvailablePromotions().iterator();
                while (it.hasNext()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append(quote(it.next().getName()));
                }
            }
        } catch (Throwable ignored) { }
        return sb.append(']').toString();
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

    private static String remainingWork(City city) {
        try {
            String item = city.getCityConstructions().getCurrentConstruction().getName();
            if (item == null || item.isEmpty() || item.equals("Nothing")) return "null";
            return String.valueOf(city.getCityConstructions().getRemainingWork(item, false));
        } catch (Throwable ignored) {
            return "null";
        }
    }

    private static String cityDefense(City city) {
        try {
            return String.valueOf(new com.unciv.logic.battle.CityCombatant(city)
                    .getCityStrength(null, com.unciv.logic.battle.CombatAction.Defend));
        } catch (Throwable ignored) {
            return "null";
        }
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
                StringBuilder mf = new StringBuilder();
                for (String f : t.getTerrainFeatures()) {
                    if (mf.length() > 0) mf.append(',');
                    mf.append(quote(f));
                }
                sb.append(",\"features\":[").append(mf).append(']');
                String res = t.getResource();
                if (res != null) sb.append(",\"resource\":").append(quote(res));
                // 设施与路的归属：/map 少了这个，工人会对着已有 Farm 的地反复开工
                if (t.getImprovement() != null)
                    sb.append(",\"improvement\":").append(quote(t.getImprovement()));
                String wip = t.getImprovementInProgress();
                if (wip != null) sb.append(",\"improvementInProgress\":").append(quote(wip))
                        .append(",\"turnsToImprovement\":").append(t.getTurnsToImprovement());
                if (t.getRoadStatus() != null && t.getRoadStatus().name().equals("Road"))
                    sb.append(",\"road\":true");
                else if (t.getRoadStatus() != null && t.getRoadStatus().name().equals("Railroad"))
                    sb.append(",\"railroad\":true");
                Civilization owner = t.getOwner();
                if (owner != null) sb.append(",\"owner\":").append(quote(owner.getCivName()));
                try {
                    StringBuilder us = new StringBuilder();
                    Iterator<MapUnit> uit = t.getUnits().iterator();
                    while (uit.hasNext()) {
                        MapUnit mu = uit.next();
                        boolean own = mu.getCiv() == c;
                        if (us.length() > 0) us.append(',');
                        us.append("{\"id\":").append(quote("unit-" + mu.getId()))
                          .append(",\"own\":").append(own)
                          .append(",\"owner\":").append(quote(mu.getCiv().getCivName()))
                          .append(",\"name\":").append(quote(mu.getName()))
                          .append(",\"hp\":").append(mu.getHealth())
                          .append(",\"military\":").append(mu.isMilitary()).append('}');
                    }
                    if (us.length() > 0) sb.append(",\"units\":[").append(us).append(']');
                } catch (Throwable ignored) { }
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
                + ",\"yields\":" + tileYields(t, c)
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
              .append(",\"isCityState\":").append(other.isCityState())
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
        StringBuilder tree = new StringBuilder();
        boolean firstBranch = true;
        try {
            for (com.unciv.models.ruleset.PolicyBranch b : c.getPolicies().getAdoptableBranches()) {
                if (branches.length() > 0) branches.append(',');
                branches.append(quote(b.getName()));
            }
            for (com.unciv.models.ruleset.PolicyBranch b : gi.getRuleset().getPolicyBranches().values()) {
                if (!firstBranch) tree.append(',');
                firstBranch = false;
                tree.append("{\"branch\":").append(quote(b.getName())).append(",\"policies\":[");
                boolean firstPolicy = true;
                for (com.unciv.models.ruleset.Policy p : b.getPolicies()) {
                    if (!firstPolicy) tree.append(',');
                    firstPolicy = false;
                    boolean isAdopted = c.getPolicies().isAdopted(p.getName());
                    boolean requiresMet = true;
                    for (String req : p.getRequires())
                        if (!c.getPolicies().isAdopted(req)) requiresMet = false;
                    tree.append("{\"name\":").append(quote(p.getName()))
                        .append(",\"adopted\":").append(isAdopted)
                        .append(",\"requires\":[").append(joinQuoted(p.getRequires())).append(']')
                        .append(",\"ready\":").append(!isAdopted && requiresMet)
                        .append('}');
                }
                tree.append("]}");
            }
        } catch (Throwable ignored) { }
        int culture = 0;
        int nextCost = 0;
        try {
            culture = c.getPolicies().getCultureNeededForNextPolicy();  // 其实是"还差多少"
            nextCost = culture;
        } catch (Throwable ignored) { }
        long stored = 0;
        try {
            stored = c.getPolicies().getStoredCulture();
        } catch (Throwable ignored) { }
        return "{\"adopted\":[" + adopted + "]"
                + ",\"culture\":" + stored
                + ",\"cultureNeededForNextPolicy\":" + nextCost
                + ",\"canAdoptPolicy\":" + c.getPolicies().canAdoptPolicy()
                + ",\"adoptableBranches\":[" + branches + "]"
                + ",\"tree\":[" + tree + "]}";
    }

    static String joinQuoted(java.util.Collection<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) sb.append(',');
            sb.append(quote(v));
        }
        return sb.toString();
    }

    static String tradeOffersJson(GameInfo gi, Civilization c, String withCiv) {
        StringBuilder pending = new StringBuilder();
        for (com.unciv.logic.trade.TradeRequest r : c.getTradeRequests()) {
            Civilization from = gi.getCivilization(r.getRequestingCiv());
            if (pending.length() > 0) pending.append(',');
            pending.append("{\"civ\":").append(quote(from == null ? r.getRequestingCiv() : from.getCivName()))
                   .append(",\"proposedByMe\":").append(c.getCivID().equals(r.getRequestingCiv()))
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

    /**
     * 一回合的完整简报：把客户端会呈现给玩家的东西合成一份，agent 每回合读这一条就够。
     * 只讲事实，不给建议。
     */
    static String briefingJson(GameInfo gi, Civilization c) {
        StringBuilder sb = new StringBuilder("{\"turn\":").append(gi.getTurns())
                .append(",\"civ\":").append(quote(c.getCivName()));
        try {
            sb.append(",\"era\":").append(quote(c.getEra().getName()));
        } catch (Throwable ignored) { sb.append(",\"era\":null"); }

        int gold = c.getGold();
        float gpt = 0f, sci = 0f, cult = 0f, faith = 0f;
        int happiness = 0;
        try {
            com.unciv.models.stats.Stats st = c.getStats().getStatsForNextTurn();
            gpt = st.getGold(); sci = st.getScience(); cult = st.getCulture(); faith = st.getFaith();
            happiness = c.getStats().getHappiness();
        } catch (Throwable ignored) { }
        try {
            sb.append(",\"difficulty\":").append(quote(gi.getGameParameters().getDifficulty()))
              .append(",\"speed\":").append(quote(gi.getGameParameters().getSpeed()));
        } catch (Throwable ignored) { }
        sb.append(",\"gold\":").append(gold)
          .append(",\"goldPerTurn\":").append(round1(gpt))
          .append(",\"sciencePerTurn\":").append(round1(sci))
          .append(",\"culture\":").append(c.getPolicies().getStoredCulture())
          .append(",\"culturePerTurn\":").append(round1(cult))
          .append(",\"cultureForNextPolicy\":").append(c.getPolicies().getCultureNeededForNextPolicy())
          .append(",\"faith\":").append(c.getReligionManager().getStoredFaith())
          .append(",\"faithPerTurn\":").append(round1(faith))
          .append(",\"happiness\":").append(happiness);

        // 研究
        String cur = c.getTech().currentTechnologyName();
        int available = 0;
        try {
            for (String t : gi.getRuleset().getTechnologies().keySet())
                if (c.getTech().canBeResearched(t)) available++;
        } catch (Throwable ignored) { }
        sb.append(",\"research\":{\"current\":").append(cur == null || cur.isEmpty() ? "null" : quote(cur))
          .append(",\"turnsLeft\":");
        if (cur == null || cur.isEmpty() || sci <= 0.01f) sb.append("null");
        else {
            try {
                sb.append((int) Math.ceil(Math.max(0, c.getTech().remainingScienceToTech(cur)) / sci));
            } catch (Throwable e) { sb.append("null"); }
        }
        sb.append(",\"available\":").append(available).append('}');

        // 城市
        sb.append(",\"cities\":[");
        boolean firstCity = true;
        for (City city : c.getCities()) {
            if (!firstCity) sb.append(',');
            firstCity = false;
            String prod;
            Integer turns = null;
            try {
                prod = city.getCityConstructions().getCurrentConstruction().getName();
                if (prod != null && !prod.isEmpty() && !prod.equals("Nothing")) {
                    float perTurn = city.getCityStats().getCurrentCityStats().getProduction();
                    int remaining = city.getCityConstructions().getRemainingWork(prod, false);
                    turns = perTurn <= 0.01f ? null : (int) Math.ceil(remaining / perTurn);
                } else prod = null;
            } catch (Throwable e) { prod = null; }
            boolean underAttack = false;
            try {
                Iterator<Tile> nit = city.getCenterTile().getNeighbors().iterator();
                while (nit.hasNext()) {
                    if (hasForeignMilitary(nit.next(), c)) { underAttack = true; break; }
                }
            } catch (Throwable ignored) { }
            sb.append("{\"id\":").append(quote("city-" + city.getId()))
              .append(",\"name\":").append(quote(city.getName()))
              .append(",\"pop\":").append(city.getPopulation().getPopulation())
              .append(",\"at\":[").append(city.getLocation().getX()).append(',').append(city.getLocation().getY()).append(']')
              .append(",\"foodPerTurn\":").append(round1(city.getCityStats().getCurrentCityStats().getFood()))
              .append(",\"turnsToGrowth\":").append(city.getPopulation().getNumTurnsToNewPopulation() == null
                      ? "null" : city.getPopulation().getNumTurnsToNewPopulation())
              .append(",\"production\":").append(prod == null ? "null" : quote(prod))
              .append(",\"turnsToBuild\":").append(turns == null ? "null" : turns)
              .append(",\"isPuppet\":").append(city.isPuppet())
              .append(",\"inResistance\":").append(city.isInResistance())
              .append(",\"health\":").append(city.getHealth())
              .append(",\"canBombard\":").append(city.canBombard())
              .append(",\"enemyAdjacent\":").append(underAttack)
              .append('}');
        }
        sb.append(']');

        // 需要操作的单位
        sb.append(",\"unitsNeedingOrders\":[");
        boolean firstUnit = true;
        Iterator<MapUnit> uit = c.getUnits().getCivUnits().iterator();
        while (uit.hasNext()) {
            MapUnit u = uit.next();
            if (u.getCurrentMovement() <= 0.1f) continue;
            if (!firstUnit) sb.append(',');
            firstUnit = false;
            HexCoord p = u.getTile().getPosition();
            sb.append("{\"id\":").append(quote("unit-" + u.getId()))
              .append(",\"name\":").append(quote(u.getName()))
              .append(",\"x\":").append(p.getX()).append(",\"y\":").append(p.getY())
              .append(",\"moves\":").append(u.getCurrentMovement())
              .append(",\"military\":").append(u.isMilitary())
              .append(",\"civilian\":").append(u.isCivilian())
              .append(",\"canAttack\":").append(u.canAttack())
              .append(",\"canPromote\":").append(u.getPromotions().canBePromoted());
            // 工人：这格能造什么，别让模型自己翻规则
            if (u.getCache().getHasUniqueToBuildImprovements()) {
                sb.append(",\"canBuild\":[");
                int n = 0;
                for (com.unciv.models.ruleset.tile.TileImprovement ti
                        : u.getTile().getRuleset().getTileImprovements().values()) {
                    boolean ok = false;
                    try { ok = u.canBuildImprovement(ti, u.getTile()); } catch (Throwable ignored) { }
                    if (!ok) continue;
                    if (n++ > 0) sb.append(',');
                    sb.append(quote(ti.getName()));
                    if (n >= 8) break;
                }
                sb.append(']');
                String wip = u.getTile().getImprovementInProgress();
                sb.append(",\"workingOn\":").append(wip == null ? "null" : quote(wip))
                  .append(",\"turnsLeft\":").append(u.getTile().getTurnsToImprovement());
            }
            // 军事：这回合打得到谁
            if (u.canAttack()) {
                sb.append(",\"attackable\":[");
                try {
                    com.unciv.logic.map.mapunit.movement.PathsToTilesWithinTurn paths =
                            u.getMovement().getDistanceToTiles(true, null, null, true);
                    ArrayList<com.unciv.logic.battle.AttackableTile> list =
                            com.unciv.logic.battle.TargetHelper.INSTANCE.getAttackableEnemies(u, paths, null, false);
                    java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
                    int n = 0;
                    for (com.unciv.logic.battle.AttackableTile at : list) {
                        Tile t = at.getTileToAttack();
                        String key = t.getPosition().getX() + "," + t.getPosition().getY();
                        if (!seen.add(key)) continue;
                        if (n++ > 0) sb.append(',');
                        sb.append("{\"x\":").append(t.getPosition().getX())
                          .append(",\"y\":").append(t.getPosition().getY())
                          .append(",\"type\":").append(quote(t.isCityCenter() ? "city" : "unit"))
                          .append('}');
                        if (n >= 6) break;
                    }
                } catch (Throwable ignored) { }
                sb.append(']');
            }
            sb.append('}');
        }
        sb.append(']');

        // 外交 / 城邦
        StringBuilder known = new StringBuilder();
        StringBuilder cs = new StringBuilder();
        boolean firstKnown = true, firstCs = true;
        Iterator<Civilization> kit = c.getKnownCivs().iterator();
        while (kit.hasNext()) {
            Civilization o = kit.next();
            if (o == c) continue;
            com.unciv.logic.civilization.diplomacy.DiplomacyManager dm = c.getDiplomacyManager(o);
            int influence = 0;
            try {
                if (o.isCityState() && o.getDiplomacyManager(c) != null)
                    influence = (int) o.getDiplomacyManager(c).getInfluence();
            } catch (Throwable ignored) { }
            if (!firstKnown) known.append(',');
            firstKnown = false;
            known.append("{\"civ\":").append(quote(o.getCivName()))
                 .append(",\"isCityState\":").append(o.isCityState())
                 .append(",\"atWar\":").append(c.isAtWarWith(o))
                 .append(",\"canDeclareWar\":").append(dm != null && dm.canDeclareWar())
                 .append(",\"influence\":").append(influence)
                 .append('}');
            if (o.isCityState()) {
                if (!firstCs) cs.append(',');
                firstCs = false;
                cs.append("{\"civ\":").append(quote(o.getCivName()))
                  .append(",\"influence\":").append(influence)
                  .append(",\"ally\":").append(o.getAllyCiv() == null ? "null" : quote(o.getAllyCiv().getCivName()))
                  .append('}');
            }
        }
        sb.append(",\"diplomacy\":{\"known\":[").append(known).append("]}");
        sb.append(",\"cityStates\":[").append(cs).append(']');

        // 宗教
        sb.append(",\"religion\":{\"state\":").append(quote(c.getReligionManager().getReligionState().name()))
          .append(",\"faith\":").append(c.getReligionManager().getStoredFaith())
          .append(",\"hasFreeBeliefs\":").append(c.getReligionManager().hasFreeBeliefs())
          .append('}');

        // 待处理占城
        StringBuilder pending = new StringBuilder();
        for (com.unciv.logic.civilization.PopupAlert a : c.getPopupAlerts()) {
            if (a.getType() != com.unciv.logic.civilization.AlertType.CityConquered) continue;
            if (pending.length() > 0) pending.append(',');
            pending.append(quote("city-" + a.getValue()));
        }
        sb.append(",\"pendingCaptures\":[").append(pending).append(']');

        // 本回合通知
        StringBuilder ev = new StringBuilder();
        List<Notification> all = c.getNotifications();
        int start = Math.max(0, all.size() - 25);
        for (int i = start; i < all.size(); i++) {
            if (i > start) ev.append(',');
            ev.append("{\"category\":").append(quote(all.get(i).getCategory().name()))
              .append(",\"text\":").append(quote(all.get(i).getText())).append('}');
        }
        sb.append(",\"events\":[").append(ev).append(']');

        // 提醒
        List<String> warnings = new ArrayList<>();
        int idleCities = 0;
        for (City city : c.getCities()) {
            if (city.isPuppet() || city.isInResistance()) continue;
            String item = city.getCityConstructions().getCurrentConstruction().getName();
            if (item == null || item.isEmpty() || item.equals("Nothing")) idleCities++;
        }
        if (idleCities > 0) warnings.add(idleCities + " 座城没有在生产任何东西");
        if (cur == null || cur.isEmpty()) warnings.add("没有在研究的科技");
        if (gpt < 0 && gold >= 0) warnings.add("每回合亏 " + round1(gpt) + " 金币");
        if (gold <= -200) warnings.add("国库 " + gold + "，已经在解散军队抵债");
        else if (gpt < 0) {
            int turnsLeft = (int) Math.floor((gold + 200) / -gpt);
            warnings.add("每回合亏 " + round1(gpt) + " 金币，国库 " + gold
                    + "，跌破 -200 才解散军队"
                    + (turnsLeft > 0 ? "，大约 " + turnsLeft + " 回合" : ""));
        }
        if (pending.length() > 0) warnings.add("有刚打下的城还没决定吞并/傀儡");
        if (happiness < 0) warnings.add("快乐为负（" + happiness + "），增长和产能会受影响");
        StringBuilder w = new StringBuilder();
        for (String s : warnings) {
            if (w.length() > 0) w.append(',');
            w.append(quote(s));
        }
        sb.append(",\"warnings\":[").append(w).append("]}");
        return sb.toString();
    }

    private static boolean hasForeignMilitary(Tile t, Civilization c) {
        try {
            Iterator<MapUnit> it = t.getUnits().iterator();
            while (it.hasNext()) {
                MapUnit u = it.next();
                if (u.getCiv() != c && u.isMilitary()) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** 游戏自带教程/百科条目，给 agent 当攻略参考 */
    private static List<Object> guideEntries = new ArrayList<>();

    private static void loadGuide() {
        File f = new File("jsons/Tutorials.json");
        if (!f.exists()) {
            System.out.println("[unciv-agent] 没找到 jsons/Tutorials.json，/guide 会返回空");
            return;
        }
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Object parsed = Json.parseRelaxed(text);
            guideEntries = Json.arr(parsed);
            System.out.println("[unciv-agent] 载入教程条目 " + guideEntries.size() + " 条");
        } catch (Throwable t) {
            System.out.println("[unciv-agent] 教程载入失败: " + t);
        }
    }

    static String guideJson(String nameFilter, String categoryFilter) {
        StringBuilder sb = new StringBuilder("{\"entries\":[");
        boolean first = true;
        for (Object o : guideEntries) {
            Map<String, Object> e = Json.obj(o);
            String name = Json.str(e, "name");
            String category = Json.str(e, "category");
            if (nameFilter != null && (name == null || !name.toLowerCase().contains(nameFilter.toLowerCase()))) continue;
            if (categoryFilter != null && (category == null || !category.toLowerCase().contains(categoryFilter.toLowerCase()))) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"name\":").append(name == null ? "null" : quote(name))
              .append(",\"category\":").append(category == null ? "null" : quote(category));
            List<Object> steps = Json.arr(e.get("steps"));
            sb.append(",\"steps\":[");
            for (int i = 0; i < steps.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(quote(String.valueOf(steps.get(i))));
            }
            sb.append(']');
            List<Object> ctext = Json.arr(e.get("civilopediaText"));
            StringBuilder body = new StringBuilder();
            for (Object line : ctext) {
                if (!(line instanceof Map)) continue;
                String t = Json.str(Json.obj(line), "text");
                if (t != null && !t.isEmpty()) {
                    if (body.length() > 0) body.append("\n");
                    body.append(t);
                }
            }
            if (body.length() == 0) {
                for (int i = 0; i < steps.size(); i++) {
                    if (i > 0) body.append("\n");
                    body.append(String.valueOf(steps.get(i)));
                }
            }
            sb.append(",\"text\":").append(quote(body.toString())).append('}');
        }
        return sb.append("]}").toString();
    }

    /**
     * 攻击预览：不执行动作，只算伤害范围。客户端点目标时会显示这个，agent 之前只能打了才知道。
     */
    static String previewAttackJson(GameInfo gi, Civilization c, String unitRef, String cityRef, Integer x, Integer y) {
        if (x == null || y == null) return "{\"error\":{\"code\":\"BAD_PARAMS\",\"message\":\"需要 x 与 y\"}}";
        Tile target = Kernel.tileAt(gi, x, y);
        if (target == null) return "{\"error\":{\"code\":\"UNKNOWN_ID\",\"message\":" + quote("没有这个格子") + "}}";

        com.unciv.logic.battle.ICombatant defender = null;
        String targetType = "none";
        try {
            if (target.isCityCenter() && target.getCity() != null) {
                defender = new com.unciv.logic.battle.CityCombatant(target.getCity());
                targetType = "city";
            } else {
                Iterator<MapUnit> it = target.getUnits().iterator();
                while (it.hasNext()) {
                    MapUnit mu = it.next();
                    if (mu.getCiv() != c) { defender = new com.unciv.logic.battle.MapUnitCombatant(mu); targetType = "unit"; break; }
                }
            }
        } catch (Throwable ignored) { }
        if (defender == null)
            return "{\"error\":{\"code\":\"ILLEGAL_TARGET\",\"message\":" + quote("这个格子上没有可打的敌人") + "}}";

        com.unciv.logic.battle.ICombatant attacker = null;
        Tile fromTile = null;
        int range = 1;
        if (unitRef != null) {
            Integer id = Kernel.idOf(unitRef, "unit");
            MapUnit u = null;
            if (id != null) {
                Iterator<MapUnit> it = c.getUnits().getCivUnits().iterator();
                while (it.hasNext()) { MapUnit m = it.next(); if (m.getId() == id) u = m; }
            }
            if (u == null) return "{\"error\":{\"code\":\"NOT_YOUR_UNIT\",\"message\":" + quote("没有这个单位") + "}}";
            attacker = new com.unciv.logic.battle.MapUnitCombatant(u);
            fromTile = u.getTile();
            range = u.getRange();
        } else if (cityRef != null) {
            City city = null;
            for (City cc : c.getCities())
                if (("city-" + cc.getId()).equals(cityRef) || cc.getId().equals(cityRef) || cc.getName().equals(cityRef)) city = cc;
            if (city == null) return "{\"error\":{\"code\":\"NOT_YOUR_CITY\",\"message\":" + quote("没有这个城市") + "}}";
            if (!city.canBombard())
                return "{\"error\":{\"code\":\"NOT_AVAILABLE\",\"message\":" + quote(city.getName() + " 现在不能开火") + "}}";
            attacker = new com.unciv.logic.battle.CityCombatant(city);
            fromTile = city.getCenterTile();
            range = city.getBombardRange();
        } else {
            return "{\"error\":{\"code\":\"BAD_PARAMS\",\"message\":" + quote("需要 unit 或 city") + "}}";
        }

        int distance = fromTile.aerialDistanceTo(target);
        com.unciv.logic.battle.BattleDamage bd = com.unciv.logic.battle.BattleDamage.INSTANCE;
        int defMin, defMax, atkMin, atkMax, defStrength, atkStrength;
        try {
            defMin = bd.calculateDamageToDefender(attacker, defender, fromTile, 0f);
            defMax = bd.calculateDamageToDefender(attacker, defender, fromTile, 1f);
            atkMin = bd.calculateDamageToAttacker(attacker, defender, fromTile, 0f);
            atkMax = bd.calculateDamageToAttacker(attacker, defender, fromTile, 1f);
            atkStrength = (int) bd.getAttackingStrength(attacker, defender, fromTile);
            defStrength = (int) bd.getDefendingStrength(attacker, defender, fromTile);
        } catch (Throwable e) {
            return "{\"error\":{\"code\":\"NOT_AVAILABLE\",\"message\":" + quote("算不出伤害: " + e.getMessage()) + "}}";
        }
        int defenderHp = defender.getHealth();
        int attackerHp = attacker.getHealth();
        boolean inRange = distance <= range;

        return "{\"target\":[" + x + "," + y + "]"
                + ",\"targetType\":" + quote(targetType)
                + ",\"distance\":" + distance
                + ",\"range\":" + range
                + ",\"inRange\":" + inRange
                + ",\"attackerStrength\":" + atkStrength
                + ",\"defenderStrength\":" + defStrength
                + ",\"damageToDefender\":{\"min\":" + defMin + ",\"max\":" + defMax + "}"
                + ",\"damageToAttacker\":{\"min\":" + atkMin + ",\"max\":" + atkMax + "}"
                + ",\"defenderHp\":" + defenderHp
                + ",\"attackerHp\":" + attackerHp
                + ",\"wouldKillDefender\":" + (defMax >= defenderHp)
                + ",\"wouldKillAttacker\":" + (atkMax >= attackerHp) + "}";
    }

    /** 帝国总览的 Stats 页：金币/科研/文化/信仰按来源拆开，加快乐拆解。 */
    static String statsJson(GameInfo gi, Civilization c) {
        StringBuilder sb = new StringBuilder("{\"turn\":").append(gi.getTurns())
                .append(",\"gold\":").append(c.getGold())
                .append(",\"perTurn\":{");
        try {
            com.unciv.models.stats.StatMap statMap = c.getStats().getStatMapForNextTurn();
            com.unciv.models.stats.Stats total = new com.unciv.models.stats.Stats();
            for (com.unciv.models.stats.Stats st : statMap.values()) total.add(st);
            sb.append("\"gold\":").append(round1(total.getGold()))
              .append(",\"science\":").append(round1(total.getScience()))
              .append(",\"culture\":").append(round1(total.getCulture()))
              .append(",\"faith\":").append(round1(total.getFaith()))
              .append(",\"happiness\":").append(round1(c.getHappiness()));
            sb.append("},\"causes\":[");
            boolean first = true;
            for (Map.Entry<String, com.unciv.models.stats.Stats> e : statMap.entrySet()) {
                com.unciv.models.stats.Stats st = e.getValue();
                boolean any = Math.abs(st.getGold()) > 0.05f || Math.abs(st.getScience()) > 0.05f
                        || Math.abs(st.getCulture()) > 0.05f || Math.abs(st.getFaith()) > 0.05f;
                if (!any) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"cause\":").append(quote(e.getKey()));
                if (Math.abs(st.getGold()) > 0.05f) sb.append(",\"gold\":").append(round1(st.getGold()));
                if (Math.abs(st.getScience()) > 0.05f) sb.append(",\"science\":").append(round1(st.getScience()));
                if (Math.abs(st.getCulture()) > 0.05f) sb.append(",\"culture\":").append(round1(st.getCulture()));
                if (Math.abs(st.getFaith()) > 0.05f) sb.append(",\"faith\":").append(round1(st.getFaith()));
                sb.append('}');
            }
            sb.append(']');
        } catch (Throwable ignored) { sb.append("}, \"causes\":[]"); }
        sb.append(",\"happinessCauses\":[");
        try {
            java.util.HashMap<String, Float> hb = c.getStats().getHappinessBreakdown();
            boolean first = true;
            for (Map.Entry<String, Float> e : hb.entrySet()) {
                if (Math.abs(e.getValue()) < 0.05f) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"cause\":").append(quote(e.getKey()))
                  .append(",\"value\":").append(round1(e.getValue())).append('}');
            }
        } catch (Throwable ignored) { }
        sb.append(']');
        // 城市一级的产出，UI 的城市列表也是这么排的
        sb.append(",\"cities\":[");
        boolean firstCity = true;
        for (City city : c.getCities()) {
            if (!firstCity) sb.append(',');
            firstCity = false;
            com.unciv.models.stats.Stats st = city.getCityStats().getCurrentCityStats();
            sb.append("{\"name\":").append(quote(city.getName()))
              .append(",\"pop\":").append(city.getPopulation().getPopulation())
              .append(",\"food\":").append(round1(st.getFood()))
              .append(",\"production\":").append(round1(st.getProduction()))
              .append(",\"gold\":").append(round1(st.getGold()))
              .append(",\"science\":").append(round1(st.getScience()))
              .append(",\"culture\":").append(round1(st.getCulture()))
              .append(",\"faith\":").append(round1(st.getFaith()))
              .append('}');
        }
        return sb.append("]}").toString();
    }

    /** 资源总览：奢侈品连没连、战略资源有多少。对应 UI 的 Resources 页。 */
    static String resourcesJson(GameInfo gi, Civilization c) {
        StringBuilder sb = new StringBuilder("{\"luxuries\":{\"connected\":[");
        java.util.HashMap<String, Integer> amounts;
        try { amounts = c.getCivResourcesByName(); } catch (Throwable e) { amounts = new java.util.HashMap<>(); }
        boolean first = true;
        try {
            for (com.unciv.models.ruleset.tile.TileResource r : gi.getRuleset().getTileResources().values()) {
                if (r.getResourceType() != com.unciv.models.ruleset.tile.ResourceType.Luxury) continue;
                int n = amounts.getOrDefault(r.getName(), 0);
                if (n <= 0) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"name\":").append(quote(r.getName()))
                  .append(",\"count\":").append(n).append('}');
            }
        } catch (Throwable ignored) { }
        sb.append("],\"unconnected\":[");
        first = true;
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        try {
            for (City city : c.getCities()) {
                Iterator<Tile> tit = city.getWorkableTiles().iterator();
                while (tit.hasNext()) {
                    Tile t = tit.next();
                    String res = t.getResource();
                    if (res == null) continue;
                    com.unciv.models.ruleset.tile.TileResource tr = gi.getRuleset().getTileResources().get(res);
                    if (tr == null || tr.getResourceType() != com.unciv.models.ruleset.tile.ResourceType.Luxury) continue;
                    if (amounts.getOrDefault(res, 0) > 0) continue;
                    if (!seen.add(res)) continue;
                    if (!first) sb.append(',');
                    first = false;
                    sb.append("{\"name\":").append(quote(res))
                      .append(",\"at\":[").append(t.getPosition().getX()).append(',')
                      .append(t.getPosition().getY()).append(']')
                      .append(",\"improvement\":").append(t.getImprovement() == null
                              ? "null" : quote(t.getImprovement()))
                      .append('}');
                }
            }
        } catch (Throwable ignored) { }
        sb.append("]},\"strategic\":[");
        first = true;
        try {
            for (com.unciv.models.ruleset.tile.TileResource r : gi.getRuleset().getTileResources().values()) {
                if (r.getResourceType() != com.unciv.models.ruleset.tile.ResourceType.Strategic) continue;
                int n = amounts.getOrDefault(r.getName(), 0);
                if (n <= 0) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"name\":").append(quote(r.getName()))
                  .append(",\"count\":").append(n).append('}');
            }
        } catch (Throwable ignored) { }
        sb.append("]}");
        return sb.toString();
    }

    /** 移动预览：一条路线、每格代价、几回合到、到不了为什么。只读，不消耗移动力。 */
    static String previewMoveJson(GameInfo gi, Civilization c, String unitRef, Integer x, Integer y) {
        Integer id = Kernel.idOf(unitRef, "unit");
        MapUnit unit = null;
        if (id != null) {
            Iterator<MapUnit> it = c.getUnits().getCivUnits().iterator();
            while (it.hasNext()) { MapUnit m = it.next(); if (m.getId() == id) unit = m; }
        }
        if (unit == null)
            return "{\"error\":{\"code\":\"NOT_YOUR_UNIT\",\"message\":" + quote("没有这个单位") + "}}";
        if (x == null || y == null)
            return "{\"error\":{\"code\":\"BAD_PARAMS\",\"message\":" + quote("需要 x 与 y") + "}}";
        Tile dest = Kernel.tileAt(gi, x, y);
        if (dest == null)
            return "{\"error\":{\"code\":\"ILLEGAL_TARGET\",\"message\":" + quote("地图外: " + x + "," + y) + "}}";

        Tile from = unit.getTile();
        HexCoord fp = from.getPosition();
        StringBuilder sb = new StringBuilder("{\"unit\":").append(quote("unit-" + unit.getId()))
                .append(",\"name\":").append(quote(unit.getName()))
                .append(",\"from\":[").append(fp.getX()).append(',').append(fp.getY()).append(']')
                .append(",\"to\":[").append(x).append(',').append(y).append(']')
                .append(",\"distance\":").append(from.aerialDistanceTo(dest))
                .append(",\"moves\":").append(unit.getCurrentMovement())
                .append(",\"maxMoves\":").append(unit.getMaxMovement(false));

        String reason = null;
        try {
            Object r = unit.getMovement().getCannotMoveToReason(dest, false, true, false, null);
            if (r != null) reason = r.toString();
        } catch (Throwable ignored) { }

        // 这回合能不能到
        com.unciv.logic.map.mapunit.movement.PathsToTilesWithinTurn paths = null;
        try { paths = unit.getMovement().getDistanceToTiles(true, null, null, true); }
        catch (Throwable ignored) { }

        if (paths != null && paths.containsKey(dest)) {
            sb.append(",\"reachableThisTurn\":true,\"turnsToReach\":1");
            float total = 0f;
            try { total = paths.get(dest).getTotalMovement(); } catch (Throwable ignored) { }
            sb.append(",\"totalMovement\":").append(round2(total))
              .append(",\"movesLeftAfter\":").append(round2(Math.max(0f, unit.getCurrentMovement() - total)));
            sb.append(",\"path\":");
            appendPath(sb, unit, paths, dest);
        } else {
            sb.append(",\"reachableThisTurn\":false");
            // 多回合：按代价做 Dijkstra，再按每回合移动力模拟
            List<Tile> route = null;
            float cost = 0f;
            try {
                Object[] res = planRoute(unit, from, dest);
                if (res != null) { route = (List<Tile>) res[0]; cost = (Float) res[1]; }
            } catch (Throwable ignored) { }
            if (route == null) {
                sb.append(",\"turnsToReach\":null,\"path\":[]");
            } else {
                int[] sim = simulateTurns(unit, route);
                sb.append(",\"turnsToReach\":").append(sim[0])
                  .append(",\"totalMovement\":").append(round2(cost))
                  .append(",\"movesLeftAfter\":").append(round2(sim[1] / 100f))
                  .append(",\"path\":");
                StringBuilder pj = new StringBuilder("[");
                for (int i = 0; i < route.size(); i++) {
                    Tile t = route.get(i);
                    if (i > 0) pj.append(',');
                    pj.append("{\"x\":").append(t.getPosition().getX())
                      .append(",\"y\":").append(t.getPosition().getY())
                      .append(",\"terrain\":").append(quote(t.getLastTerrain() == null ? "" : t.getLastTerrain().getName()))
                      .append('}');
                }
                pj.append(']');
                sb.append(pj);
            }
        }

        // 目标格本身的信息
        sb.append(",\"destination\":{\"terrain\":")
          .append(quote(dest.getLastTerrain() == null ? "" : dest.getLastTerrain().getName()))
          .append(",\"improvement\":").append(dest.getImprovement() == null ? "null" : quote(dest.getImprovement()))
          .append(",\"city\":").append(dest.isCityCenter() && dest.getCity() != null
                  ? quote(dest.getCity().getName()) : "null")
          .append('}');
        sb.append(",\"blocked\":").append(reason == null ? "null" : quote(reason));
        sb.append(",\"blockedHint\":").append(reason == null ? "null" : quote(moveReasonHint(reason)));
        return sb.append('}').toString();
    }

    private static void appendPath(StringBuilder sb, MapUnit unit,
                                   com.unciv.logic.map.mapunit.movement.PathsToTilesWithinTurn paths, Tile dest) {
        List<Tile> route;
        try { route = paths.getPathToTile(dest); }
        catch (Throwable e) { sb.append("[]"); return; }
        sb.append('[');
        Tile prev = unit.getTile();
        boolean first = true;
        for (Tile t : route) {
            if (!first) sb.append(',');
            first = false;
            float step = 0f;
            try {
                step = com.unciv.logic.map.mapunit.movement.MovementCost.INSTANCE
                        .getMovementCostBetweenAdjacentTiles(unit, prev, t, false);
            } catch (Throwable ignored) { }
            sb.append("{\"x\":").append(t.getPosition().getX())
              .append(",\"y\":").append(t.getPosition().getY())
              .append(",\"terrain\":").append(quote(t.getLastTerrain() == null ? "" : t.getLastTerrain().getName()))
              .append(",\"cost\":").append(round2(step))
              .append('}');
            prev = t;
        }
        sb.append(']');
    }

    /** 按步进代价做 Dijkstra，只走 canPassThrough 的格子；返回 {List<Tile> 路线, Float 总代价} 或 null。 */
    private static Object[] planRoute(MapUnit unit, Tile from, Tile dest) {
        int cap = 1500;
        java.util.HashMap<Tile, Float> dist = new java.util.HashMap<>();
        java.util.HashMap<Tile, Tile> prev = new java.util.HashMap<>();
        java.util.PriorityQueue<Object[]> pq = new java.util.PriorityQueue<>(16,
                (a, b) -> Float.compare((Float) a[0], (Float) b[0]));
        dist.put(from, 0f);
        pq.add(new Object[]{0f, from});
        int visited = 0;
        while (!pq.isEmpty() && visited++ < cap) {
            Object[] cur = pq.poll();
            float d = (Float) cur[0];
            Tile t = (Tile) cur[1];
            if (d > dist.getOrDefault(t, Float.MAX_VALUE)) continue;
            if (t == dest) break;
            Iterator<Tile> nit = t.getNeighbors().iterator();
            while (nit.hasNext()) {
                Tile n = nit.next();
                boolean pass = false;
                try { pass = unit.getMovement().canPassThrough(n, false); } catch (Throwable ignored) { }
                if (!pass && n != dest) continue;
                float step;
                try {
                    step = com.unciv.logic.map.mapunit.movement.MovementCost.INSTANCE
                            .getMovementCostBetweenAdjacentTiles(unit, t, n, false);
                } catch (Throwable e) { continue; }
                if (step >= 100f) continue;
                float nd = d + step;
                if (nd < dist.getOrDefault(n, Float.MAX_VALUE)) {
                    dist.put(n, nd);
                    prev.put(n, t);
                    pq.add(new Object[]{nd, n});
                }
            }
        }
        if (!dist.containsKey(dest)) return null;
        List<Tile> route = new ArrayList<>();
        Tile cur = dest;
        while (cur != from) {
            route.add(0, cur);
            cur = prev.get(cur);
            if (cur == null) return null;
        }
        return new Object[]{route, dist.get(dest)};
    }

    /** 按每回合重置移动力模拟走这条路线；返回 {回合数, 最后剩余移动力×100}。 */
    private static int[] simulateTurns(MapUnit unit, List<Tile> route) {
        int max = Math.max(1, unit.getMaxMovement(false));
        float moves = unit.getCurrentMovement();
        int turns = 1;
        Tile prev = unit.getTile();
        for (Tile t : route) {
            float step;
            try {
                step = com.unciv.logic.map.mapunit.movement.MovementCost.INSTANCE
                        .getMovementCostBetweenAdjacentTiles(unit, prev, t, false);
            } catch (Throwable e) { step = 1f; }
            if (moves <= 0.05f) { turns++; moves = max; }
            moves = Math.max(0f, moves - step);
            prev = t;
        }
        return new int[]{turns, Math.round(moves * 100)};
    }

    private static String moveReasonHint(String reason) {
        switch (reason) {
            case "TerrainImpassable": return "地形不可通行";
            case "BoatCannotGoOnLand": return "船不能上岸";
            case "CannotEmbark": return "这个单位不能上船/下水";
            case "CannotEnterOcean": return "还不能进远洋，需要相关科技";
            case "CannotEnterForeignLand": return "不能进入别国领土（没开边）";
            case "CannotEnterCityCenter": return "不能进入别人城市中心";
            case "TileIsNotEmpty": return "目标格已经有单位";
            case "NoAirUnitTransport": return "没有可用的空中运输";
            default: return reason;
        }
    }

    /** 这回合能走到的所有格子，带剩余移动力；客户端移动覆盖层的等价物。 */
    static String reachableJson(GameInfo gi, Civilization c, String unitRef) {
        Integer id = Kernel.idOf(unitRef, "unit");
        MapUnit unit = null;
        if (id != null) {
            Iterator<MapUnit> it = c.getUnits().getCivUnits().iterator();
            while (it.hasNext()) { MapUnit m = it.next(); if (m.getId() == id) unit = m; }
        }
        if (unit == null)
            return "{\"error\":{\"code\":\"NOT_YOUR_UNIT\",\"message\":" + quote("没有这个单位") + "}}";
        HexCoord p = unit.getTile().getPosition();
        StringBuilder sb = new StringBuilder("{\"unit\":").append(quote("unit-" + unit.getId()))
                .append(",\"name\":").append(quote(unit.getName()))
                .append(",\"from\":[").append(p.getX()).append(',').append(p.getY()).append(']')
                .append(",\"moves\":").append(unit.getCurrentMovement())
                .append(",\"tiles\":[");
        int n = 0;
        try {
            com.unciv.logic.map.mapunit.movement.PathsToTilesWithinTurn paths =
                    unit.getMovement().getDistanceToTiles(true, null, null, true);
            List<Tile> keys = new ArrayList<>(paths.keySet());
            keys.sort((a, b) -> Float.compare(paths.get(a).getTotalMovement(), paths.get(b).getTotalMovement()));
            for (Tile t : keys) {
                if (t == unit.getTile()) continue;
                if (n > 0) sb.append(',');
                n++;
                sb.append("{\"x\":").append(t.getPosition().getX())
                  .append(",\"y\":").append(t.getPosition().getY())
                  .append(",\"cost\":").append(round2(paths.get(t).getTotalMovement()))
                  .append(",\"terrain\":").append(quote(t.getLastTerrain() == null ? "" : t.getLastTerrain().getName()));
                if (t.isCityCenter() && t.getCity() != null)
                    sb.append(",\"city\":").append(quote(t.getCity().getName()));
                if (t.getImprovement() != null)
                    sb.append(",\"improvement\":").append(quote(t.getImprovement()));
                sb.append('}');
            }
        } catch (Throwable ignored) { }
        return sb.append("],\"count\":").append(n).append('}').toString();
    }

    /** 空生产在三个投影里必须同一个写法：null，不是 "Nothing" */
    static String productionJson(City city) {
        try {
            String n = city.getCityConstructions().getCurrentConstruction().getName();
            if (n == null || n.isEmpty() || n.equals("Nothing")) return "null";
            return quote(n);
        } catch (Throwable e) {
            return "null";
        }
    }

    private static String round2(float f) {
        return String.valueOf(Math.round(f * 100f) / 100f);
    }

    /** 某个单位这回合能打的所有目标，附伤害区间；省得模型自己算六边形几何 */
    static String targetsJson(GameInfo gi, Civilization c, String unitRef) {
        Integer id = Kernel.idOf(unitRef, "unit");
        MapUnit unit = null;
        if (id != null) {
            Iterator<MapUnit> it = c.getUnits().getCivUnits().iterator();
            while (it.hasNext()) { MapUnit m = it.next(); if (m.getId() == id) unit = m; }
        }
        if (unit == null) return "{\"error\":{\"code\":\"NOT_YOUR_UNIT\",\"message\":" + quote("没有这个单位") + "}}";

        StringBuilder sb = new StringBuilder("{\"unit\":").append(quote("unit-" + unit.getId()))
                .append(",\"name\":").append(quote(unit.getName()))
                .append(",\"range\":").append(unit.getRange())
                .append(",\"targets\":[");
        boolean first = true;
        try {
            com.unciv.logic.battle.BattleDamage bd = com.unciv.logic.battle.BattleDamage.INSTANCE;
            com.unciv.logic.battle.MapUnitCombatant attacker =
                    new com.unciv.logic.battle.MapUnitCombatant(unit);
            com.unciv.logic.map.mapunit.movement.PathsToTilesWithinTurn paths =
                    unit.getMovement().getDistanceToTiles(true, null, null, true);
            ArrayList<com.unciv.logic.battle.AttackableTile> list =
                    com.unciv.logic.battle.TargetHelper.INSTANCE.getAttackableEnemies(unit, paths, null, false);
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (com.unciv.logic.battle.AttackableTile at : list) {
                Tile t = at.getTileToAttack();
                String key = t.getPosition().getX() + "," + t.getPosition().getY();
                if (!seen.add(key)) continue;
                com.unciv.logic.battle.ICombatant defender = at.getCombatant();
                int dMin = 0, dMax = 0, aMin = 0, aMax = 0;
                try {
                    dMin = bd.calculateDamageToDefender(attacker, defender, at.getTileToAttackFrom(), 0f);
                    dMax = bd.calculateDamageToDefender(attacker, defender, at.getTileToAttackFrom(), 1f);
                    aMin = bd.calculateDamageToAttacker(attacker, defender, at.getTileToAttackFrom(), 0f);
                    aMax = bd.calculateDamageToAttacker(attacker, defender, at.getTileToAttackFrom(), 1f);
                } catch (Throwable ignored) { }
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"x\":").append(t.getPosition().getX())
                  .append(",\"y\":").append(t.getPosition().getY())
                  .append(",\"type\":").append(quote(t.isCityCenter() ? "city" : "unit"))
                  .append(",\"damageToDefender\":{\"min\":").append(dMin).append(",\"max\":").append(dMax).append('}')
                  .append(",\"damageToAttacker\":{\"min\":").append(aMin).append(",\"max\":").append(aMax).append('}')
                  .append('}');
            }
        } catch (Throwable ignored) { }
        return sb.append("]}").toString();
    }

    static String techsJson(GameInfo gi, Civilization c) {
        StringBuilder sb = new StringBuilder("{\"researching\":");
        String cur = c.getTech().currentTechnologyName();
        sb.append(cur == null || cur.isEmpty() ? "null" : quote(cur));
        sb.append(",\"sciencePerTurn\":").append(round1(c.getStats().getStatsForNextTurn().getScience()));
        sb.append(",\"available\":[");
        boolean first = true;
        try {
            for (String name : gi.getRuleset().getTechnologies().keySet()) {
                if (!c.getTech().canBeResearched(name)) continue;
                if (!first) sb.append(',');
                first = false;
                int cost = c.getTech().costOfTech(name);
                int remaining = cost;
                try {
                    remaining = Math.max(0, c.getTech().remainingScienceToTech(name));
                } catch (Throwable ignored) { }
                double perTurn = c.getStats().getStatsForNextTurn().getScience();
                sb.append("{\"name\":").append(quote(name))
                  .append(",\"cost\":").append(cost)
                  .append(",\"remaining\":").append(remaining)
                  .append(",\"turns\":").append(perTurn <= 0.01 ? "null"
                          : String.valueOf((int) Math.ceil(remaining / perTurn)))
                  .append('}');
            }
        } catch (Throwable ignored) { }
        sb.append("],\"researched\":[");
        boolean firstDone = true;
        try {
            java.util.List<String> known = new java.util.ArrayList<>(c.getTech().getTechsResearched());
            java.util.Collections.sort(known);
            for (String t : known) {
                if (!firstDone) sb.append(',');
                firstDone = false;
                sb.append(quote(t));
            }
        } catch (Throwable ignored) { }
        return sb.append("]}").toString();
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

        boolean canPantheon = false;
        try { canPantheon = rm.canFoundOrExpandPantheon(); } catch (Throwable ignored) { }
        StringBuilder must = new StringBuilder("{");
        try {
            com.unciv.logic.civilization.managers.ReligionState state = rm.getReligionState();
            com.unciv.models.Counter<com.unciv.models.ruleset.BeliefType> cnt;
            // 只有还没立过万神殿时才要求选万神殿信念；state==Pantheon 时引擎会拒绝，
            // 之前这里照报 Pantheon:1，和 choose_beliefs 的返回直接矛盾。
            if (state == com.unciv.logic.civilization.managers.ReligionState.None) {
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
                + ",\"canChoosePantheon\":" + canPantheon
                + ",\"pantheonFaithNeeded\":" + rm.faithForPantheon(0)
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
        // 引擎的 UnitActions 不含 UI 专用动作，这里补上内核真正实现的那两个
        try {
            if (unit.getCache().getHasUniqueToBuildImprovements()) {
                sb.append(",{\"type\":").append(quote("build_improvement"))
                  .append(",\"title\":").append(quote("造设施"))
                  .append(",\"available\":").append(unit.hasMovement())
                  .append('}');
            }
            sb.append(",{\"type\":").append(quote("disband_unit"))
              .append(",\"title\":").append(quote("解散单位"))
              .append(",\"available\":true")
              .append('}');
        } catch (Throwable ignored) { }
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
        // 第一回合开始前 stats 还是空的，算一次让 /state /techs 一开始就有数
        try {
            gi.getCurrentPlayerCiv().updateStatsForNextTurn();
        } catch (Throwable ignored) { }
        return stateJson(gi, gi.getCurrentPlayerCiv());
    }
}
