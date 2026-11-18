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

import java.util.ArrayList;
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

    /** 幂等键：同一 intent.id 只生效一次 */
    private static final LinkedHashSet<String> appliedIds = new LinkedHashSet<>();

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
        tool("purchase_construction", "用金币购买城市建筑或单位。",
                "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"},\"item\":{\"type\":\"string\"}},\"required\":[\"city\",\"item\"]}", "act");
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

        if (id != null && appliedIds.contains(id)) {
            return "{\"ok\":true,\"intent\":" + q(id) + ",\"duplicate\":true,\"events\":[]}";
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
                case "declare_war": applyDeclareWar(gameInfo, actor, params, events); break;
                case "make_peace": applyMakePeace(gameInfo, actor, params, events); break;
                case "adopt_policy": applyAdoptPolicy(gameInfo, actor, params, events); break;
                case "end_turn": applyEndTurn(gameInfo, actor, ref, events); break;
                default: return error("UNKNOWN_ACTION", "动作未实现: " + type, null);
            }
        } catch (Fail f) {
            return error(f.code, f.getMessage(), f.candidates);
        } catch (Throwable e) {
            return error("INTERNAL", String.valueOf(e), null);
        }

        if (id != null) {
            appliedIds.add(id);
            if (appliedIds.size() > 1000) {
                Iterator<String> it = appliedIds.iterator();
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

        Tile dest = gi.getTileMap().get(new HexCoord(x, y));
        if (dest == null) throw new Fail("ILLEGAL_TARGET", "没有这个格子: " + x + "," + y);

        HexCoord from = unit.getTile().getPosition();
        try {
            unit.getMovement().moveToTile(dest, true);
        } catch (Throwable e) {
            throw new Fail("ILLEGAL_TARGET", "走不过去: " + e.getMessage());
        }
        HexCoord to = unit.getTile().getPosition();
        events.add("{\"type\":\"unit_moved\",\"unit\":" + q("unit-" + unit.getId())
                + ",\"from\":[" + from.getX() + "," + from.getY() + "]"
                + ",\"to\":[" + to.getX() + "," + to.getY() + "]"
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
        if (!ok) throw new Fail("NOT_AVAILABLE", "这里不能建城");
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

        String before = city.getCityConstructions().getCurrentConstruction().getName();
        city.getCityConstructions().setCurrentConstruction(item);
        String now = city.getCityConstructions().getCurrentConstruction().getName();
        if (!now.equals(item)) {
            city.getCityConstructions().setCurrentConstruction(before);
            throw new Fail("NOT_AVAILABLE", "现在不能生产 " + item + "，已还原为 " + before);
        }
        Integer count = Json.integer(params, "count");
        int extra = count == null ? 1 : Math.max(1, Math.min(count, 20));
        for (int i = 1; i < extra; i++) {
            try {
                city.getCityConstructions().addToQueue(item, null);
            } catch (Throwable ignored) { }
        }
        events.add("{\"type\":\"production_set\",\"city\":" + q("city-" + city.getId())
                + ",\"item\":" + q(item) + ",\"queued\":" + extra + "}");
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

        PathsToTilesWithinTurn paths = unit.getMovement().getDistanceToTiles(false, null, null, false);
        ArrayList<AttackableTile> targets =
                TargetHelper.INSTANCE.getAttackableEnemies(unit, paths, null, stayOnTile);

        AttackableTile hit = null;
        List<String> candidates = new ArrayList<>();
        for (AttackableTile at : targets) {
            HexCoord p = at.getTileToAttack().getPosition();
            candidates.add(p.getX() + "," + p.getY());
            if (p.getX() == x && p.getY() == y) hit = at;
        }
        if (hit == null) {
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

        boolean ok;
        try {
            ok = city.getCityConstructions().purchaseConstruction(item, 1, false,
                    com.unciv.models.stats.Stat.Gold, null);
        } catch (Throwable e) {
            throw new Fail("NOT_AVAILABLE", "购买失败: " + e.getMessage());
        }
        if (!ok) throw new Fail("NOT_AVAILABLE", "买不起或不能买: " + item);
        events.add("{\"type\":\"purchased\",\"city\":" + q("city-" + city.getId())
                + ",\"item\":" + q(item) + ",\"goldLeft\":" + actor.getGold() + "}");
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

    private static void applyDeclareWar(GameInfo gi, Civilization actor, Map<String, Object> params, List<String> events) {
        Civilization other = requireOtherCiv(gi, actor, Json.str(params, "civ"));
        com.unciv.logic.civilization.diplomacy.DiplomacyManager dm =
                actor.getDiplomacyManager(other);
        if (dm == null) throw new Fail("NOT_AVAILABLE", "还没遇到这个文明");
        if (actor.isAtWarWith(other)) throw new Fail("NOT_AVAILABLE", "已经和 " + other.getCivName() + " 交战");
        if (!dm.canDeclareWar()) throw new Fail("NOT_AVAILABLE", "现在不能宣战");
        dm.declareWar(null);
        events.add("{\"type\":\"war_declared\",\"civ\":" + q(other.getCivName()) + "}");
    }

    private static void applyMakePeace(GameInfo gi, Civilization actor, Map<String, Object> params, List<String> events) {
        Civilization other = requireOtherCiv(gi, actor, Json.str(params, "civ"));
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
        int before = gi.getTurns();
        ref.advanceTurn(gi, actor);
        events.add("{\"type\":\"turn_advanced\",\"from\":" + before + ",\"to\":" + gi.getTurns()
                + ",\"currentCiv\":" + q(actor.getCivName()) + "}");
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
            case "end_turn": {
                StringBuilder sb = new StringBuilder("{\"type\":").append(q(name));
                String target = Json.str(args, "unit");
                if (target == null) target = Json.str(args, "city");
                if (target == null) target = Json.str(args, "civ");
                if (target == null) target = Json.str(args, "policy");
                if (target != null) sb.append(",\"target\":").append(q(target));
                sb.append(",\"params\":{");
                boolean first = true;
                for (Map.Entry<String, Object> e : args.entrySet()) {
                    if (e.getKey().equals("unit") || e.getKey().equals("city")
                            || e.getKey().equals("civ") || e.getKey().equals("policy")) continue;
                    if (!first) sb.append(',');
                    first = false;
                    sb.append(q(e.getKey())).append(':');
                    Object v = e.getValue();
                    if (v instanceof Number) sb.append(((Number) v).intValue());
                    else if (v instanceof Boolean) sb.append(v);
                    else sb.append(q(String.valueOf(v)));
                }
                sb.append("}}");
                return handleIntent(sb.toString(), gameInfo, ref, actor);
            }
            default:
                return "{\"ok\":false,\"error\":{\"code\":\"UNKNOWN_TOOL\",\"message\":" + q("没有这个工具: " + name) + "}}";
        }
    }
}
