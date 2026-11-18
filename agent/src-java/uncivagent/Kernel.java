package uncivagent;

import com.unciv.logic.GameInfo;
import com.unciv.logic.city.City;
import com.unciv.logic.civilization.Civilization;
import com.unciv.logic.map.HexCoord;
import com.unciv.logic.map.mapunit.MapUnit;
import com.unciv.logic.battle.AttackableTile;
import com.unciv.logic.battle.Battle;
import com.unciv.logic.battle.MapUnitCombatant;
import com.unciv.logic.battle.TargetHelper;
import com.unciv.logic.civilization.Notification;
import com.unciv.logic.map.mapunit.actions.UnitActions;
import com.unciv.logic.map.mapunit.movement.PathsToTilesWithinTurn;
import com.unciv.models.ruleset.unit.Promotion;
import com.unciv.logic.map.tile.Tile;
import com.unciv.models.UnitActionType;
import com.unciv.models.ruleset.Ruleset;

import com.unciv.logic.trade.Trade;
import com.unciv.logic.trade.TradeLogic;
import com.unciv.logic.trade.TradeOffer;
import com.unciv.logic.trade.TradeOfferType;
import com.unciv.logic.trade.TradeOffersList;
import com.unciv.logic.trade.TradeRequest;
import com.unciv.logic.civilization.diplomacy.CityStateFunctions;
import com.unciv.logic.civilization.managers.ReligionManager;
import com.unciv.logic.civilization.managers.ReligionState;
import com.unciv.logic.map.mapunit.PossibleUpgrade;
import com.unciv.logic.battle.MapUnitCombatant;
import com.unciv.logic.battle.Nuke;
import com.unciv.models.ruleset.BeliefType;
import com.unciv.models.Counter;
import com.unciv.models.Religion;
import com.unciv.models.Spy;
import com.unciv.models.ruleset.Belief;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 统一动作内核。HTTP /intent 和 MCP tools/call 都走这里，行为必须一致。
 *
 * 契约见 notes/unciv-agent-contract.md：
 *   动作是数据 → validate（纯函数）→ apply（唯一写入口）→ Result(events, stateDelta)
 */
public final class Kernel {

    /** 幂等键：同一 intent.id 只生效一次；id 换了内容则报错，而不是静默丢掉 */
    private static final LinkedHashMap<String, String> appliedIds = new LinkedHashMap<>();

    private Kernel() { }

    // ---------- 工具表（唯一事实来源，HTTP 和 MCP 都从这里长出来） ----------

    public static final class Tool {
        final String name;
        final String description;
        final String schema;   // JSON Schema 的 properties 片段
        final String kind;     // "read" | "act" | "turn"

        Tool(String name, String description, String schema, String kind) {
            this.name = name;
            this.description = description;
            this.schema = schema;
            this.kind = kind;
        }
    }

    private static final Map<String, Tool> TOOLS = new LinkedHashMap<>();

    private static void tool(String name, String desc, String schema, String kind) {
        TOOLS.put(name, new Tool(name, desc, schema, kind));
    }

    static {
        tool("get_state", "读取当前文明的整体状态：回合、金币、研究、城市与单位摘要。", "{\"type\":\"object\",\"properties\":{}}", "read");
        tool("get_units", "列出己方所有单位，含坐标、血量、剩余移动力、可用动作。", "{\"type\":\"object\",\"properties\":{}}", "read");
        tool("get_cities", "列出己方所有城市，含人口、坐标、在建项目。", "{\"type\":\"object\",\"properties\":{}}", "read");
        tool("move_unit", "把一个单位移动到指定格子。",
                "{\"type\":\"object\",\"properties\":{\"unit\":{\"type\":\"string\"},\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"}},\"required\":[\"unit\",\"x\",\"y\"]}", "act");
        tool("found_city", "用移民建立城市。",
                "{\"type\":\"object\",\"properties\":{\"unit\":{\"type\":\"string\"}},\"required\":[\"unit\"]}", "act");
        tool("set_city_production", "设置城市的生产项目；count>1 时连续排入队列，避免造完变 Nothing。",
                "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"},\"item\":{\"type\":\"string\"},\"count\":{\"type\":\"integer\"}},\"required\":[\"city\",\"item\"]}", "act");
        tool("set_research", "设置当前研究目标。",
                "{\"type\":\"object\",\"properties\":{\"tech\":{\"type\":\"string\"}},\"required\":[\"tech\"]}", "act");
        tool("attack", "用单位攻击指定格子上的敌方单位或城市。",
                "{\"type\":\"object\",\"properties\":{\"unit\":{\"type\":\"string\"},\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"}},\"required\":[\"unit\",\"x\",\"y\"]}", "act");
        tool("bombard", "用远程单位轰击指定格子，不移动。",
                "{\"type\":\"object\",\"properties\":{\"unit\":{\"type\":\"string\"},\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"}},\"required\":[\"unit\",\"x\",\"y\"]}", "act");
        tool("promote_unit", "给单位选择一个晋升。",
                "{\"type\":\"object\",\"properties\":{\"unit\":{\"type\":\"string\"},\"promotion\":{\"type\":\"string\"}},\"required\":[\"unit\",\"promotion\"]}", "act");
        tool("unit_action", "触发单位的通用动作，如 Fortify / Sleep / Explore / Automate / Pillage / CreateImprovement。带 id 可指定具体目标（改良设施名、unique 等）。",
                "{\"type\":\"object\",\"properties\":{\"unit\":{\"type\":\"string\"},\"action\":{\"type\":\"string\"},\"id\":{\"type\":\"string\"}},\"required\":[\"unit\",\"action\"]}", "act");
        tool("get_map", "读取指定中心点附近的已探索地块。",
                "{\"type\":\"object\",\"properties\":{\"cx\":{\"type\":\"integer\"},\"cy\":{\"type\":\"integer\"},\"radius\":{\"type\":\"integer\"}},\"required\":[\"cx\",\"cy\"]}", "read");
        tool("get_events", "读取本回合的通知事件。",
                "{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\"}}}", "read");
        tool("get_city_details", "读取单个城市的详情：人口、焦点、生产队列、可建造项。",
                "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}", "read");
        tool("set_city_focus", "设置城市人口分配焦点。",
                "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"},\"focus\":{\"type\":\"string\"}},\"required\":[\"city\",\"focus\"]}", "act");
        tool("purchase_construction", "用金币或信仰购买城市建筑或单位。",
                "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"},\"item\":{\"type\":\"string\"},\"currency\":{\"type\":\"string\"}},\"required\":[\"city\",\"item\"]}", "act");
        tool("get_victory_progress", "读取胜利条件与当前进度。", "{\"type\":\"object\",\"properties\":{}}", "read");
        tool("get_tile", "读取单个地块的详情。",
                "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"}},\"required\":[\"x\",\"y\"]}", "read");
        tool("get_diplomacy", "列出已知文明及其外交状态。", "{\"type\":\"object\",\"properties\":{}}", "read");
        tool("declare_war", "对某个文明宣战。",
                "{\"type\":\"object\",\"properties\":{\"civ\":{\"type\":\"string\"}},\"required\":[\"civ\"]}", "act");
        tool("make_peace", "与某个文明议和。",
                "{\"type\":\"object\",\"properties\":{\"civ\":{\"type\":\"string\"}},\"required\":[\"civ\"]}", "act");
        tool("get_policies", "读取已采纳政策与可选分支。", "{\"type\":\"object\",\"properties\":{}}", "read");
        tool("adopt_policy", "采纳一个政策。",
                "{\"type\":\"object\",\"properties\":{\"policy\":{\"type\":\"string\"}},\"required\":[\"policy\"]}", "act");
        tool("end_turn", "结束本回合。单人模式下由宿主直接推进。",
                "{\"type\":\"object\",\"properties\":{}}", "turn");
        tool("get_trade_offers", "读取待回应的交易请求；带 civ 时还列出与该文明互相可交易的项。",
                "{\"type\":\"object\",\"properties\":{\"civ\":{\"type\":\"string\"}}}", "read");
        tool("propose_trade", "向某个文明提出交易。give 是你给出去的，want 是你想要的；每项 {name,type,amount}。",
                "{\"type\":\"object\",\"properties\":{\"civ\":{\"type\":\"string\"},\"give\":{\"type\":\"array\"},\"want\":{\"type\":\"array\"}},\"required\":[\"civ\"]}", "act");
        tool("respond_trade", "回应某个文明发来的交易请求：accept 为 true 成交，false 拒绝。",
                "{\"type\":\"object\",\"properties\":{\"civ\":{\"type\":\"string\"},\"accept\":{\"type\":\"boolean\"},\"index\":{\"type\":\"integer\"}},\"required\":[\"civ\"]}", "act");
        tool("get_techs", "列出当前可研究的科技、造价和按当前科研速度的回合数。",
                "{\"type\":\"object\",\"properties\":{}}", "read");        tool("get_religion", "读取宗教状态：信仰、万神殿/国教、已选与可选的信念。",
                "{\"type\":\"object\",\"properties\":{}}", "read");
        tool("choose_beliefs", "选信念：万神殿、立国教（带 unit 用大先知）或强化宗教。beliefs 是信念名数组。",
                "{\"type\":\"object\",\"properties\":{\"unit\":{\"type\":\"string\"},\"beliefs\":{\"type\":\"array\"},\"name\":{\"type\":\"string\"},\"displayName\":{\"type\":\"string\"}},\"required\":[\"beliefs\"]}", "act");
        tool("get_city_states", "列出已遇到的城邦：影响力、盟友、任务、送礼可得影响力。",
                "{\"type\":\"object\",\"properties\":{}}", "read");
        tool("gift_city_state", "给城邦送金币换影响力。",
                "{\"type\":\"object\",\"properties\":{\"civ\":{\"type\":\"string\"},\"gold\":{\"type\":\"integer\"}},\"required\":[\"civ\"]}", "act");
        tool("pledge_protect", "承诺保护或撤回对城邦的保护。",
                "{\"type\":\"object\",\"properties\":{\"civ\":{\"type\":\"string\"},\"protect\":{\"type\":\"boolean\"}},\"required\":[\"civ\"]}", "act");
        tool("set_worked_tiles", "手动指定城市工作哪些地块；lock 锁定，autoFill 用剩余人口自动补。",
                "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"},\"tiles\":{\"type\":\"array\"},\"lock\":{\"type\":\"boolean\"},\"autoFill\":{\"type\":\"boolean\"}},\"required\":[\"city\",\"tiles\"]}", "act");
        tool("nuke", "用核武器打击指定格子。",
                "{\"type\":\"object\",\"properties\":{\"unit\":{\"type\":\"string\"},\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"}},\"required\":[\"unit\",\"x\",\"y\"]}", "act");
        tool("upgrade_unit", "把单位升级到新兵种；有多个目标时用 to 指定。",
                "{\"type\":\"object\",\"properties\":{\"unit\":{\"type\":\"string\"},\"to\":{\"type\":\"string\"}},\"required\":[\"unit\"]}", "act");
        tool("get_spies", "列出间谍：位置、当前动作、剩余回合。",
                "{\"type\":\"object\",\"properties\":{}}", "read");
        tool("spy_move", "把间谍派到某个城市（别人城里会自动建网并开始偷科技），city 留空回总部。",
                "{\"type\":\"object\",\"properties\":{\"spy\":{\"type\":\"string\"},\"city\":{\"type\":\"string\"}},\"required\":[\"spy\"]}", "act");
        tool("set_city_specialists", "手动分配城市专家，如 {\"Scientist\":1}；会切到手动模式。",
                "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"},\"specialists\":{\"type\":\"object\"}},\"required\":[\"city\",\"specialists\"]}", "act");
        tool("annex_city", "把傀儡城转为直辖。",
                "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}", "act");
        tool("capture_city", "处理刚打下来的城市：action 为 puppet / annex / raze / liberate，默认 puppet。",
                "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"},\"action\":{\"type\":\"string\"}},\"required\":[\"city\"]}", "act");
    }

    public static Map<String, Tool> tools() { return TOOLS; }

    // ---------- Intent 入口 ----------

    public static String handleIntent(String body, GameInfo gameInfo, UncivGameRef ref, Civilization actor) {
        Map<String, Object> in;
        try {
            in = Json.obj(Json.parse(body));
        } catch (Exception e) {
            return error("BAD_JSON", "请求体不是合法 JSON: " + e.getMessage(), null);
        }

        String id = Json.str(in, "id");
        String type = Json.str(in, "type");
        String target = Json.str(in, "target");
        Integer turn = Json.integer(in, "turn");
        Map<String, Object> params = Json.obj(in.get("params"));

        if (type == null) return error("BAD_INTENT", "缺少 type", null);
        if (!TOOLS.containsKey(type)) return error("UNKNOWN_ACTION", "没有这个动作: " + type, null);

        Tool t = TOOLS.get(type);
        if ("read".equals(t.kind)) {
            return error("NOT_AN_ACTION", type + " 是只读工具，请用对应查询接口", null);
        }

        String fingerprint = type + "|" + (target == null ? "" : target) + "|" + Json.write(params);
        if (id != null && appliedIds.containsKey(id)) {
            if (fingerprint.equals(appliedIds.get(id)))
                return "{\"ok\":true,\"intent\":" + q(id) + ",\"duplicate\":true,\"events\":[]}";
            return error("ID_REUSED", "这个 id 已经用在另一个动作上了，换一个 id", null);
        }

        if (turn != null && turn != gameInfo.getTurns()) {
            return error("STALE", "回合已推进，你看到的是第 " + turn + " 回合，当前是 " + gameInfo.getTurns(), null);
        }

        List<String> events = new ArrayList<>();
        try {
            switch (type) {
                case "move_unit":  applyMove(gameInfo, actor, target, params, events); break;
                case "found_city": applyFoundCity(gameInfo, actor, target, events); break;
                case "set_city_production": applyProduction(gameInfo, actor, target, params, events); break;
                case "set_research": applyResearch(gameInfo, actor, target, params, events); break;
                case "attack": applyAttack(gameInfo, actor, target, params, events, false); break;
                case "bombard": applyAttack(gameInfo, actor, target, params, events, true); break;
                case "promote_unit": applyPromote(gameInfo, actor, target, params, events); break;
                case "unit_action": applyUnitAction(gameInfo, actor, target, params, events); break;
                case "set_city_focus": applyCityFocus(gameInfo, actor, target, params, events); break;
                case "purchase_construction": applyPurchase(gameInfo, actor, target, params, events); break;
                case "declare_war": applyDeclareWar(gameInfo, actor, target, params, events); break;
                case "make_peace": applyMakePeace(gameInfo, actor, target, params, events); break;
                case "adopt_policy": applyAdoptPolicy(gameInfo, actor, params, events); break;
                case "end_turn": applyEndTurn(gameInfo, actor, ref, events); break;
                case "propose_trade": applyProposeTrade(gameInfo, actor, target, params, events); break;
                case "respond_trade": applyRespondTrade(gameInfo, actor, target, params, events); break;
                case "choose_beliefs": applyChooseBeliefs(gameInfo, actor, target, params, events); break;
                case "gift_city_state": applyGiftCityState(gameInfo, actor, target, params, events); break;
                case "pledge_protect": applyPledgeProtect(gameInfo, actor, target, params, events); break;
                case "set_worked_tiles": applySetWorkedTiles(gameInfo, actor, target, params, events); break;
                case "nuke": applyNuke(gameInfo, actor, target, params, events); break;
                case "upgrade_unit": applyUpgradeUnit(gameInfo, actor, target, params, events); break;
                case "spy_move": applySpyMove(gameInfo, actor, params, events); break;
                case "set_city_specialists": applySetSpecialists(gameInfo, actor, target, params, events); break;
                case "annex_city": applyAnnexCity(gameInfo, actor, target, params, events); break;
                case "capture_city": applyCaptureCity(gameInfo, actor, target, params, events); break;
                default: return error("UNKNOWN_ACTION", "动作未实现: " + type, null);
            }
        } catch (Fail f) {
            return error(f.code, f.getMessage(), f.candidates);
        } catch (Throwable e) {
            return error("INTERNAL", String.valueOf(e), null);
        }

        if (id != null) {
            appliedIds.put(id, fingerprint);
            if (appliedIds.size() > 1000) {
                Iterator<String> it = appliedIds.keySet().iterator();
                it.next();
                it.remove();
            }
        }
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"intent\":")
                .append(id == null ? "null" : q(id))
                .append(",\"turn\":").append(gameInfo.getTurns())
                .append(",\"events\":[").append(String.join(",", events)).append("]}");
        return sb.toString();
    }

    /** 让 Kernel 能推进回合而不直接依赖 Agent 的内部字段 */
    public interface UncivGameRef {
        void advanceTurn(GameInfo gameInfo, Civilization actor);
    }

    private static final class Fail extends RuntimeException {
        final String code;
        final List<String> candidates;
        Fail(String code, String message) { this(code, message, null); }
        Fail(String code, String message, List<String> candidates) {
            super(message);
            this.code = code;
            this.candidates = candidates;
        }
    }

    // ---------- 各动作 ----------

    private static void applyMove(GameInfo gi, Civilization actor, String target, Map<String, Object> params, List<String> events) {
        MapUnit unit = requireOwnUnit(gi, actor, target);
        Integer x = Json.integer(params, "x");
        Integer y = Json.integer(params, "y");
        if (x == null || y == null) throw new Fail("BAD_PARAMS", "需要 x 与 y");

        Tile dest = tileAt(gi, x, y);
        if (dest == null) throw new Fail("ILLEGAL_TARGET", "没有这个格子: " + x + "," + y);

        HexCoord from = unit.getTile().getPosition();
        if (unit.getCurrentMovement() <= 0.05f)
            throw new Fail("NOT_AVAILABLE", "这个单位这回合已经没有移动力了");

        boolean reachableAnywhere;
        try {
            reachableAnywhere = unit.getMovement().canReach(dest);
        } catch (Throwable e) {
            reachableAnywhere = false;
        }
        if (!reachableAnywhere)
            throw new Fail("ILLEGAL_TARGET", "这个单位到不了 " + x + "," + y + "（海陆不通或没有可走的路）");

        boolean reachable;
        try {
            reachable = unit.getMovement().canReachInCurrentTurn(dest);
        } catch (Throwable e) {
            reachable = false;
        }
        try {
            if (reachable) unit.getMovement().moveToTile(dest, true);
            else unit.getMovement().headTowards(dest);   // 跨回合寻路：这一回合能走多远走多远
        } catch (Throwable e) {
            throw new Fail("ILLEGAL_TARGET", "走不过去: " + e.getMessage());
        }
        HexCoord to = unit.getTile().getPosition();
        boolean arrived = to.getX() == dest.getPosition().getX() && to.getY() == dest.getPosition().getY();
        int left = unit.getTile().aerialDistanceTo(dest);
        events.add("{\"type\":\"unit_moved\",\"unit\":" + q("unit-" + unit.getId())
                + ",\"from\":[" + from.getX() + "," + from.getY() + "]"
                + ",\"to\":[" + to.getX() + "," + to.getY() + "]"
                + ",\"target\":[" + dest.getPosition().getX() + "," + dest.getPosition().getY() + "]"
                + ",\"arrived\":" + arrived
                + ",\"tilesLeft\":" + left
                + ",\"moves\":" + unit.getCurrentMovement() + "}");
    }

    private static void applyFoundCity(GameInfo gi, Civilization actor, String target, List<String> events) {
        MapUnit unit = requireOwnUnit(gi, actor, target);
        int before = unit.getCiv().getCities().size();
        boolean ok;
        try {
            ok = UnitActions.INSTANCE.invokeUnitAction(unit, UnitActionType.FoundCity, null);
        } catch (Throwable e) {
            throw new Fail("ILLEGAL_TARGET", "建城失败: " + e.getMessage());
        }
        if (!ok) {
            StringBuilder why = new StringBuilder("这里不能建城");
            try {
                Tile t = unit.getTile();
                if (t.isWater()) why.append("：水面");
                else if (t.isImpassible()) why.append("：不可通行地形");
                City nearest = null;
                int best = Integer.MAX_VALUE;
                for (City c : actor.getCities()) {
                    int d = t.aerialDistanceTo(c.getCenterTile());
                    if (d < best) { best = d; nearest = c; }
                }
                if (nearest != null)
                    why.append("：离最近的城 ").append(nearest.getName()).append(" 只有 ").append(best).append(" 格");
            } catch (Throwable ignored) { }
            throw new Fail("NOT_AVAILABLE", why.toString());
        }
        List<City> cities = unit.getCiv().getCities();
        if (cities.size() > before) {
            City c = cities.get(cities.size() - 1);
            events.add("{\"type\":\"city_founded\",\"city\":" + q("city-" + c.getId())
                    + ",\"name\":" + q(c.getName())
                    + ",\"at\":[" + c.getLocation().getX() + "," + c.getLocation().getY() + "]}");
        } else {
            events.add("{\"type\":\"city_founded\"}");
        }
    }

    /** 小地图边缘外 TileMap.get 会抛异常，统一收口。 */
    static Tile tileAt(GameInfo gi, int x, int y) {
        try {
            return gi.getTileMap().get(new HexCoord(x, y));
        } catch (Throwable ignored) {
            return null;
        }
    }

    static List<String> buildableNow(GameInfo gi, City city) {
        List<String> out = new ArrayList<>();
        Ruleset rs = gi.getRuleset();
        List<String> pool = new ArrayList<>();
        pool.addAll(rs.getBuildings().keySet());
        pool.addAll(rs.getUnits().keySet());
        for (String name : pool) {
            if (out.size() >= 25) break;
            try {
                com.unciv.models.ruleset.IConstruction con = city.getCityConstructions().getConstruction$unciv_core(name);
                if (con != null && city.getCityConstructions().canAddToQueue(con,
                        city.getCityConstructions().getConstructionQueue())) out.add(name);
            } catch (Throwable ignored) { }
        }
        return out;
    }

    private static void applyProduction(GameInfo gi, Civilization actor, String target, Map<String, Object> params, List<String> events) {
        City city = requireOwnCity(gi, actor, target);
        String item = Json.str(params, "item");
        if (item == null) throw new Fail("BAD_PARAMS", "需要 item");

        Ruleset rs = gi.getRuleset();
        if (!rs.getBuildings().containsKey(item) && !rs.getUnits().containsKey(item)) {
            List<String> candidates = new ArrayList<>();
            for (String k : rs.getBuildings().keySet()) {
                if (k.toLowerCase().contains(item.toLowerCase())) candidates.add(k);
                if (candidates.size() >= 8) break;
            }
            throw new Fail("UNKNOWN_ID", "没有这个可建造项: " + item, candidates);
        }

        // 先按引擎自己的规则验一遍：setCurrentConstruction 对不可建造项不会报错，只会让城市空转
        com.unciv.models.ruleset.IConstruction con = null;
        try {
            con = city.getCityConstructions().getConstruction$unciv_core(item);
        } catch (Throwable ignored) { }
        boolean buildable = false;
        if (con != null) {
            try {
                buildable = city.getCityConstructions().canAddToQueue(con, java.util.Collections.emptyList());
            } catch (Throwable ignored) { }
        }
        if (!buildable)
            throw new Fail("NOT_AVAILABLE", "现在不能生产 " + item + "（缺科技/资源/前置）", buildableNow(gi, city));

        String mode = Json.str(params, "mode");
        boolean replace = mode == null || !mode.equalsIgnoreCase("append");
        if (replace) {
            try {
                city.getCityConstructions().getConstructionQueue().clear();
            } catch (Throwable ignored) { }
        }
        city.getCityConstructions().setCurrentConstruction(item);

        Integer count = Json.integer(params, "count");
        int want = count == null ? 1 : Math.max(1, Math.min(count, 20));
        for (int i = 1; i < want; i++) {
            try {
                city.getCityConstructions().addToQueue(item, null);
            } catch (Throwable ignored) { }
        }

        // 以引擎的真实队列为准，别报一个自己都没验证过的数字
        List<String> queue = city.getCityConstructions().getConstructionQueue();
        StringBuilder qj = new StringBuilder();
        for (String qq : queue) {
            if (qj.length() > 0) qj.append(',');
            qj.append(q(qq));
        }
        events.add("{\"type\":\"production_set\",\"city\":" + q("city-" + city.getId())
                + ",\"item\":" + q(item) + ",\"mode\":" + q(replace ? "replace" : "append")
                + ",\"requested\":" + want + ",\"queued\":" + queue.size()
                + ",\"queue\":[" + qj + "]}");
    }

    private static void applyResearch(GameInfo gi, Civilization actor, String target, Map<String, Object> params, List<String> events) {
        Civilization civ = actor;
        String tech = Json.str(params, "tech");
        if (tech == null) throw new Fail("BAD_PARAMS", "需要 tech");

        Ruleset rs = gi.getRuleset();
        if (!rs.getTechnologies().containsKey(tech)) {
            List<String> candidates = new ArrayList<>();
            for (String k : rs.getTechnologies().keySet()) {
                if (k.toLowerCase().contains(tech.toLowerCase())) candidates.add(k);
                if (candidates.size() >= 8) break;
            }
            if (candidates.isEmpty()) candidates.addAll(actor.getTech().getTechsToResearch());
            throw new Fail("UNKNOWN_ID", "没有这个科技: " + tech, candidates);
        }
        boolean can;
        try {
            can = civ.getTech().canBeResearched(tech);
        } catch (Throwable e) {
            can = false;
        }
        if (!can) throw new Fail("NOT_AVAILABLE", "现在研究不了: " + tech);

        civ.getTech().getTechsToResearch().clear();
        civ.getTech().getTechsToResearch().add(tech);
        events.add("{\"type\":\"research_set\",\"tech\":" + q(tech) + "}");
    }

    private static void applyAttack(GameInfo gi, Civilization actor, String target, Map<String, Object> params,
                                    List<String> events, boolean stayOnTile) {
        MapUnit unit = requireOwnUnit(gi, actor, target);
        Integer x = Json.integer(params, "x");
        Integer y = Json.integer(params, "y");
        if (x == null || y == null) throw new Fail("BAD_PARAMS", "需要 x 与 y");

        PathsToTilesWithinTurn paths;
        ArrayList<AttackableTile> targets;
        try {
            paths = unit.getMovement().getDistanceToTiles(false, null, null, false);
            targets = TargetHelper.INSTANCE.getAttackableEnemies(unit, paths, null, stayOnTile);
        } catch (Throwable e) {
            throw new Fail("ILLEGAL_TARGET", "算不出攻击路线: " + e.getMessage());
        }

        AttackableTile hit = null;
        List<String> candidates = new ArrayList<>();
        for (AttackableTile at : targets) {
            HexCoord p = at.getTileToAttack().getPosition();
            candidates.add(p.getX() + "," + p.getY());
            if (p.getX() == x && p.getY() == y) hit = at;
        }
        if (hit == null) {
            Tile tgt = tileAt(gi, x, y);
            if (tgt != null) {
                Civilization owner = null;
                try {
                    if (tgt.isCityCenter() && tgt.getCity() != null) {
                        owner = tgt.getCity().getCiv();
                    } else {
                        Iterator<MapUnit> uit = tgt.getUnits().iterator();
                        if (uit.hasNext()) owner = uit.next().getCiv();
                    }
                } catch (Throwable ignored) { }
                if (owner != null && owner != actor && !actor.isAtWarWith(owner))
                    throw new Fail("NOT_AVAILABLE", "还没和 " + owner.getCivName() + " 宣战，先 declare_war", candidates);
            }
            throw new Fail("ILLEGAL_TARGET", "打不到 " + x + "," + y + (stayOnTile ? "（只能原地轰击）" : ""), candidates);
        }

        MapUnitCombatant attacker = new MapUnitCombatant(unit);
        if (!stayOnTile) Battle.INSTANCE.movePreparingAttack(attacker, hit, false);
        Battle.DamageDealt dmg = Battle.INSTANCE.attackOrNuke(attacker, hit);
        HexCoord at = unit.getTile().getPosition();
        events.add("{\"type\":\"attacked\",\"unit\":" + q("unit-" + unit.getId())
                + ",\"target\":[" + x + "," + y + "]"
                + ",\"at\":[" + at.getX() + "," + at.getY() + "]"
                + ",\"damageDealt\":" + (dmg == null ? 0 : dmg.getAttackerDealt())
                + ",\"damageTaken\":" + (dmg == null ? 0 : dmg.getDefenderDealt()) + "}");
    }

    private static void applyPromote(GameInfo gi, Civilization actor, String target, Map<String, Object> params, List<String> events) {
        MapUnit unit = requireOwnUnit(gi, actor, target);
        String promo = Json.str(params, "promotion");
        if (promo == null) throw new Fail("BAD_PARAMS", "需要 promotion");

        if (!unit.getPromotions().canBePromoted()) throw new Fail("NOT_AVAILABLE", "这个单位现在没有可用的晋升");

        List<String> available = new ArrayList<>();
        Iterator<Promotion> it = unit.getPromotions().getAvailablePromotions().iterator();
        while (it.hasNext()) available.add(it.next().getName());
        if (!available.contains(promo)) {
            throw new Fail("UNKNOWN_ID", "这个晋升不可选: " + promo, available);
        }
        unit.getPromotions().addPromotion(promo, false);
        events.add("{\"type\":\"promoted\",\"unit\":" + q("unit-" + unit.getId())
                + ",\"promotion\":" + q(promo) + "}");
    }

    private static void applyUnitAction(GameInfo gi, Civilization actor, String target, Map<String, Object> params, List<String> events) {
        MapUnit unit = requireOwnUnit(gi, actor, target);
        String action = Json.str(params, "action");
        if (action == null) throw new Fail("BAD_PARAMS", "需要 action");
        String actionId = Json.str(params, "id");

        UnitActionType type;
        try {
            type = UnitActionType.valueOf(action);
        } catch (IllegalArgumentException e) {
            throw new Fail("UNKNOWN_ACTION", "没有这个动作类型: " + action);
        }
        boolean ok;
        try {
            ok = UnitActions.INSTANCE.invokeUnitAction(unit, type, actionId);
        } catch (Throwable e) {
            throw new Fail("ILLEGAL_TARGET", "执行失败: " + e.getMessage());
        }
        if (!ok) throw new Fail("NOT_AVAILABLE", "这个动作现在不可用: " + action);
        events.add("{\"type\":\"unit_action\",\"unit\":" + q("unit-" + unit.getId())
                + ",\"action\":" + q(action) + "}");
    }

    private static void applyCityFocus(GameInfo gi, Civilization actor, String target, Map<String, Object> params, List<String> events) {
        City city = requireOwnCity(gi, actor, target);
        String focus = Json.str(params, "focus");
        if (focus == null) throw new Fail("BAD_PARAMS", "需要 focus");
        com.unciv.logic.city.CityFocus f;
        try {
            f = com.unciv.logic.city.CityFocus.valueOf(focus);
        } catch (IllegalArgumentException e) {
            List<String> names = new ArrayList<>();
            for (com.unciv.logic.city.CityFocus v : com.unciv.logic.city.CityFocus.values()) names.add(v.name());
            throw new Fail("UNKNOWN_ID", "没有这个焦点: " + focus, names);
        }
        city.setCityFocus(f);
        events.add("{\"type\":\"city_focus_set\",\"city\":" + q("city-" + city.getId())
                + ",\"focus\":" + q(f.name()) + "}");
    }

    private static void applyPurchase(GameInfo gi, Civilization actor, String target, Map<String, Object> params, List<String> events) {
        City city = requireOwnCity(gi, actor, target);
        String item = Json.str(params, "item");
        if (item == null) throw new Fail("BAD_PARAMS", "需要 item");

        com.unciv.models.ruleset.Ruleset rs = gi.getRuleset();
        if (!rs.getBuildings().containsKey(item) && !rs.getUnits().containsKey(item)) {
            List<String> candidates = new ArrayList<>();
            for (String k : rs.getBuildings().keySet()) {
                if (k.toLowerCase().contains(item.toLowerCase())) candidates.add(k);
                if (candidates.size() >= 8) break;
            }
            throw new Fail("UNKNOWN_ID", "没有这个可购买项: " + item, candidates);
        }

        String currency = Json.str(params, "currency");
        com.unciv.models.stats.Stat stat = com.unciv.models.stats.Stat.Gold;
        if (currency != null) {
            try {
                stat = com.unciv.models.stats.Stat.valueOf(currency);
            } catch (IllegalArgumentException e) {
                throw new Fail("UNKNOWN_ID", "没有这种货币: " + currency,
                        java.util.Arrays.asList("Gold", "Faith"));
            }
            if (stat != com.unciv.models.stats.Stat.Gold && stat != com.unciv.models.stats.Stat.Faith)
                throw new Fail("NOT_AVAILABLE", "只能用 Gold 或 Faith 购买");
        }

        // purchaseConstruction 自己不做可购买性检查（UI 是按钮禁用），这里先按引擎规则验一遍
        com.unciv.models.ruleset.INonPerpetualConstruction buyable = null;
        Integer price = null;
        try {
            com.unciv.models.ruleset.IConstruction con = city.getCityConstructions().getConstruction$unciv_core(item);
            if (con instanceof com.unciv.models.ruleset.INonPerpetualConstruction) {
                buyable = (com.unciv.models.ruleset.INonPerpetualConstruction) con;
                price = buyable.getStatBuyCost(city, stat);
            }
        } catch (Throwable ignored) { }
        if (buyable == null) throw new Fail("NOT_AVAILABLE", item + " 不能购买（不是可购买的项目）");
        if (price == null) throw new Fail("NOT_AVAILABLE", item + " 不能用 " + stat.name() + " 购买");
        boolean alreadyBuilt = false;
        try {
            alreadyBuilt = city.getCityConstructions().isBuilt(item);
        } catch (Throwable ignored) { }
        if (alreadyBuilt)
            throw new Fail("NOT_AVAILABLE", item + " 已经建过了");

        boolean purchasable = true;
        try {
            purchasable = buyable.isPurchasable(city.getCityConstructions());
        } catch (Throwable ignored) { }
        if (!purchasable)
            throw new Fail("NOT_AVAILABLE", item + " 现在买不了：缺前置（科技/建筑）");
        if (!city.getCityConstructions().isConstructionPurchaseAllowed(buyable, stat, price)) {
            int have = stat == com.unciv.models.stats.Stat.Faith
                    ? actor.getReligionManager().getStoredFaith() : actor.getGold();
            if (have < price)
                throw new Fail("NOT_AVAILABLE", "买不起 " + item + "：要 " + price + " " + stat.name()
                        + "，现有 " + have);
            throw new Fail("NOT_AVAILABLE", "现在不能买 " + item
                    + "：傀儡城 / 抵抗中 / 城里放不下新单位");
        }

        boolean ok;
        try {
            ok = city.getCityConstructions().purchaseConstruction(buyable, -1, false, stat, null);
        } catch (Throwable e) {
            throw new Fail("NOT_AVAILABLE", "购买失败: " + e.getMessage());
        }
        if (!ok) throw new Fail("NOT_AVAILABLE", "购买没有生效: " + item);
        events.add("{\"type\":\"purchased\",\"city\":" + q("city-" + city.getId())
                + ",\"item\":" + q(item) + ",\"currency\":" + q(stat.name())
                + ",\"goldLeft\":" + actor.getGold()
                + ",\"faithLeft\":" + actor.getReligionManager().getStoredFaith() + "}");
    }

    private static Civilization requireOtherCiv(GameInfo gi, Civilization me, String nameOrId) {
        if (nameOrId == null) throw new Fail("BAD_PARAMS", "需要 civ");
        List<String> candidates = new ArrayList<>();
        for (Civilization c : gi.getCivilizations()) {
            if (c == me) continue;
            candidates.add(c.getCivName());
            if (c.getCivName().equals(nameOrId) || c.getCivID().equals(nameOrId)) return c;
        }
        throw new Fail("UNKNOWN_ID", "没有这个文明: " + nameOrId, candidates);
    }

    private static void applyDeclareWar(GameInfo gi, Civilization actor, String target, Map<String, Object> params, List<String> events) {
        Civilization other = requireOtherCiv(gi, actor, civRef(params, target));
        com.unciv.logic.civilization.diplomacy.DiplomacyManager dm =
                actor.getDiplomacyManager(other);
        if (dm == null) throw new Fail("NOT_AVAILABLE", "还没遇到这个文明");
        if (actor.isAtWarWith(other)) throw new Fail("NOT_AVAILABLE", "已经和 " + other.getCivName() + " 交战");
        if (!dm.canDeclareWar()) throw new Fail("NOT_AVAILABLE", "现在不能宣战");
        dm.declareWar(new com.unciv.logic.civilization.diplomacy.DeclareWarReason(
                com.unciv.logic.civilization.diplomacy.WarType.DirectWar, null));
        events.add("{\"type\":\"war_declared\",\"civ\":" + q(other.getCivName()) + "}");
    }

    private static void applyMakePeace(GameInfo gi, Civilization actor, String target, Map<String, Object> params, List<String> events) {
        Civilization other = requireOtherCiv(gi, actor, civRef(params, target));
        com.unciv.logic.civilization.diplomacy.DiplomacyManager dm =
                actor.getDiplomacyManager(other);
        if (dm == null) throw new Fail("NOT_AVAILABLE", "还没遇到这个文明");
        if (!actor.isAtWarWith(other)) throw new Fail("NOT_AVAILABLE", "和 " + other.getCivName() + " 本来就不在交战");
        dm.makePeace();
        events.add("{\"type\":\"peace_made\",\"civ\":" + q(other.getCivName()) + "}");
    }

    private static void applyAdoptPolicy(GameInfo gi, Civilization actor, Map<String, Object> params, List<String> events) {
        String name = Json.str(params, "policy");
        if (name == null) throw new Fail("BAD_PARAMS", "需要 policy");

        com.unciv.models.ruleset.Policy policy = gi.getRuleset().getPolicies().get(name);
        if (policy == null) {
            List<String> candidates = new ArrayList<>();
            for (String k : gi.getRuleset().getPolicies().keySet()) {
                if (k.toLowerCase().contains(name.toLowerCase())) candidates.add(k);
                if (candidates.size() >= 8) break;
            }
            throw new Fail("UNKNOWN_ID", "没有这个政策: " + name, candidates);
        }
        if (actor.getPolicies().isAdopted(name)) throw new Fail("NOT_AVAILABLE", "已经采纳过 " + name);
        try {
            actor.getPolicies().adopt(policy, false);
        } catch (Throwable e) {
            throw new Fail("NOT_AVAILABLE", "采纳失败: " + e.getMessage());
        }
        events.add("{\"type\":\"policy_adopted\",\"policy\":" + q(name) + "}");
    }

    private static void applyEndTurn(GameInfo gi, Civilization actor, UncivGameRef ref, List<String> events) {
        // 交回合前把"你大概忘了"的东西说出来，避免因为没看见而白丢一回合
        List<String> warnings = new ArrayList<>();
        int idle = 0;
        Iterator<MapUnit> uit = actor.getUnits().getCivUnits().iterator();
        while (uit.hasNext()) {
            MapUnit u = uit.next();
            if (u.getCurrentMovement() > 0.1f && !u.isTransported()) idle++;
        }
        if (idle > 0) warnings.add(idle + " 个单位还有移动力");
        int idleCities = 0;
        for (City c : actor.getCities()) {
            if (c.isPuppet() || c.isInResistance()) continue;
            String item = c.getCityConstructions().getCurrentConstruction().getName();
            if (item == null || item.isEmpty() || item.equals("Nothing")) idleCities++;
        }
        if (idleCities > 0) warnings.add(idleCities + " 座城没有在生产任何东西");
        int pending = 0;
        for (com.unciv.logic.civilization.PopupAlert a : actor.getPopupAlerts())
            if (a.getType() == com.unciv.logic.civilization.AlertType.CityConquered) pending++;
        if (pending > 0) warnings.add(pending + " 座刚打下的城还没决定吞并/傀儡");

        int before = gi.getTurns();
        ref.advanceTurn(gi, actor);
        StringBuilder w = new StringBuilder();
        for (String s : warnings) {
            if (w.length() > 0) w.append(',');
            w.append(q(s));
        }
        events.add("{\"type\":\"turn_advanced\",\"from\":" + before + ",\"to\":" + gi.getTurns()
                + ",\"currentCiv\":" + q(actor.getCivName())
                + ",\"warnings\":[" + w + "]}");
    }

    /** City 上 workedTiles 属性与 getWorkedTiles() 函数在 JVM 里同名，Java 里取不了，这里换个路子。 */
    static List<Tile> workedTilesOf(City city) {
        List<Tile> out = new ArrayList<>();
        Iterator<Tile> it = city.getWorkableTiles().iterator();
        while (it.hasNext()) {
            Tile t = it.next();
            if (city.isWorked(t)) out.add(t);
        }
        return out;
    }

    /** 文明引用：优先 params.civ，其次 target（允许直接把文明名当 target） */
    private static String civRef(Map<String, Object> params, String target) {
        String civ = Json.str(params, "civ");
        if (civ != null) return civ;
        if (target != null && !target.startsWith("unit-") && !target.startsWith("city-")) return target;
        return null;
    }

    // ---------- 交易 ----------

    static String offersJson(Iterable<TradeOffer> offers) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (TradeOffer o : offers) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"name\":").append(q(o.getName()))
              .append(",\"type\":").append(q(o.getType().name()))
              .append(",\"amount\":").append(o.getAmount())
              .append(",\"duration\":").append(o.getDuration()).append('}');
        }
        return sb.append(']').toString();
    }

    static String quotedList(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(q(values.get(i)));
        }
        return sb.toString();
    }

    /**
     * 把 {"name":..,"type":..,"amount":..} 落到 staged 里。
     * 只接受 TradeLogic 当场算出来的可用项，避免造出引擎不认的报价。
     */
    private static void stageOffer(TradeOffersList available, TradeOffersList staged, Object spec, List<String> bad) {
        Map<String, Object> m = Json.obj(spec);
        String name = Json.str(m, "name");
        if (name == null) { bad.add("<缺 name>"); return; }
        String typeName = Json.str(m, "type");
        Integer amount = Json.integer(m, "amount");

        TradeOffer found = null;
        for (TradeOffer o : available) {
            if (!o.getName().equals(name)) continue;
            if (typeName != null && !o.getType().name().equalsIgnoreCase(typeName)) continue;
            found = o;
            break;
        }
        if (found == null) {
            bad.add(typeName == null ? name : name + " (" + typeName + ")");
            return;
        }

        int amt = amount == null ? Math.max(1, found.getAmount()) : amount;
        if (amt <= 0) amt = 1;
        switch (found.getType()) {
            case Gold:
            case Gold_Per_Turn:
            case Luxury_Resource:
            case Strategic_Resource:
            case Stockpiled_Resource:
                amt = Math.min(amt, Math.max(1, found.getAmount()));
                break;
            default:
                amt = 1;
        }
        staged.add(new TradeOffer(found.getName(), found.getType(), amt, found.getDuration()));
    }

    private static void applyProposeTrade(GameInfo gi, Civilization actor, String target, Map<String, Object> params, List<String> events) {
        Civilization other = requireOtherCiv(gi, actor, civRef(params, target));
        if (actor.getDiplomacyManager(other) == null)
            throw new Fail("NOT_AVAILABLE", "还没遇到 " + other.getCivName());

        TradeLogic tl = new TradeLogic(actor, other);
        List<String> bad = new ArrayList<>();
        for (Object o : Json.arr(params.get("give")))
            stageOffer(tl.getOurAvailableOffers(), tl.getCurrentTrade().getOurOffers(), o, bad);
        for (Object o : Json.arr(params.get("want")))
            stageOffer(tl.getTheirAvailableOffers(), tl.getCurrentTrade().getTheirOffers(), o, bad);
        if (!bad.isEmpty())
            throw new Fail("NOT_AVAILABLE", "这些不在可交易范围内: " + String.join("、", bad), bad);

        Trade trade = tl.getCurrentTrade();
        if (trade.getOurOffers().isEmpty() && trade.getTheirOffers().isEmpty())
            throw new Fail("BAD_PARAMS", "give 和 want 都是空的");

        other.getTradeRequests().removeIf(r -> actor.getCivID().equals(r.getRequestingCiv()));
        other.getTradeRequests().add(new TradeRequest(actor.getCivID(), trade.reverse()));
        actor.getCache().updateCivResources();

        events.add("{\"type\":\"trade_proposed\",\"civ\":" + q(other.getCivName())
                + ",\"give\":" + offersJson(trade.getOurOffers())
                + ",\"want\":" + offersJson(trade.getTheirOffers()) + "}");
    }

    private static void applyRespondTrade(GameInfo gi, Civilization actor, String target, Map<String, Object> params, List<String> events) {
        Civilization other = requireOtherCiv(gi, actor, civRef(params, target));
        Object acc = params.get("accept");
        boolean accept = !(acc instanceof Boolean) || (Boolean) acc;
        Integer idxParam = Json.integer(params, "index");
        int idx = idxParam == null ? 0 : idxParam;

        List<TradeRequest> mine = new ArrayList<>();
        for (TradeRequest r : actor.getTradeRequests())
            if (other.getCivID().equals(r.getRequestingCiv())) mine.add(r);
        if (mine.isEmpty())
            throw new Fail("NOT_AVAILABLE", other.getCivName() + " 没有待回应的交易");
        if (idx < 0 || idx >= mine.size())
            throw new Fail("BAD_PARAMS", "index 越界，共有 " + mine.size() + " 条");

        TradeRequest req = mine.get(idx);
        String give = offersJson(req.getTrade().getTheirOffers());
        String want = offersJson(req.getTrade().getOurOffers());

        if (accept) {
            TradeLogic tl = new TradeLogic(actor, other);
            tl.getCurrentTrade().set(req.getTrade());
            try {
                tl.acceptTrade(true);
            } catch (Throwable e) {
                throw new Fail("NOT_AVAILABLE", "成交失败: " + e.getMessage());
            }
        } else {
            req.decline(actor);
        }
        actor.getTradeRequests().remove(req);

        events.add("{\"type\":\"" + (accept ? "trade_accepted" : "trade_declined") + "\",\"civ\":"
                + q(other.getCivName()) + ",\"give\":" + give + ",\"want\":" + want + "}");
    }

    // ---------- 宗教 ----------

    private static List<Belief> resolveBeliefs(GameInfo gi, List<Object> raw) {
        Ruleset rs = gi.getRuleset();
        List<Belief> out = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        for (Object o : raw) {
            String name = o instanceof String ? (String) o : null;
            Belief b = name == null ? null : rs.getBeliefs().get(name);
            if (b == null) { bad.add(String.valueOf(o)); continue; }
            out.add(b);
        }
        if (!bad.isEmpty()) {
            List<String> cand = new ArrayList<>();
            for (String k : rs.getBeliefs().keySet()) {
                for (String b : bad) {
                    if (k.toLowerCase().contains(b.toLowerCase())) { cand.add(k); break; }
                }
                if (cand.size() >= 10) break;
            }
            throw new Fail("UNKNOWN_ID", "没有这些信念: " + String.join("、", bad), cand);
        }
        return out;
    }

    private static Map<String, Integer> expectedBeliefs(ReligionManager rm, ReligionState state) {
        Map<String, Integer> expected = new LinkedHashMap<>();
        if (state == ReligionState.None || state == ReligionState.Pantheon) {
            expected.put(BeliefType.Pantheon.name(), 1);
            return expected;
        }
        Counter<BeliefType> counter = state == ReligionState.EnhancingReligion
                ? rm.getBeliefsToChooseAtEnhancing()
                : rm.getBeliefsToChooseAtFounding();
        for (Map.Entry<BeliefType, Integer> e : counter.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) expected.put(e.getKey().name(), e.getValue());
        }
        return expected;
    }

    private static void applyChooseBeliefs(GameInfo gi, Civilization actor, String target, Map<String, Object> params, List<String> events) {
        if (!gi.isReligionEnabled()) throw new Fail("NOT_AVAILABLE", "这个规则集没开宗教");
        ReligionManager rm = actor.getReligionManager();

        // 带 unit 时先把大先知用掉，一步完成立教
        String unitRef = target != null ? target : Json.str(params, "unit");
        if (unitRef != null && rm.getReligionState() != ReligionState.FoundingReligion) {
            MapUnit prophet = requireOwnUnit(gi, actor, unitRef);
            boolean ok;
            try {
                ok = UnitActions.INSTANCE.invokeUnitAction(prophet, UnitActionType.FoundReligion, null);
            } catch (Throwable e) {
                throw new Fail("NOT_AVAILABLE", "立教失败: " + e.getMessage());
            }
            if (!ok) throw new Fail("NOT_AVAILABLE", prophet.getName() + " 现在不能立教");
        }

        List<Belief> chosen = resolveBeliefs(gi, Json.arr(params.get("beliefs")));

        ReligionState state = rm.getReligionState();
        if (state == ReligionState.None || state == ReligionState.Pantheon) {
            if (!rm.canFoundOrExpandPantheon())
                throw new Fail("NOT_AVAILABLE", "还不能立万神殿：信仰 " + rm.getStoredFaith()
                        + "/" + rm.faithForPantheon(0));
        } else if (state != ReligionState.FoundingReligion && state != ReligionState.EnhancingReligion) {
            throw new Fail("NOT_AVAILABLE", "现在不能选信念，状态是 " + state.name());
        }

        Map<String, Integer> expected = expectedBeliefs(rm, state);
        int total = 0;
        List<String> want = new ArrayList<>();
        for (Map.Entry<String, Integer> e : expected.entrySet()) {
            total += e.getValue();
            want.add(e.getKey() + "x" + e.getValue());
        }
        if (chosen.size() != total)
            throw new Fail("BAD_PARAMS", "这次要选 " + total + " 个信念（" + String.join(" ", want)
                    + "），收到 " + chosen.size() + " 个", want);

        String name = Json.str(params, "name");
        String displayName = Json.str(params, "displayName");
        if (state == ReligionState.FoundingReligion) {
            if (name == null) throw new Fail("BAD_PARAMS", "立国教需要 name（宗教标识）");
            if (displayName == null) displayName = name;
            try {
                rm.foundReligion$unciv_core(displayName, name);
            } catch (Throwable e) {
                throw new Fail("NOT_AVAILABLE", "立教失败: " + e.getMessage());
            }
        }
        try {
            rm.chooseBeliefs(chosen, rm.usingFreeBeliefs());
        } catch (Throwable e) {
            throw new Fail("NOT_AVAILABLE", "选信念失败: " + e.getMessage());
        }

        Religion rel = rm.getReligion();
        List<String> names = new ArrayList<>();
        for (Belief b : chosen) names.add(b.getName());
        events.add("{\"type\":\"beliefs_chosen\",\"state\":" + q(rm.getReligionState().name())
                + ",\"religion\":" + (rel == null ? "null"
                        : q(rel.getDisplayName() == null ? rel.getName() : rel.getDisplayName()))
                + ",\"beliefs\":[" + quotedList(names) + "]}");
    }

    // ---------- 城邦 ----------

    private static void applyGiftCityState(GameInfo gi, Civilization actor, String target, Map<String, Object> params, List<String> events) {
        Civilization cs = requireOtherCiv(gi, actor, civRef(params, target));
        if (!cs.isCityState()) throw new Fail("NOT_AVAILABLE", cs.getCivName() + " 不是城邦");
        Integer gold = Json.integer(params, "gold");
        int amount = gold == null ? 250 : gold;
        if (amount <= 0) throw new Fail("BAD_PARAMS", "gold 必须是正数");
        if (actor.getGold() < amount)
            throw new Fail("NOT_AVAILABLE", "金币不够，现在只有 " + actor.getGold());

        int gain = cs.getCityStateFunctions().influenceGainedByGift(actor, amount);
        try {
            cs.getCityStateFunctions().receiveGoldGift(actor, amount);
        } catch (Throwable e) {
            throw new Fail("NOT_AVAILABLE", "送礼失败: " + e.getMessage());
        }
        int influence = 0;
        // 影响力存在城邦那一侧的 DiplomacyManager 上
        if (cs.getDiplomacyManager(actor) != null)
            influence = (int) cs.getDiplomacyManager(actor).getInfluence();
        events.add("{\"type\":\"city_state_gift\",\"civ\":" + q(cs.getCivName())
                + ",\"gold\":" + amount + ",\"influenceGained\":" + gain
                + ",\"influence\":" + influence + "}");
    }

    private static void applyPledgeProtect(GameInfo gi, Civilization actor, String target, Map<String, Object> params, List<String> events) {
        Civilization cs = requireOtherCiv(gi, actor, civRef(params, target));
        if (!cs.isCityState()) throw new Fail("NOT_AVAILABLE", cs.getCivName() + " 不是城邦");
        Object p = params.get("protect");
        boolean protect = !(p instanceof Boolean) || (Boolean) p;

        CityStateFunctions f = cs.getCityStateFunctions();
        if (protect) {
            if (!f.otherCivCanPledgeProtection(actor))
                throw new Fail("NOT_AVAILABLE", "现在不能承诺保护 " + cs.getCivName());
            f.addProtectorCiv(actor);
        } else {
            if (!f.otherCivCanWithdrawProtection(actor)) {
                com.unciv.logic.civilization.diplomacy.DiplomacyManager dm = cs.getDiplomacyManager(actor);
                boolean isProtector = dm != null && dm.getDiplomaticStatus()
                        == com.unciv.logic.civilization.diplomacy.DiplomaticStatus.Protector;
                throw new Fail("NOT_AVAILABLE", isProtector
                        ? "刚承诺保护，10 回合内不能撤回"
                        : "现在没有对 " + cs.getCivName() + " 的保护承诺");
            }
            f.removeProtectorCiv(actor, false);
        }
        events.add("{\"type\":\"" + (protect ? "pledged_protection" : "withdrew_protection")
                + "\",\"civ\":" + q(cs.getCivName()) + "}");
    }

    // ---------- 地块分配 ----------

    private static void applySetWorkedTiles(GameInfo gi, Civilization actor, String target, Map<String, Object> params, List<String> events) {
        City city = requireOwnCity(gi, actor, target);
        List<Object> raw = Json.arr(params.get("tiles"));
        if (raw.isEmpty()) throw new Fail("BAD_PARAMS", "需要 tiles 数组，元素是 [x,y]");

        LinkedHashSet<HexCoord> want = new LinkedHashSet<>();
        for (Object o : raw) {
            List<Object> pair = Json.arr(o);
            if (pair.size() < 2 || !(pair.get(0) instanceof Number) || !(pair.get(1) instanceof Number))
                throw new Fail("BAD_PARAMS", "tiles 里每一项要是 [x,y]");
            want.add(new HexCoord(((Number) pair.get(0)).intValue(), ((Number) pair.get(1)).intValue()));
        }

        List<String> bad = new ArrayList<>();
        for (HexCoord c : want) {
            Tile t = tileAt(gi, c.getX(), c.getY());
            if (t == null) { bad.add(c.getX() + "," + c.getY()); continue; }
            boolean workable = false;
            Iterator<Tile> it = city.getWorkableTiles().iterator();
            while (it.hasNext()) if (it.next() == t) { workable = true; break; }
            if (!workable) bad.add(c.getX() + "," + c.getY());
        }
        if (!bad.isEmpty())
            throw new Fail("ILLEGAL_TARGET", "这些格子不能被这座城工作: " + String.join(" ", bad), bad);

        int pop = city.getPopulation().getPopulation();
        if (want.size() > pop)
            throw new Fail("BAD_PARAMS", "人口只有 " + pop + "，不能同时工作 " + want.size() + " 格");

        List<Tile> current = workedTilesOf(city);
        for (Tile t : current)
            if (!want.contains(t.getPosition())) city.stopWorkingTile(t);

        for (HexCoord c : want) {
            Tile t = tileAt(gi, c.getX(), c.getY());
            if (t == null || city.isWorked(t)) continue;
            try {
                city.workTile(t);
            } catch (Throwable e) {
                throw new Fail("NOT_AVAILABLE", "分配 " + c.getX() + "," + c.getY() + " 失败: " + e.getMessage());
            }
        }

        Object lockObj = params.get("lock");
        boolean lock = !(lockObj instanceof Boolean) || (Boolean) lockObj;
        if (lock) {
            for (HexCoord c : want) {
                Tile t = tileAt(gi, c.getX(), c.getY());
                if (t != null && city.isWorked(t)) city.lockTile(t);
            }
        }

        Object fillObj = params.get("autoFill");
        boolean autoFill = !(fillObj instanceof Boolean) || (Boolean) fillObj;
        if (autoFill) city.getPopulation().autoAssignPopulation$unciv_core();

        List<String> worked = new ArrayList<>();
        for (Tile t : workedTilesOf(city)) {
            HexCoord p = t.getPosition();
            worked.add("[" + p.getX() + "," + p.getY() + "]");
        }
        events.add("{\"type\":\"worked_tiles_set\",\"city\":" + q("city-" + city.getId())
                + ",\"worked\":[" + String.join(",", worked) + "]}");
    }

    // ---------- 核弹 ----------

    private static void applyNuke(GameInfo gi, Civilization actor, String target, Map<String, Object> params, List<String> events) {
        MapUnit unit = requireOwnUnit(gi, actor, target);
        Integer x = Json.integer(params, "x");
        Integer y = Json.integer(params, "y");
        if (x == null || y == null) throw new Fail("BAD_PARAMS", "需要 x 与 y");
        if (!unit.isNuclearWeapon()) throw new Fail("NOT_AVAILABLE", unit.getName() + " 不是核武器");

        Tile t = tileAt(gi, x, y);
        if (t == null) throw new Fail("ILLEGAL_TARGET", "没有这个格子: " + x + "," + y);
        MapUnitCombatant combatant = new MapUnitCombatant(unit);
        if (!Nuke.INSTANCE.mayUseNuke(combatant, t))
            throw new Fail("ILLEGAL_TARGET", "打不到这个格子（射程 " + unit.getRange() + "）或者会被外交规则挡住");

        try {
            Nuke.INSTANCE.NUKE(combatant, t);
        } catch (Throwable e) {
            throw new Fail("NOT_AVAILABLE", "核打击失败: " + e.getMessage());
        }
        events.add("{\"type\":\"nuke\",\"unit\":" + q("unit-" + unit.getId())
                + ",\"at\":[" + x + "," + y + "]}");
    }

    // ---------- 单位升级 ----------

    private static void applyUpgradeUnit(GameInfo gi, Civilization actor, String target, Map<String, Object> params, List<String> events) {
        MapUnit unit = requireOwnUnit(gi, actor, target);
        String to = Json.str(params, "to");

        List<PossibleUpgrade> ups = unit.getUpgrade().getUpgradesOfKind(false);
        List<String> names = new ArrayList<>();
        PossibleUpgrade pick = null;
        for (PossibleUpgrade u : ups) {
            if (!u.isShownToPlayer()) continue;
            names.add(u.getUpgradedUnit().getName());
            if (to != null && u.getUpgradedUnit().getName().equalsIgnoreCase(to)) pick = u;
        }
        if (pick == null && to == null) {
            if (names.size() == 1) {
                for (PossibleUpgrade u : ups) if (u.isShownToPlayer()) pick = u;
            } else if (names.isEmpty()) {
                throw new Fail("NOT_AVAILABLE", unit.getName() + " 现在没有可升级的目标");
            } else {
                throw new Fail("BAD_PARAMS", "有多个升级目标，请用 to 指定", names);
            }
        }
        if (pick == null) throw new Fail("UNKNOWN_ID", "不能升级到 " + to, names);

        if (!unit.getUpgrade().canPerformUpgrade(pick, false))
            throw new Fail("NOT_AVAILABLE", "升级条件不满足（要 " + pick.getGoldCostOfUpgrade()
                    + " 金币，现在 " + actor.getGold() + "）");

        String from = unit.getName();
        try {
            unit.getUpgrade().performUpgrade(pick, false);
        } catch (Throwable e) {
            throw new Fail("NOT_AVAILABLE", "升级失败: " + e.getMessage());
        }
        events.add("{\"type\":\"unit_upgraded\",\"unit\":" + q("unit-" + unit.getId())
                + ",\"from\":" + q(from) + ",\"to\":" + q(pick.getUpgradedUnit().getName())
                + ",\"goldCost\":" + pick.getGoldCostOfUpgrade() + "}");
    }

    // ---------- 间谍 ----------

    private static void applySpyMove(GameInfo gi, Civilization actor, Map<String, Object> params, List<String> events) {
        String ref = Json.str(params, "spy");
        if (ref == null) throw new Fail("BAD_PARAMS", "需要 spy（名字或序号）");
        List<Spy> spies = actor.getEspionageManager().getSpyList();
        Spy spy = null;
        for (int i = 0; i < spies.size(); i++) {
            if (spies.get(i).getName().equals(ref) || String.valueOf(i).equals(ref)) spy = spies.get(i);
        }
        if (spy == null) {
            List<String> cand = new ArrayList<>();
            for (Spy s : spies) cand.add(s.getName());
            throw new Fail("UNKNOWN_ID", "没有这个间谍: " + ref, cand);
        }

        String cityRef = Json.str(params, "city");
        if (cityRef == null || cityRef.isEmpty() || cityRef.equalsIgnoreCase("hideout")) {
            spy.moveTo(null);
            events.add("{\"type\":\"spy_moved\",\"spy\":" + q(spy.getName()) + ",\"to\":\"hideout\"}");
            return;
        }

        City dest = null;
        List<String> cand = new ArrayList<>();
        Iterator<City> it = gi.getCities().iterator();
        while (it.hasNext()) {
            City c = it.next();
            cand.add(c.getName());
            if (c.getName().equals(cityRef) || ("city-" + c.getId()).equals(cityRef) || c.getId().equals(cityRef))
                dest = c;
        }
        if (dest == null) throw new Fail("UNKNOWN_ID", "没有这个城市: " + cityRef, cand);
        if (!spy.canMoveTo(dest)) throw new Fail("NOT_AVAILABLE", "这个间谍不能去 " + dest.getName());

        spy.moveTo(dest);
        events.add("{\"type\":\"spy_moved\",\"spy\":" + q(spy.getName()) + ",\"to\":" + q(dest.getName()) + "}");
    }

    // ---------- 专家分配 ----------

    private static void applySetSpecialists(GameInfo gi, Civilization actor, String target, Map<String, Object> params, List<String> events) {
        City city = requireOwnCity(gi, actor, target);
        if (!params.containsKey("specialists"))
            throw new Fail("BAD_PARAMS", "需要 specialists 对象，如 {\"Scientist\":1}；传 {} 表示全部取消");
        Map<String, Object> want = Json.obj(params.get("specialists"));

        Ruleset rs = gi.getRuleset();
        Counter<String> allocations = city.getPopulation().getSpecialistAllocations();
        Counter<String> max = city.getPopulation().getMaxSpecialists();

        Map<String, Integer> applied = new LinkedHashMap<>();
        int totalAfter = 0;
        for (Map.Entry<String, Object> e : want.entrySet()) {
            String name = e.getKey();
            Integer count = e.getValue() instanceof Number ? ((Number) e.getValue()).intValue() : null;
            if (count == null) throw new Fail("BAD_PARAMS", name + " 的数量必须是整数");
            if (!rs.getSpecialists().containsKey(name)) {
                List<String> cand = new ArrayList<>(rs.getSpecialists().keySet());
                throw new Fail("UNKNOWN_ID", "没有这个专家: " + name, cand);
            }
            if (count < 0) throw new Fail("BAD_PARAMS", name + " 不能是负数");
            int cap = max.containsKey(name) ? max.get(name) : 0;
            if (count > cap)
                throw new Fail("NOT_AVAILABLE", city.getName() + " 最多只能有 " + cap + " 个 " + name);
            applied.put(name, count);
            totalAfter += count;
        }
        int pop = city.getPopulation().getPopulation();
        if (totalAfter > pop)
            throw new Fail("NOT_AVAILABLE", "人口只有 " + pop + "，专家加起来会到 " + totalAfter);

        // 名单里没提到的专家一律清零，传 {} 就是全部取消
        for (String name : new ArrayList<>(allocations.keySet())) {
            if (applied.containsKey(name)) continue;
            int cur = allocations.get(name);
            if (cur != 0) allocations.add(name, -cur);
        }
        for (Map.Entry<String, Integer> e : applied.entrySet()) {
            int cur = allocations.containsKey(e.getKey()) ? allocations.get(e.getKey()) : 0;
            int delta = e.getValue() - cur;
            if (delta != 0) allocations.add(e.getKey(), delta);
        }
        city.setManualSpecialists(true);
        // 专家占的是人口，负的自由人口会让回合开始时乱分配，这里立刻把最差的地块放掉
        try {
            int free = city.getPopulation().getFreePopulation();
            if (free < 0) city.getPopulation().unassignExtraPopulation();
            else if (free > 0) city.getPopulation().autoAssignPopulation$unciv_core();
            city.getCityStats().update(null, true, true, true);
        } catch (Throwable ignored) { }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : allocations.entrySet()) {
            if (e.getValue() <= 0) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(q(e.getKey())).append(':').append(e.getValue());
        }
        events.add("{\"type\":\"specialists_set\",\"city\":" + q("city-" + city.getId())
                + ",\"specialists\":{" + sb + "}}");
    }

    // ---------- 傀儡转直辖 ----------

    private static void applyAnnexCity(GameInfo gi, Civilization actor, String target, Map<String, Object> params, List<String> events) {
        City city = requireOwnCity(gi, actor, target);
        if (!city.isPuppet()) throw new Fail("NOT_AVAILABLE", city.getName() + " 不是傀儡城，已经是直辖");
        try {
            city.annexCity();
        } catch (Throwable e) {
            throw new Fail("NOT_AVAILABLE", "直辖失败: " + e.getMessage());
        }
        events.add("{\"type\":\"city_annexed\",\"city\":" + q("city-" + city.getId())
                + ",\"name\":" + q(city.getName()) + "}");
    }

    // ---------- 占城决策 ----------

    /** 找出本文明待处理的占城弹窗对应的城市 */
    private static City pendingCapture(GameInfo gi, Civilization actor, String ref) {
        for (com.unciv.logic.civilization.PopupAlert alert : actor.getPopupAlerts()) {
            if (alert.getType() != com.unciv.logic.civilization.AlertType.CityConquered) continue;
            if (ref != null && !ref.equals(alert.getValue())) continue;
            Iterator<City> it = gi.getCities().iterator();
            while (it.hasNext()) {
                City c = it.next();
                if (c.getId().equals(alert.getValue())) return c;
            }
        }
        return null;
    }

    private static void applyCaptureCity(GameInfo gi, Civilization actor, String target, Map<String, Object> params, List<String> events) {
        String cityId = target != null && target.startsWith("city-") ? target.substring(5) : null;
        City city = pendingCapture(gi, actor, cityId);
        if (city == null) {
            List<String> waiting = new ArrayList<>();
            for (com.unciv.logic.civilization.PopupAlert alert : actor.getPopupAlerts()) {
                if (alert.getType() == com.unciv.logic.civilization.AlertType.CityConquered)
                    waiting.add("city-" + alert.getValue());
            }
            throw new Fail("NOT_AVAILABLE", waiting.isEmpty()
                    ? "现在没有待处理的占城"
                    : "这个城市不在待处理列表里", waiting);
        }

        String action = Json.str(params, "action");
        if (action == null) action = "puppet";
        action = action.toLowerCase();
        boolean mayAnnex = !actor.hasUnique(com.unciv.models.ruleset.unique.UniqueType.MayNotAnnexCities,
                actor.getState());
        String name = city.getName();
        String oldOwner = city.getCiv().getCivName();

        try {
            switch (action) {
                case "puppet":
                    city.puppetCity(actor);
                    break;
                case "annex":
                    if (!mayAnnex) throw new Fail("NOT_AVAILABLE", "这个文明不能直辖城市");
                    city.puppetCity(actor);
                    city.annexCity();
                    break;
                case "raze":
                    if (!city.canBeDestroyed(true))
                        throw new Fail("NOT_AVAILABLE", name + " 不能夷平（原首都或圣城）");
                    city.puppetCity(actor);
                    if (mayAnnex) city.annexCity();
                    city.setBeingRazed(true);
                    break;
                case "liberate": {
                    Civilization founder = city.getFoundingCivObject();
                    if (founder == null) throw new Fail("NOT_AVAILABLE", name + " 没有原始所有者");
                    if (founder == actor) throw new Fail("NOT_AVAILABLE", name + " 本来就是你的城");
                    city.liberateCity(actor);
                    break;
                }
                default:
                    throw new Fail("UNKNOWN_ID", "没有这个处理方式: " + action,
                            java.util.Arrays.asList("puppet", "annex", "raze", "liberate"));
            }
        } catch (Fail f) {
            throw f;
        } catch (Throwable e) {
            throw new Fail("NOT_AVAILABLE", "处理占城失败: " + e.getMessage());
        }

        // 清掉对应弹窗，否则下一次读档/回合还会挂着
        actor.getPopupAlerts().removeIf(a -> a.getType() == com.unciv.logic.civilization.AlertType.CityConquered
                && a.getValue().equals(city.getId()));

        events.add("{\"type\":\"city_captured\",\"city\":" + q("city-" + city.getId())
                + ",\"name\":" + q(name) + ",\"from\":" + q(oldOwner)
                + ",\"action\":" + q(action) + ",\"isPuppet\":" + city.isPuppet() + "}");
    }

    // ---------- 对象解析 ----------

    private static MapUnit requireOwnUnit(GameInfo gi, Civilization actor, String target) {
        Integer id = idOf(target, "unit");
        if (id == null) throw new Fail("BAD_PARAMS", "target 必须是 unit-<id>");
        Iterator<MapUnit> it = actor.getUnits().getCivUnits().iterator();
        while (it.hasNext()) {
            MapUnit u = it.next();
            if (u.getId() == id) return u;
        }
        List<String> mine = new ArrayList<>();
        it = actor.getUnits().getCivUnits().iterator();
        while (it.hasNext()) mine.add("unit-" + it.next().getId());
        throw new Fail("NOT_YOUR_UNIT", "你没有 id 为 " + id + " 的单位", mine);
    }

    private static City requireOwnCity(GameInfo gi, Civilization actor, String target) {
        String id = target != null && target.startsWith("city-") ? target.substring(5) : null;
        if (id == null) throw new Fail("BAD_PARAMS", "target 必须是 city-<id>");
        List<String> mine = new ArrayList<>();
        for (City c : actor.getCities()) {
            mine.add("city-" + c.getId());
            if (c.getId().equals(id)) return c;
        }
        throw new Fail("NOT_YOUR_CITY", "你没有 id 为 " + id + " 的城市", mine);
    }

    private static Integer idOf(String target, String prefix) {
        if (target == null || !target.startsWith(prefix + "-")) return null;
        try {
            return Integer.parseInt(target.substring(prefix.length() + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------- 输出 ----------

    static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < ' ') sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String error(String code, String message, List<String> candidates) {
        StringBuilder sb = new StringBuilder("{\"ok\":false,\"error\":{\"code\":")
                .append(q(code)).append(",\"message\":").append(q(message));
        if (candidates != null) {
            sb.append(",\"candidates\":[");
            for (int i = 0; i < candidates.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(q(candidates.get(i)));
            }
            sb.append(']');
        }
        return sb.append("}}").toString();
    }

    // ---------- MCP ----------

    public static String handleMcp(String body, GameInfo gameInfo, UncivGameRef ref, Civilization actor) {
        Map<String, Object> req;
        try {
            req = Json.obj(Json.parse(body));
        } catch (Exception e) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"Parse error\"}}";
        }
        Object idObj = req.get("id");
        String idJson = idObj == null ? "null" : (idObj instanceof String ? q((String) idObj) : String.valueOf(((Double) idObj).intValue()));
        String method = Json.str(req, "method");

        if ("initialize".equals(method)) {
            return "{\"jsonrpc\":\"2.0\",\"id\":" + idJson + ",\"result\":{"
                    + "\"protocolVersion\":\"2025-06-18\","
                    + "\"capabilities\":{\"tools\":{}},"
                    + "\"serverInfo\":{\"name\":\"unciv-agent\",\"version\":\"0.1.0\"}}}";
        }
        if ("notifications/initialized".equals(method)) {
            return ""; // 通知不需要响应
        }
        if ("ping".equals(method)) {
            return "{\"jsonrpc\":\"2.0\",\"id\":" + idJson + ",\"result\":{}}";
        }
        if ("tools/list".equals(method)) {
            StringBuilder sb = new StringBuilder("{\"jsonrpc\":\"2.0\",\"id\":").append(idJson)
                    .append(",\"result\":{\"tools\":[");
            boolean first = true;
            for (Tool t : TOOLS.values()) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"name\":").append(q(t.name))
                  .append(",\"description\":").append(q(t.description))
                  .append(",\"inputSchema\":").append(t.schema).append('}');
            }
            return sb.append("]}}").toString();
        }
        if ("tools/call".equals(method) && gameInfo == null) {
            return "{\"jsonrpc\":\"2.0\",\"id\":" + idJson + ",\"error\":{\"code\":-32000,\"message\":\"no game loaded\"}}";
        }
        if ("tools/call".equals(method)) {
            Map<String, Object> params = Json.obj(req.get("params"));
            String name = Json.str(params, "name");
            Map<String, Object> args = Json.obj(params.get("arguments"));
            if (name == null) {
                return "{\"jsonrpc\":\"2.0\",\"id\":" + idJson + ",\"error\":{\"code\":-32602,\"message\":\"缺少 name\"}}";
            }
            String payload = dispatchTool(name, args, gameInfo, ref, actor);
            boolean isError = payload.contains("\"ok\":false") || payload.contains("\"error\"");
            return "{\"jsonrpc\":\"2.0\",\"id\":" + idJson + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":"
                    + q(payload) + "}],\"isError\":" + isError + "}}";
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":" + idJson + ",\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}";
    }

    /** MCP 的 arguments 是扁平对象，翻译成 Intent 再走同一个内核 */
    private static String dispatchTool(String name, Map<String, Object> args, GameInfo gameInfo, UncivGameRef ref, Civilization actor) {
        switch (name) {
            case "get_state": return Agent.stateJson(gameInfo, actor);
            case "get_units": return Agent.unitsJson(gameInfo, actor);
            case "get_cities": return Agent.citiesJson(gameInfo, actor);
            case "get_city_details": return Agent.cityDetailsJson(gameInfo, actor, Json.str(args, "city"));
            case "get_victory_progress": return Agent.victoryJson(gameInfo, actor);
            case "get_tile": return Agent.tileJson(gameInfo, actor,
                    Json.integer(args, "x"), Json.integer(args, "y"));
            case "get_diplomacy": return Agent.diplomacyJson(gameInfo, actor);
            case "get_policies": return Agent.policiesJson(gameInfo, actor);
            case "get_map": return Agent.mapJson(gameInfo, actor,
                    Json.integer(args, "cx"), Json.integer(args, "cy"), Json.integer(args, "radius"));
            case "get_events": return Agent.eventsJson(gameInfo, actor, Json.integer(args, "limit"));
            case "get_trade_offers": return Agent.tradeOffersJson(gameInfo, actor, Json.str(args, "civ"));
            case "get_techs": return Agent.techsJson(gameInfo, actor);
            case "get_religion": return Agent.religionJson(gameInfo, actor);
            case "get_city_states": return Agent.cityStatesJson(gameInfo, actor);
            case "get_spies": return Agent.spiesJson(gameInfo, actor);
            case "declare_war":
            case "make_peace":
            case "adopt_policy":
            case "set_city_focus":
            case "purchase_construction":
            case "attack":
            case "bombard":
            case "promote_unit":
            case "unit_action":
            case "move_unit":
            case "found_city":
            case "set_city_production":
            case "set_research":
            case "propose_trade":
            case "respond_trade":
            case "choose_beliefs":
            case "gift_city_state":
            case "pledge_protect":
            case "set_worked_tiles":
            case "nuke":
            case "upgrade_unit":
            case "spy_move":
            case "set_city_specialists":
            case "annex_city":
            case "capture_city":
            case "end_turn": {
                StringBuilder sb = new StringBuilder("{\"type\":").append(q(name));
                Map<String, Object> rest = new LinkedHashMap<>(args);
                String targetKey = null;
                if (rest.containsKey("unit")) targetKey = "unit";
                else if (rest.containsKey("city")) targetKey = "city";
                else if (rest.containsKey("civ")) targetKey = "civ";
                else if (rest.containsKey("policy")) targetKey = "policy";
                if (targetKey != null) {
                    Object tv = rest.remove(targetKey);
                    sb.append(",\"target\":").append(tv instanceof String ? q((String) tv) : "null");
                }
                sb.append(",\"params\":").append(Json.write(rest)).append('}');
                return handleIntent(sb.toString(), gameInfo, ref, actor);
            }
            default:
                return "{\"ok\":false,\"error\":{\"code\":\"UNKNOWN_TOOL\",\"message\":" + q("没有这个工具: " + name) + "}}";
        }
    }
}
