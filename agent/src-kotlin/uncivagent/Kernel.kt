@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "UNCHECKED_CAST")

package uncivagent

import com.unciv.logic.GameInfo
import com.unciv.logic.battle.AttackableTile
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.Nuke
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.CityStateFunctions
import com.unciv.logic.civilization.managers.ReligionManager
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.PossibleUpgrade
import com.unciv.logic.map.mapunit.actions.UnitActions
import com.unciv.logic.map.mapunit.movement.PathsToTilesWithinTurn
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeOffersList
import com.unciv.logic.trade.TradeRequest
import com.unciv.models.Counter
import com.unciv.models.Religion
import com.unciv.models.Spy
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.unit.Promotion

/**
 * 统一动作内核。HTTP /intent 和 MCP tools/call 都走这里，行为必须一致。
 *
 * 契约见 notes/unciv-agent-contract.md：
 *   动作是数据 → validate（纯函数）→ apply（唯一写入口）→ Result(events, stateDelta)
 */
object Kernel {

    /** 幂等键：同一 intent.id 只生效一次；id 换了内容则报错，而不是静默丢掉 */
    private val appliedIds = LinkedHashMap<String, String>()

    // ---------- 工具表（唯一事实来源，HTTP 和 MCP 都从这里长出来） ----------

    data class Tool(
        @JvmField val name: String,
        @JvmField val description: String,
        @JvmField val schema: String,   // JSON Schema 的 properties 片段
        @JvmField val kind: String,     // "read" | "act" | "turn"
    )

    private val TOOLS: List<Tool> = listOf(
        Tool("get_state", "读取当前文明的整体状态：回合、金币、研究、城市与单位摘要。", """{"type":"object","properties":{}}""", "read"),
        Tool("get_units", "列出己方所有单位，含坐标、血量、剩余移动力、可用动作。", """{"type":"object","properties":{}}""", "read"),
        Tool("get_cities", "列出己方所有城市，含人口、坐标、在建项目。", """{"type":"object","properties":{}}""", "read"),
        Tool("move_unit", "把一个单位移动到指定格子。",
            """{"type":"object","properties":{"unit":{"type":"string"},"x":{"type":"integer"},"y":{"type":"integer"}},"required":["unit","x","y"]}""", "act"),
        Tool("found_city", "用移民建立城市。",
            """{"type":"object","properties":{"unit":{"type":"string"}},"required":["unit"]}""", "act"),
        Tool("set_city_production", "设置城市的生产项目；count>1 时连续排入队列，避免造完变 Nothing。",
            """{"type":"object","properties":{"city":{"type":"string"},"item":{"type":"string"},"count":{"type":"integer"}},"required":["city","item"]}""", "act"),
        Tool("set_research", "设置当前研究目标。",
            """{"type":"object","properties":{"tech":{"type":"string"}},"required":["tech"]}""", "act"),
        Tool("attack", "用单位攻击指定格子上的敌方单位或城市。",
            """{"type":"object","properties":{"unit":{"type":"string"},"x":{"type":"integer"},"y":{"type":"integer"}},"required":["unit","x","y"]}""", "act"),
        Tool("bombard", "用远程单位轰击指定格子，不移动。",
            """{"type":"object","properties":{"unit":{"type":"string"},"x":{"type":"integer"},"y":{"type":"integer"}},"required":["unit","x","y"]}""", "act"),
        Tool("promote_unit", "给单位选择一个晋升。",
            """{"type":"object","properties":{"unit":{"type":"string"},"promotion":{"type":"string"}},"required":["unit","promotion"]}""", "act"),
        Tool("unit_action", "触发单位的通用动作，如 Fortify / Sleep / Explore / Automate / Pillage / CreateImprovement。带 id 可指定具体目标（改良设施名、unique 等）。",
            """{"type":"object","properties":{"unit":{"type":"string"},"action":{"type":"string"},"id":{"type":"string"}},"required":["unit","action"]}""", "act"),
        Tool("get_map", "读取指定中心点附近的已探索地块。",
            """{"type":"object","properties":{"cx":{"type":"integer"},"cy":{"type":"integer"},"radius":{"type":"integer"}},"required":["cx","cy"]}""", "read"),
        Tool("get_events", "读取本回合的通知事件。",
            """{"type":"object","properties":{"limit":{"type":"integer"}}}""", "read"),
        Tool("get_city_details", "读取单个城市的详情：人口、焦点、生产队列、可建造项。",
            """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}""", "read"),
        Tool("set_city_focus", "设置城市人口分配焦点。",
            """{"type":"object","properties":{"city":{"type":"string"},"focus":{"type":"string"}},"required":["city","focus"]}""", "act"),
        Tool("purchase_construction", "用金币或信仰购买城市建筑或单位。",
            """{"type":"object","properties":{"city":{"type":"string"},"item":{"type":"string"},"currency":{"type":"string"}},"required":["city","item"]}""", "act"),
        Tool("get_victory_progress", "读取胜利条件与当前进度。", """{"type":"object","properties":{}}""", "read"),
        Tool("get_tile", "读取单个地块的详情。",
            """{"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"}},"required":["x","y"]}""", "read"),
        Tool("get_diplomacy", "列出已知文明及其外交状态。", """{"type":"object","properties":{}}""", "read"),
        Tool("declare_war", "对某个文明宣战。",
            """{"type":"object","properties":{"civ":{"type":"string"}},"required":["civ"]}""", "act"),
        Tool("make_peace", "与某个文明议和。",
            """{"type":"object","properties":{"civ":{"type":"string"}},"required":["civ"]}""", "act"),
        Tool("get_policies", "读取已采纳政策与可选分支。", """{"type":"object","properties":{}}""", "read"),
        Tool("adopt_policy", "采纳一个政策。",
            """{"type":"object","properties":{"policy":{"type":"string"}},"required":["policy"]}""", "act"),
        Tool("end_turn", "结束本回合。单人模式下由宿主直接推进。",
            """{"type":"object","properties":{}}""", "turn"),
        Tool("get_trade_offers", "读取待回应的交易请求；带 civ 时还列出与该文明互相可交易的项。",
            """{"type":"object","properties":{"civ":{"type":"string"}}}""", "read"),
        Tool("propose_trade", "向某个文明提出交易。give 是你给出去的，want 是你想要的；每项 {name,type,amount}。",
            """{"type":"object","properties":{"civ":{"type":"string"},"give":{"type":"array"},"want":{"type":"array"}},"required":["civ"]}""", "act"),
        Tool("respond_trade", "回应某个文明发来的交易请求：accept 为 true 成交，false 拒绝。",
            """{"type":"object","properties":{"civ":{"type":"string"},"accept":{"type":"boolean"},"index":{"type":"integer"}},"required":["civ"]}""", "act"),
        Tool("build_improvement", "让工人在这块地上开始造设施（农场/矿/路等）。之后每回合工人留在这格不动，进度自己走。",
            """{"type":"object","properties":{"improvement":{"type":"string"}},"required":["improvement"]}""", "act"),
        Tool("disband_unit", "解散自己的单位。",
            """{"type":"object","properties":{}}""", "act"),
        Tool("preview_move", "移动预览：这条路线怎么走、每格代价、几回合到、到不了为什么。不改状态。",
            """{"type":"object","properties":{"unit":{"type":"string"},"x":{"type":"integer"},"y":{"type":"integer"}},"required":["unit","x","y"]}""", "read"),
        Tool("get_stats", "帝国总览的 Stats 页：金币/科研/文化/信仰按来源拆开，加快乐拆解和逐城产出。",
            """{"type":"object","properties":{}}""", "read"),
        Tool("get_resources", "资源总览：奢侈品哪些连上了、领地里还有哪些没连，战略资源存量。",
            """{"type":"object","properties":{}}""", "read"),
        Tool("get_reachable", "这个单位这回合能走到的所有格子，带总代价。",
            """{"type":"object","properties":{"unit":{"type":"string"}},"required":["unit"]}""", "read"),
        Tool("get_targets", "这个单位这回合能打的所有目标格，附双向伤害区间。",
            """{"type":"object","properties":{"unit":{"type":"string"}},"required":["unit"]}""", "read"),
        Tool("preview_attack", "不执行动作，只算这一击的伤害范围：双方力量、期望伤害、会不会死、够不够得着。",
            """{"type":"object","properties":{"unit":{"type":"string"},"city":{"type":"string"},"x":{"type":"integer"},"y":{"type":"integer"}},"required":["x","y"]}""", "read"),
        Tool("get_guide", "游戏自带的教程/百科条目，可当攻略参考；可按 name 或 category 过滤。",
            """{"type":"object","properties":{"name":{"type":"string"},"category":{"type":"string"}}}""", "read"),
        Tool("get_briefing", "一回合的完整简报：资源、研究、城市、待操作单位、外交、宗教、事件、提醒。每回合读这一条就够。",
            """{"type":"object","properties":{}}""", "read"),
        Tool("get_techs", "列出当前可研究的科技、造价和按当前科研速度的回合数。",
            """{"type":"object","properties":{}}""", "read"),
        Tool("get_religion", "读取宗教状态：信仰、万神殿/国教、已选与可选的信念。",
            """{"type":"object","properties":{}}""", "read"),
        Tool("choose_beliefs", "选信念：万神殿、立国教（带 unit 用大先知）或强化宗教。beliefs 是信念名数组。",
            """{"type":"object","properties":{"unit":{"type":"string"},"beliefs":{"type":"array"},"name":{"type":"string"},"displayName":{"type":"string"}},"required":["beliefs"]}""", "act"),
        Tool("get_city_states", "列出已遇到的城邦：影响力、盟友、任务、送礼可得影响力。",
            """{"type":"object","properties":{}}""", "read"),
        Tool("gift_city_state", "给城邦送金币换影响力。",
            """{"type":"object","properties":{"civ":{"type":"string"},"gold":{"type":"integer"}},"required":["civ"]}""", "act"),
        Tool("pledge_protect", "承诺保护或撤回对城邦的保护。",
            """{"type":"object","properties":{"civ":{"type":"string"},"protect":{"type":"boolean"}},"required":["civ"]}""", "act"),
        Tool("set_worked_tiles", "手动指定城市工作哪些地块；lock 锁定，autoFill 用剩余人口自动补。",
            """{"type":"object","properties":{"city":{"type":"string"},"tiles":{"type":"array"},"lock":{"type":"boolean"},"autoFill":{"type":"boolean"}},"required":["city","tiles"]}""", "act"),
        Tool("bombard_city", "用城市自己的远程火力轰击范围内的敌人（每回合一次，不消耗单位）。",
            """{"type":"object","properties":{"city":{"type":"string"},"x":{"type":"integer"},"y":{"type":"integer"}},"required":["city","x","y"]}""", "act"),
        Tool("nuke", "用核武器打击指定格子。",
            """{"type":"object","properties":{"unit":{"type":"string"},"x":{"type":"integer"},"y":{"type":"integer"}},"required":["unit","x","y"]}""", "act"),
        Tool("upgrade_unit", "把单位升级到新兵种；有多个目标时用 to 指定。",
            """{"type":"object","properties":{"unit":{"type":"string"},"to":{"type":"string"}},"required":["unit"]}""", "act"),
        Tool("get_spies", "列出间谍：位置、当前动作、剩余回合。",
            """{"type":"object","properties":{}}""", "read"),
        Tool("spy_move", "把间谍派到某个城市（别人城里会自动建网并开始偷科技），city 留空回总部。",
            """{"type":"object","properties":{"spy":{"type":"string"},"city":{"type":"string"}},"required":["spy"]}""", "act"),
        Tool("set_city_specialists", "手动分配城市专家，如 {\"Scientist\":1}；会切到手动模式。",
            """{"type":"object","properties":{"city":{"type":"string"},"specialists":{"type":"object"}},"required":["city","specialists"]}""", "act"),
        Tool("annex_city", "把傀儡城转为直辖。",
            """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}""", "act"),
        Tool("capture_city", "处理刚打下来的城市：action 为 puppet / annex / raze / liberate，默认 puppet。",
            """{"type":"object","properties":{"city":{"type":"string"},"action":{"type":"string"}},"required":["city"]}""", "act"),
    )

    private val TOOLS_BY_NAME: Map<String, Tool> = TOOLS.associateBy { it.name }

    @JvmStatic
    fun tools(): List<Tool> = TOOLS

    // ---------- Intent 入口 ----------

    @JvmStatic
    fun handleIntent(body: String, gameInfo: GameInfo, ref: UncivGameRef, actor: Civilization): String {
        val inp: MutableMap<String, Any?>
        try {
            inp = obj(parse(body))
        } catch (e: Exception) {
            return error("BAD_JSON", "请求体不是合法 JSON: " + e.message, null)
        }

        val id = str(inp, "id")
        val type = str(inp, "type")
        val target = str(inp, "target")
        val turn = integer(inp, "turn")
        val params = obj(inp["params"])

        if (type == null) return error("BAD_INTENT", "缺少 type", null)
        val t = TOOLS_BY_NAME[type] ?: return error("UNKNOWN_ACTION", "没有这个动作: $type", null)

        if (t.kind == "read") {
            return error("NOT_AN_ACTION", "$type 是只读工具，请用对应查询接口", null)
        }

        val fingerprint = type + "|" + (target ?: "") + "|" + write(params)
        if (id != null && appliedIds.containsKey(id)) {
            if (fingerprint == appliedIds[id])
                return """{"ok":true,"intent":${q(id)},"duplicate":true,"events":[]}"""
            return error("ID_REUSED", "这个 id 已经用在另一个动作上了，换一个 id", null)
        }

        if (turn != null && turn != gameInfo.turns) {
            return error("STALE", "回合已推进，你看到的是第 $turn 回合，当前是 ${gameInfo.turns}", null)
        }

        val events = ArrayList<String>()
        try {
            when (type) {
                "move_unit" -> applyMove(gameInfo, actor, target, params, events)
                "found_city" -> applyFoundCity(gameInfo, actor, target, events)
                "build_improvement" -> applyBuildImprovement(gameInfo, actor, target, params, events)
                "disband_unit" -> applyDisbandUnit(gameInfo, actor, target, events)
                "set_city_production" -> applyProduction(gameInfo, actor, target, params, events)
                "set_research" -> applyResearch(gameInfo, actor, target, params, events)
                "attack" -> applyAttack(gameInfo, actor, target, params, events, false)
                "bombard" -> applyAttack(gameInfo, actor, target, params, events, true)
                "promote_unit" -> applyPromote(gameInfo, actor, target, params, events)
                "unit_action" -> applyUnitAction(gameInfo, actor, target, params, events)
                "set_city_focus" -> applyCityFocus(gameInfo, actor, target, params, events)
                "purchase_construction" -> applyPurchase(gameInfo, actor, target, params, events)
                "declare_war" -> applyDeclareWar(gameInfo, actor, target, params, events)
                "make_peace" -> applyMakePeace(gameInfo, actor, target, params, events)
                "adopt_policy" -> applyAdoptPolicy(gameInfo, actor, params, events)
                "end_turn" -> applyEndTurn(gameInfo, actor, ref, events)
                "propose_trade" -> applyProposeTrade(gameInfo, actor, target, params, events)
                "respond_trade" -> applyRespondTrade(gameInfo, actor, target, params, events)
                "choose_beliefs" -> applyChooseBeliefs(gameInfo, actor, target, params, events)
                "gift_city_state" -> applyGiftCityState(gameInfo, actor, target, params, events)
                "pledge_protect" -> applyPledgeProtect(gameInfo, actor, target, params, events)
                "set_worked_tiles" -> applySetWorkedTiles(gameInfo, actor, target, params, events)
                "nuke" -> applyNuke(gameInfo, actor, target, params, events)
                "bombard_city" -> applyCityBombard(gameInfo, actor, target, params, events)
                "upgrade_unit" -> applyUpgradeUnit(gameInfo, actor, target, params, events)
                "spy_move" -> applySpyMove(gameInfo, actor, params, events)
                "set_city_specialists" -> applySetSpecialists(gameInfo, actor, target, params, events)
                "annex_city" -> applyAnnexCity(gameInfo, actor, target, params, events)
                "capture_city" -> applyCaptureCity(gameInfo, actor, target, params, events)
                else -> return error("UNKNOWN_ACTION", "动作未实现: $type", null)
            }
        } catch (f: Fail) {
            return error(f.code, f.message ?: "", f.candidates)
        } catch (e: Throwable) {
            return error("INTERNAL", e.toString(), null)
        }

        if (id != null) {
            appliedIds[id] = fingerprint
            if (appliedIds.size > 1000) {
                val it = appliedIds.keys.iterator()
                it.next()
                it.remove()
            }
        }
        return """{"ok":true,"intent":${id?.let { q(it) } ?: "null"},"turn":${gameInfo.turns},"events":[${events.joinToString(",")}]}"""
    }

    /** 让 Kernel 能推进回合而不直接依赖 Agent 的内部字段 */
    interface UncivGameRef {
        fun advanceTurn(gameInfo: GameInfo, actor: Civilization)
    }

    private class Fail(val code: String, message: String, val candidates: List<String>? = null) :
        RuntimeException(message)

    // ---------- 各动作 ----------

    private fun applyMove(gi: GameInfo, actor: Civilization, target: String?, params: MutableMap<String, Any?>, events: MutableList<String>) {
        val unit = requireOwnUnit(gi, actor, target)
        val x = integer(params, "x")
        val y = integer(params, "y")
        if (x == null || y == null) throw Fail("BAD_PARAMS", "需要 x 与 y")

        val dest = tileAt(gi, x, y)
            ?: throw Fail("ILLEGAL_TARGET", "没有这个格子: $x,$y")

        val from = unit.getTile().position
        if (unit.currentMovement <= 0.05f)
            throw Fail("NOT_AVAILABLE", "这个单位这回合已经没有移动力了")

        var reachableAnywhere: Boolean
        try {
            reachableAnywhere = unit.movement.canReach(dest)
        } catch (e: Throwable) {
            reachableAnywhere = false
        }
        if (!reachableAnywhere)
            throw Fail("ILLEGAL_TARGET", "这个单位到不了 $x,$y（海陆不通或没有可走的路）")

        var reachable: Boolean
        try {
            reachable = unit.movement.canReachInCurrentTurn(dest)
        } catch (e: Throwable) {
            reachable = false
        }
        try {
            if (reachable) unit.movement.moveToTile(dest, true)
            else unit.movement.headTowards(dest)   // 跨回合寻路：这一回合能走多远走多远
        } catch (e: Throwable) {
            throw Fail("ILLEGAL_TARGET", "走不过去: " + e.message)
        }
        val to = unit.getTile().position
        val arrived = to.x == dest.position.x && to.y == dest.position.y
        val left = unit.getTile().aerialDistanceTo(dest)
        var blocked: String? = null
        if (!arrived) {
            val uit = dest.getUnits().iterator()
            if (uit.hasNext()) {
                val occupant = uit.next()
                blocked = "目标格被 " + occupant.civ.civName + " 的 " +
                    occupant.name + " 占着，走不进去"
            }
        }
        if (!arrived && blocked == null) {
            // 没走到、也没人挡着：就是这回合的路到头了，或者根本走不到
            if (left > 0) {
                blocked = "这回合只走到这里，还差 $left 格；下回合继续"
            } else {
                throw Fail("ILLEGAL_TARGET", "走不到 $x,$y：这个单位进不了那种地形（水陆不通或不可通行），没有移动")
            }
        }
        events.add(
            """{"type":"unit_moved","unit":${q("unit-" + unit.id)}""" +
                ""","from":[${from.x},${from.y}]""" +
                ""","to":[${to.x},${to.y}]""" +
                ""","target":[${dest.position.x},${dest.position.y}]""" +
                ""","arrived":$arrived""" +
                ""","blockedBy":${blocked?.let { q(it) } ?: "null"}""" +
                ""","tilesLeft":$left""" +
                ""","moves":${unit.currentMovement}}"""
        )
    }

    private fun applyFoundCity(gi: GameInfo, actor: Civilization, target: String?, events: MutableList<String>) {
        val unit = requireOwnUnit(gi, actor, target)
        if (unit.currentMovement <= 0.05f)
            throw Fail("NOT_AVAILABLE", unit.name + " 这回合没有移动力了，建城要 1 点移动力")
        val before = unit.civ.cities.size
        var ok: Boolean
        try {
            ok = UnitActions.invokeUnitAction(unit, UnitActionType.FoundCity, null)
        } catch (e: Throwable) {
            throw Fail("ILLEGAL_TARGET", "建城失败: " + e.message)
        }
        if (!ok) {
            val why = StringBuilder("这里不能建城")
            try {
                val t = unit.getTile()
                if (t.isWater) why.append("：水面")
                else if (t.isImpassible()) why.append("：不可通行地形")
                val minDist = gi.ruleset.modOptions.constants.minimalCityDistance
                var nearest: City? = null
                var best = Int.MAX_VALUE
                for (cv in gi.civilizations) {
                    for (c in cv.cities) {
                        val d = t.aerialDistanceTo(c.getCenterTile())
                        if (d < best) { best = d; nearest = c }
                    }
                }
                if (nearest != null && best <= minDist) {
                    val mine = nearest.civ === actor
                    why.append("：离").append(if (mine) "自己的城 " else nearest.civ.civName + " 的城 ")
                        .append(nearest.name).append(" 只有 ").append(best)
                        .append(" 格，至少要 ").append(minDist + 1).append(" 格")
                } else if (t.owningCity != null && t.owningCity!!.civ !== actor) {
                    why.append("：这里是 ").append(t.owningCity!!.civ.civName).append(" 的领土")
                } else {
                    why.append("：地形不允许（可能离城市太近或地形本身不能建城）")
                }
            } catch (ignored: Throwable) { }
            throw Fail("NOT_AVAILABLE", why.toString())
        }
        val cities = unit.civ.cities
        if (cities.size > before) {
            val c = cities[cities.size - 1]
            events.add(
                """{"type":"city_founded","city":${q("city-" + c.id)},"name":${q(c.name)},"at":[${c.location.x},${c.location.y}]}"""
            )
        } else {
            events.add("""{"type":"city_founded"}""")
        }
    }

    /** 工人造设施：引擎的入口在 UI 层（ImprovementPickerScreen），逻辑层只有
     *  Tile.startWorkingOnImprovement + 每回合 UnitTurnManager.endTurn 里的 doWorkerTurn。 */
    private fun applyBuildImprovement(gi: GameInfo, actor: Civilization, target: String?,
                                      params: MutableMap<String, Any?>, events: MutableList<String>) {
        val unit = requireOwnUnit(gi, actor, target)
        val name = str(params, "improvement") ?: throw Fail("BAD_PARAMS", "需要 improvement")
        val tile = unit.getTile()
        val rs = gi.ruleset

        val imp = rs.tileImprovements[name]
        if (imp == null) {
            val candidates = ArrayList<String>()
            for (k in rs.tileImprovements.keys) {
                if (k.lowercase().contains(name.lowercase())) {
                    candidates.add(k)
                    if (candidates.size >= 8) break
                }
            }
            if (candidates.isEmpty()) {
                val buildable = ArrayList<String>()
                for (ti in rs.tileImprovements.values) {
                    try {
                        if (unit.canBuildImprovement(ti, tile)) buildable.add(ti.name)
                    } catch (ignored: Throwable) { }
                }
                throw Fail("UNKNOWN_ID", "没有这个设施: $name" +
                    (if (buildable.isEmpty()) "" else "；这个单位在这格能造: $buildable"), candidates)
            }
            throw Fail("UNKNOWN_ID", "没有这个设施: $name", candidates)
        }

        if (!unit.cache.hasUniqueToBuildImprovements)
            throw Fail("NOT_AVAILABLE", unit.name + " 不能造设施，只有工人（Worker）类单位可以")
        if (tile.isCityCenter()) throw Fail("ILLEGAL_TARGET", "城市中心不能造设施")
        if (!unit.hasMovement())
            throw Fail("NOT_AVAILABLE", unit.name + " 这回合没有移动力了，造设施要单位留在这格")
        if (!unit.canBuildImprovement(imp, tile))
            throw Fail("NOT_AVAILABLE", unit.name + " 造不了 " + imp.name +
                "：地形不符、缺前置科技，或这格不允许")

        val problems = ArrayList<String>()
        try {
            val ctx = com.unciv.models.ruleset.unique.GameContext(unit)
            for (o in tile.improvementFunctions.getImprovementBuildingProblems(imp, ctx)) {
                if (o is com.unciv.logic.map.tile.ImprovementBuildingProblem) {
                    if (o.reportable) problems.add(o.name)
                }
            }
        } catch (ignored: Throwable) { }
        if (problems.isNotEmpty())
            throw Fail("NOT_AVAILABLE", "这里造不了 " + imp.name + "：" + problems)

        val before = tile.improvement
        tile.startWorkingOnImprovement(imp, actor, unit)
        val turns = tile.turnsToImprovement
        events.add(
            """{"type":"improvement_started","unit":${q("unit-" + unit.id)},"improvement":${q(imp.name)},"at":[${tile.position.x},${tile.position.y}],"turns":$turns,"replaces":${before?.let { q(it) } ?: "null"},"note":${q("工人留在这格不动，每回合自己推进；走开或换活会丢进度")}}"""
        )
    }

    private fun applyDisbandUnit(gi: GameInfo, actor: Civilization, target: String?, events: MutableList<String>) {
        val unit = requireOwnUnit(gi, actor, target)
        val id = unit.id
        val name = unit.name
        unit.disband()
        try { actor.updateStatsForNextTurn() } catch (ignored: Throwable) { }
        events.add(
            """{"type":"disbanded","unit":${q("unit-$id")},"name":${q(name)},"gold":${actor.gold}}"""
        )
    }

    /** 小地图边缘外 TileMap.get 会抛异常，统一收口。 */
    @JvmStatic
    fun tileAt(gi: GameInfo, x: Int, y: Int): Tile? {
        return try {
            gi.tileMap.get(HexCoord(x, y))
        } catch (ignored: Throwable) {
            null
        }
    }

    @JvmStatic
    fun buildableNow(gi: GameInfo, city: City): List<String> {
        val out = ArrayList<String>()
        val rs = gi.ruleset
        val pool = ArrayList<String>()
        pool.addAll(rs.buildings.keys)
        pool.addAll(rs.units.keys)
        for (name in pool) {
            if (out.size >= 25) break
            try {
                val con = city.cityConstructions.getConstruction(name)
                if (con != null && city.cityConstructions.canAddToQueue(con,
                        city.cityConstructions.constructionQueue)) out.add(name)
            } catch (ignored: Throwable) { }
        }
        return out
    }

    private fun applyProduction(gi: GameInfo, actor: Civilization, target: String?, params: MutableMap<String, Any?>, events: MutableList<String>) {
        val city = requireOwnCity(gi, actor, target)
        val item = str(params, "item") ?: throw Fail("BAD_PARAMS", "需要 item")

        val rs = gi.ruleset
        if (!rs.buildings.containsKey(item) && !rs.units.containsKey(item)) {
            val candidates = ArrayList<String>()
            val needle = item.lowercase()
            for (k in rs.buildings.keys) {
                if (k.lowercase().contains(needle)) candidates.add(k)
                if (candidates.size >= 8) break
            }
            for (k in rs.units.keys) {
                if (candidates.size >= 8) break
                if (k.lowercase().contains(needle)) candidates.add(k)
            }
            throw Fail("UNKNOWN_ID", "没有这个可建造项: $item", candidates)
        }

        // 先按引擎自己的规则验一遍：setCurrentConstruction 对不可建造项不会报错，只会让城市空转
        var con: com.unciv.models.ruleset.IConstruction? = null
        try {
            con = city.cityConstructions.getConstruction(item)
        } catch (ignored: Throwable) { }
        var buildable = false
        if (con != null) {
            try {
                buildable = city.cityConstructions.canAddToQueue(con, emptyList())
            } catch (ignored: Throwable) { }
        }
        if (!buildable)
            throw Fail("NOT_AVAILABLE", "现在不能生产 $item（缺科技/资源/前置）", buildableNow(gi, city))

        val mode = str(params, "mode")
        val replace = mode == null || !mode.equals("append", ignoreCase = true)
        if (replace) {
            try {
                (city.cityConstructions.constructionQueue as MutableList<String>).clear()
            } catch (ignored: Throwable) { }
        }
        city.cityConstructions.setCurrentConstruction(item)

        val count = integer(params, "count")
        val want = if (count == null) 1 else maxOf(1, minOf(count, 20))
        for (i in 1 until want) {
            try {
                city.cityConstructions.addToQueue(item, null)
            } catch (ignored: Throwable) { }
        }

        // 以引擎的真实队列为准，别报一个自己都没验证过的数字
        val queue = city.cityConstructions.constructionQueue
        events.add(
            """{"type":"production_set","city":${q("city-" + city.id)},"item":${q(item)},"mode":${q(if (replace) "replace" else "append")},"requested":$want,"queued":${queue.size},"queue":[${quotedList(queue)}]}"""
        )
    }

    private fun applyResearch(gi: GameInfo, actor: Civilization, target: String?, params: MutableMap<String, Any?>, events: MutableList<String>) {
        val civ = actor
        var tech = str(params, "tech")
        val queue = arr(params["techs"])
        if (tech == null && queue.isNotEmpty()) tech = queue[0].toString()
        if (tech == null) throw Fail("BAD_PARAMS", "需要 tech 或 techs")

        val rs = gi.ruleset
        if (!rs.technologies.containsKey(tech)) {
            val candidates = ArrayList<String>()
            for (k in rs.technologies.keys) {
                if (k.lowercase().contains(tech.lowercase())) candidates.add(k)
                if (candidates.size >= 8) break
            }
            if (candidates.isEmpty()) candidates.addAll(actor.tech.techsToResearch)
            throw Fail("UNKNOWN_ID", "没有这个科技: $tech", candidates)
        }
        var can: Boolean
        try {
            can = civ.tech.canBeResearched(tech)
        } catch (e: Throwable) {
            can = false
        }
        if (!can) throw Fail("NOT_AVAILABLE", "现在研究不了: $tech")

        // techs 数组可以一次排好几项，省得科技一完成就空转一回合
        val plan = ArrayList<String>()
        plan.add(tech)
        for (o in queue) {
            val t = o as? String ?: continue
            if (t == tech) continue
            if (!rs.technologies.containsKey(t)) continue
            plan.add(t)
        }
        civ.tech.techsToResearch.clear()
        civ.tech.techsToResearch.addAll(plan)
        events.add("""{"type":"research_set","tech":${q(tech)},"queue":[${quotedList(plan)}]}""")
    }

    private fun applyAttack(gi: GameInfo, actor: Civilization, target: String?, params: MutableMap<String, Any?>,
                            events: MutableList<String>, stayOnTile: Boolean) {
        val unit = requireOwnUnit(gi, actor, target)
        val x = integer(params, "x")
        val y = integer(params, "y")
        if (x == null || y == null) throw Fail("BAD_PARAMS", "需要 x 与 y")

        val paths: PathsToTilesWithinTurn
        val targets: ArrayList<AttackableTile>
        try {
            paths = unit.movement.getDistanceToTiles()
            targets = TargetHelper.getAttackableEnemies(unit, paths, null, stayOnTile)
        } catch (e: Throwable) {
            throw Fail("ILLEGAL_TARGET", "算不出攻击路线: " + e.message)
        }

        var hit: AttackableTile? = null
        val seen = LinkedHashSet<String>()
        for (at in targets) {
            val p = at.tileToAttack.position
            seen.add("${p.x},${p.y}")
            if (p.x == x && p.y == y) hit = at
        }
        val candidates = ArrayList<String>(seen)
        if (hit == null && stayOnTile) {
            try {
                if (!unit.baseUnit.isRanged() && !unit.baseUnit.isAirUnit() && !unit.isNuclearWeapon())
                    throw Fail("NOT_AVAILABLE", unit.name + " 不是远程单位，用 attack 而不是 bombard")
                val mustSetUp = unit.hasUnique(
                    com.unciv.models.ruleset.unique.UniqueType.MustSetUp, actor.state, false)
                if (mustSetUp && !unit.isSetUpForSiege())
                    throw Fail("NOT_AVAILABLE", unit.name + " 要先架设：unit_action SetUp，然后才能 bombard")
            } catch (f: Fail) {
                throw f
            } catch (ignored: Throwable) { }
        }
        if (hit == null) {
            val tgt = tileAt(gi, x, y)
            if (tgt != null) {
                var owner: Civilization? = null
                try {
                    if (tgt.isCityCenter() && tgt.getCity() != null) {
                        owner = tgt.getCity()!!.civ
                    } else {
                        val uit = tgt.getUnits().iterator()
                        if (uit.hasNext()) owner = uit.next().civ
                    }
                } catch (ignored: Throwable) { }
                if (owner != null && owner !== actor && !actor.isAtWarWith(owner))
                    throw Fail("NOT_AVAILABLE", "还没和 " + owner.civName + " 宣战，先 declare_war", candidates)
            }
            var hint = ""
            try {
                val ranged = !unit.baseUnit.isMelee()
                if (ranged && candidates.isEmpty())
                    hint = "；射程内没有可见目标，远程攻击要求目标在视野里，先派单位靠近"
                else if (!ranged && candidates.isEmpty())
                    hint = "；附近没有可打的敌人，近战要求相邻"
            } catch (ignored: Throwable) { }
            throw Fail("ILLEGAL_TARGET", "打不到 $x,$y" +
                (if (stayOnTile) "（只能原地轰击）" else "") + hint, candidates)
        }

        val attacker = MapUnitCombatant(unit)
        try {
            if (stayOnTile && hit.tileToAttack.isCityCenter() &&
                hit.tileToAttack.getCity() != null &&
                hit.tileToAttack.getCity()!!.health <= 1)
                throw Fail("NOT_AVAILABLE", "这座城只剩 1 血，远程打不下来，需要近战单位走进去占领")
        } catch (f: Fail) {
            throw f
        } catch (ignored: Throwable) { }
        val from = unit.getTile().position
        val dmg: Battle.DamageDealt?
        try {
            if (!stayOnTile) Battle.movePreparingAttack(attacker, hit, false)
            dmg = Battle.attackOrNuke(attacker, hit)
        } catch (e: Throwable) {
            // 引擎自己抛异常时不能漏成 INTERNAL：模型需要知道这是哪一步不行
            throw Fail("NOT_AVAILABLE", "引擎拒绝这次攻击（" + e.javaClass.simpleName +
                ": " + e.message + "）；多半是目标格上有被俘单位，或路线判定与引擎不一致")
        }
        val at = unit.getTile().position
        events.add(
            """{"type":"attacked","unit":${q("unit-" + unit.id)},"target":[$x,$y],"from":[${from.x},${from.y}],"at":[${at.x},${at.y}],"damageDealt":${dmg?.attackerDealt ?: 0},"damageTaken":${dmg?.defenderDealt ?: 0}}"""
        )
    }

    private fun applyPromote(gi: GameInfo, actor: Civilization, target: String?, params: MutableMap<String, Any?>, events: MutableList<String>) {
        val unit = requireOwnUnit(gi, actor, target)
        val promo = str(params, "promotion") ?: throw Fail("BAD_PARAMS", "需要 promotion")

        if (!unit.promotions.canBePromoted()) throw Fail("NOT_AVAILABLE", "这个单位现在没有可用的晋升")

        val available = ArrayList<String>()
        for (p in unit.promotions.getAvailablePromotions()) available.add(p.name)
        if (!available.contains(promo)) {
            throw Fail("UNKNOWN_ID", "这个晋升不可选: $promo", available)
        }
        unit.promotions.addPromotion(promo, false)
        events.add(
            """{"type":"promoted","unit":${q("unit-" + unit.id)},"promotion":${q(promo)}}"""
        )
    }

    private fun applyUnitAction(gi: GameInfo, actor: Civilization, target: String?, params: MutableMap<String, Any?>, events: MutableList<String>) {
        val unit = requireOwnUnit(gi, actor, target)
        val action = str(params, "action") ?: throw Fail("BAD_PARAMS", "需要 action")
        val actionId = str(params, "id")

        val type: UnitActionType
        try {
            type = UnitActionType.valueOf(action)
        } catch (e: IllegalArgumentException) {
            throw Fail("UNKNOWN_ACTION", "没有这个动作类型: $action")
        }
        // Automate 在引擎里只是个开关，真正的自动移动由客户端的 AutoPlayMenu 每回合驱动，
        // headless 下没人跑。这里直接跑一遍自动化，把「打开开关」变成「这回合替我干活」。
        if (type == UnitActionType.Automate) {
            val x0 = unit.getTile().position.x
            val y0 = unit.getTile().position.y
            try {
                val view = com.unciv.view.GameView(gi, actor, false)
                com.unciv.logic.automation.unit.UnitAutomation.automateUnitMoves(
                    view.getMapUnitView(unit), com.unciv.logic.automation.civilization.AutomationCache())
            } catch (e: Throwable) {
                throw Fail("NOT_AVAILABLE", "自动行动失败: " + e.message)
            }
            if (unit.isDestroyed || unit.getTile() == null)
                throw Fail("NOT_AVAILABLE", "单位在自动行动中消失了（可能被吞并/解散）")
            val now = unit.getTile()
            val impAfter = now.improvementInProgress
            events.add(
                """{"type":"automated","unit":${q("unit-" + unit.id)},"from":[$x0,$y0],"at":[${now.position.x},${now.position.y}],"improvement":${impAfter?.let { q(it) } ?: "null"},"turns":${now.turnsToImprovement},"moves":${unit.currentMovement}}"""
            )
            return
        }

        var ok: Boolean
        try {
            ok = UnitActions.invokeUnitAction(unit, type, actionId)
        } catch (e: Throwable) {
            throw Fail("ILLEGAL_TARGET", "执行失败: " + e.message)
        }
        if (!ok) throw Fail("NOT_AVAILABLE", "这个动作现在不可用: $action")
        events.add("""{"type":"unit_action","unit":${q("unit-" + unit.id)},"action":${q(action)}}""")
    }

    private fun applyCityFocus(gi: GameInfo, actor: Civilization, target: String?, params: MutableMap<String, Any?>, events: MutableList<String>) {
        val city = requireOwnCity(gi, actor, target)
        val focus = str(params, "focus") ?: throw Fail("BAD_PARAMS", "需要 focus")
        val f: com.unciv.logic.city.CityFocus
        try {
            f = com.unciv.logic.city.CityFocus.valueOf(focus)
        } catch (e: IllegalArgumentException) {
            val names = ArrayList<String>()
            for (v in com.unciv.logic.city.CityFocus.values()) names.add(v.name)
            throw Fail("UNKNOWN_ID", "没有这个焦点: $focus", names)
        }
        city.setCityFocus(f)
        events.add(
            """{"type":"city_focus_set","city":${q("city-" + city.id)},"focus":${q(f.name)}}"""
        )
    }

    private fun applyPurchase(gi: GameInfo, actor: Civilization, target: String?, params: MutableMap<String, Any?>, events: MutableList<String>) {
        val city = requireOwnCity(gi, actor, target)
        val item = str(params, "item") ?: throw Fail("BAD_PARAMS", "需要 item")

        val rs = gi.ruleset
        if (!rs.buildings.containsKey(item) && !rs.units.containsKey(item)) {
            val candidates = ArrayList<String>()
            for (k in rs.buildings.keys) {
                if (k.lowercase().contains(item.lowercase())) candidates.add(k)
                if (candidates.size >= 8) break
            }
            throw Fail("UNKNOWN_ID", "没有这个可购买项: $item", candidates)
        }

        val currency = str(params, "currency")
        var stat = com.unciv.models.stats.Stat.Gold
        if (currency != null) {
            try {
                stat = com.unciv.models.stats.Stat.valueOf(currency)
            } catch (e: IllegalArgumentException) {
                throw Fail("UNKNOWN_ID", "没有这种货币: $currency", listOf("Gold", "Faith"))
            }
            if (stat != com.unciv.models.stats.Stat.Gold && stat != com.unciv.models.stats.Stat.Faith)
                throw Fail("NOT_AVAILABLE", "只能用 Gold 或 Faith 购买")
        }

        // purchaseConstruction 自己不做可购买性检查（UI 是按钮禁用），这里先按引擎规则验一遍
        var buyable: com.unciv.models.ruleset.INonPerpetualConstruction? = null
        var price: Int? = null
        try {
            val con = city.cityConstructions.getConstruction(item)
            if (con is com.unciv.models.ruleset.INonPerpetualConstruction) {
                buyable = con
                price = con.getStatBuyCost(city, stat)
            }
        } catch (ignored: Throwable) { }
        if (buyable == null) throw Fail("NOT_AVAILABLE", "$item 不能购买（不是可购买的项目）")
        if (price == null) throw Fail("NOT_AVAILABLE", "$item 不能用 " + stat.name + " 购买")
        var alreadyBuilt = false
        try {
            alreadyBuilt = city.cityConstructions.isBuilt(item)
        } catch (ignored: Throwable) { }
        if (alreadyBuilt)
            throw Fail("NOT_AVAILABLE", "$item 已经建过了")

        var purchasable = true
        try {
            purchasable = buyable.isPurchasable(city.cityConstructions)
        } catch (ignored: Throwable) { }
        if (!purchasable)
            throw Fail("NOT_AVAILABLE", "$item 现在买不了：缺前置（科技/建筑）")
        if (!city.cityConstructions.isConstructionPurchaseAllowed(buyable, stat, price)) {
            val have = if (stat == com.unciv.models.stats.Stat.Faith)
                actor.religionManager.storedFaith else actor.gold
            if (have < price)
                throw Fail("NOT_AVAILABLE", "买不起 $item：要 $price " + stat.name + "，现有 $have")
            throw Fail("NOT_AVAILABLE", "现在不能买 $item：傀儡城 / 抵抗中 / 城里放不下新单位")
        }

        var ok: Boolean
        try {
            ok = city.cityConstructions.purchaseConstruction(buyable, -1, false, stat, null)
        } catch (e: Throwable) {
            throw Fail("NOT_AVAILABLE", "购买失败: " + e.message)
        }
        if (!ok) throw Fail("NOT_AVAILABLE", "购买没有生效: $item")
        events.add(
            """{"type":"purchased","city":${q("city-" + city.id)},"item":${q(item)},"currency":${q(stat.name)},"goldLeft":${actor.gold},"faithLeft":${actor.religionManager.storedFaith}}"""
        )
    }

    private fun requireOtherCiv(gi: GameInfo, me: Civilization, nameOrId: String?): Civilization {
        if (nameOrId == null) throw Fail("BAD_PARAMS", "需要 civ")
        val candidates = ArrayList<String>()
        for (c in gi.civilizations) {
            if (c === me) continue
            candidates.add(c.civName)
            if (c.civName == nameOrId || c.civID == nameOrId) return c
        }
        throw Fail("UNKNOWN_ID", "没有这个文明: $nameOrId", candidates)
    }

    private fun applyDeclareWar(gi: GameInfo, actor: Civilization, target: String?, params: MutableMap<String, Any?>, events: MutableList<String>) {
        val other = requireOtherCiv(gi, actor, civRef(params, target))
        val dm = actor.getDiplomacyManager(other)
        if (dm == null) throw Fail("NOT_AVAILABLE", "还没遇到这个文明")
        if (actor.isAtWarWith(other)) throw Fail("NOT_AVAILABLE", "已经和 " + other.civName + " 交战")
        if (!dm.canDeclareWar()) throw Fail("NOT_AVAILABLE", "现在不能宣战")
        dm.declareWar(com.unciv.logic.civilization.diplomacy.DeclareWarReason(
            com.unciv.logic.civilization.diplomacy.WarType.DirectWar, null))
        events.add("""{"type":"war_declared","civ":${q(other.civName)}}""")
    }

    private fun applyMakePeace(gi: GameInfo, actor: Civilization, target: String?, params: MutableMap<String, Any?>, events: MutableList<String>) {
        val other = requireOtherCiv(gi, actor, civRef(params, target))
        val dm = actor.getDiplomacyManager(other)
        if (dm == null) throw Fail("NOT_AVAILABLE", "还没遇到这个文明")
        if (!actor.isAtWarWith(other)) throw Fail("NOT_AVAILABLE", "和 " + other.civName + " 本来就不在交战")
        dm.makePeace()
        events.add("""{"type":"peace_made","civ":${q(other.civName)}}""")
    }

    private fun applyAdoptPolicy(gi: GameInfo, actor: Civilization, params: MutableMap<String, Any?>, events: MutableList<String>) {
        val name = str(params, "policy") ?: throw Fail("BAD_PARAMS", "需要 policy")

        val policy = gi.ruleset.policies[name]
        if (policy == null) {
            val candidates = ArrayList<String>()
            for (k in gi.ruleset.policies.keys) {
                if (k.lowercase().contains(name.lowercase())) candidates.add(k)
                if (candidates.size >= 8) break
            }
            throw Fail("UNKNOWN_ID", "没有这个政策: $name", candidates)
        }
        if (actor.policies.isAdopted(name)) throw Fail("NOT_AVAILABLE", "已经采纳过 $name")
        try {
            actor.policies.adopt(policy, false)
        } catch (e: Throwable) {
            throw Fail("NOT_AVAILABLE", "采纳失败: " + e.message)
        }
        events.add("""{"type":"policy_adopted","policy":${q(name)}}""")
    }

    private fun applyEndTurn(gi: GameInfo, actor: Civilization, ref: UncivGameRef, events: MutableList<String>) {
        // 交回合前把"你大概忘了"的东西说出来，避免因为没看见而白丢一回合
        val warnings = ArrayList<String>()
        var idle = 0
        for (u in actor.units.getCivUnits()) {
            if (u.currentMovement > 0.1f && !u.isTransported) idle++
        }
        if (idle > 0) warnings.add("$idle 个单位还有移动力")
        var idleCities = 0
        for (c in actor.cities) {
            if (c.isPuppet || c.isInResistance()) continue
            val item = c.cityConstructions.getCurrentConstruction().name
            if (item == null || item.isEmpty() || item == "Nothing") idleCities++
        }
        if (idleCities > 0) warnings.add("$idleCities 座城没有在生产任何东西")
        try {
            val researching = actor.tech.currentTechnologyName()
            if (researching == null || researching.isEmpty())
                warnings.add("没有在研究的科技，这回合的科研会浪费")
        } catch (ignored: Throwable) { }
        try {
            val gpt = actor.stats.statsForNextTurn.gold
            val treasury = actor.gold
            // 引擎只在国库 <= -200 且还在亏的时候解散军队（TurnManager.endTurn）
            if (treasury <= -200)
                warnings.add("国库 $treasury，已经在解散军队来抵债了")
            else if (gpt < 0) {
                val turnsLeft = Math.floor((treasury + 200) / -gpt.toDouble()).toInt()
                warnings.add("每回合亏 " + round1(gpt) + " 金币，国库 $treasury" +
                    "，跌破 -200 才会解散军队" +
                    (if (turnsLeft > 0) "，大约 $turnsLeft 回合" else ""))
            }
        } catch (ignored: Throwable) { }
        var pending = 0
        for (a in actor.popupAlerts)
            if (a.type == com.unciv.logic.civilization.AlertType.CityConquered) pending++
        if (pending > 0) warnings.add("$pending 座刚打下的城还没决定吞并/傀儡")

        val before = gi.turns
        ref.advanceTurn(gi, actor)
        events.add(
            """{"type":"turn_advanced","from":$before,"to":${gi.turns},"currentCiv":${q(actor.civName)},"warnings":[${quotedList(warnings)}]}"""
        )
    }

    /** City 上 workedTiles 属性与 getWorkedTiles() 函数在 JVM 里同名，Java 里取不了，这里换个路子。 */
    @JvmStatic
    fun workedTilesOf(city: City): List<Tile> {
        val out = ArrayList<Tile>()
        for (t in city.getWorkableTiles()) {
            if (city.isWorked(t)) out.add(t)
        }
        return out
    }

    /** 文明引用：优先 params.civ，其次 target（允许直接把文明名当 target） */
    private fun civRef(params: MutableMap<String, Any?>, target: String?): String? {
        val civ = str(params, "civ")
        if (civ != null) return civ
        if (target != null && !target.startsWith("unit-") && !target.startsWith("city-")) return target
        return null
    }

    // ---------- 交易 ----------

    @JvmStatic
    fun offersJson(offers: Iterable<TradeOffer>): String {
        val sb = StringBuilder("[")
        var first = true
        for (o in offers) {
            if (!first) sb.append(',')
            first = false
            sb.append("""{"name":""").append(q(o.name))
                .append(""","type":""").append(q(o.type.name))
                .append(""","amount":""").append(o.amount)
                .append(""","duration":""").append(o.duration).append('}')
        }
        return sb.append(']').toString()
    }

    @JvmStatic
    fun round1(v: Float): String = (Math.round(v * 10f) / 10f).toString()

    @JvmStatic
    fun quotedList(values: List<String>): String {
        val sb = StringBuilder()
        for (i in values.indices) {
            if (i > 0) sb.append(',')
            sb.append(q(values[i]))
        }
        return sb.toString()
    }

    /**
     * 把 {"name":..,"type":..,"amount":..} 落到 staged 里。
     * 只接受 TradeLogic 当场算出来的可用项，避免造出引擎不认的报价。
     */
    private fun stageOffer(available: TradeOffersList, staged: TradeOffersList, spec: Any?, bad: MutableList<String>) {
        val m = obj(spec)
        val name = str(m, "name")
        if (name == null) { bad.add("<缺 name>"); return }
        val typeName = str(m, "type")
        val amount = integer(m, "amount")

        var found: TradeOffer? = null
        for (o in available) {
            if (o.name != name) continue
            if (typeName != null && !o.type.name.equals(typeName, ignoreCase = true)) continue
            found = o
            break
        }
        if (found == null) {
            bad.add(if (typeName == null) name else "$name ($typeName)")
            return
        }

        var amt = if (amount == null) Math.max(1, found.amount) else amount
        if (amt <= 0) amt = 1
        when (found.type) {
            TradeOfferType.Gold,
            TradeOfferType.Gold_Per_Turn,
            TradeOfferType.Luxury_Resource,
            TradeOfferType.Strategic_Resource,
            TradeOfferType.Stockpiled_Resource ->
                amt = Math.min(amt, Math.max(1, found.amount))
            else -> amt = 1
        }
        staged.add(TradeOffer(found.name, found.type, amt, found.duration))
    }

    private fun applyProposeTrade(gi: GameInfo, actor: Civilization, target: String?, params: MutableMap<String, Any?>, events: MutableList<String>) {
        val other = requireOtherCiv(gi, actor, civRef(params, target))
        if (actor.getDiplomacyManager(other) == null)
            throw Fail("NOT_AVAILABLE", "还没遇到 " + other.civName)

        val tl = TradeLogic(actor, other)
        val bad = ArrayList<String>()
        for (o in arr(params["give"]))
            stageOffer(tl.ourAvailableOffers, tl.currentTrade.ourOffers, o, bad)
        for (o in arr(params["want"]))
            stageOffer(tl.theirAvailableOffers, tl.currentTrade.theirOffers, o, bad)
        if (bad.isNotEmpty())
            throw Fail("NOT_AVAILABLE", "这些不在可交易范围内: " + bad.joinToString("、"), bad)

        val trade = tl.currentTrade
        if (trade.ourOffers.isEmpty() && trade.theirOffers.isEmpty())
            throw Fail("BAD_PARAMS", "give 和 want 都是空的")

        other.tradeRequests.removeAll { actor.civID == it.requestingCiv }
        other.tradeRequests.add(TradeRequest(actor.civID, trade.reverse()))
        actor.cache.updateCivResources()

        events.add(
            """{"type":"trade_proposed","civ":${q(other.civName)},"give":${offersJson(trade.ourOffers)},"want":${offersJson(trade.theirOffers)},"note":${q("对方要到下一回合才回应，届时 /trade 的 pending 里会出现回复或还价")}}"""
        )
    }

    private fun applyRespondTrade(gi: GameInfo, actor: Civilization, target: String?, params: MutableMap<String, Any?>, events: MutableList<String>) {
        val other = requireOtherCiv(gi, actor, civRef(params, target))
        val acc = params["accept"]
        val accept = acc !is Boolean || acc as Boolean
        val idxParam = integer(params, "index")
        val idx = idxParam ?: 0

        val mine = ArrayList<TradeRequest>()
        for (r in actor.tradeRequests)
            if (other.civID == r.requestingCiv) mine.add(r)
        if (mine.isEmpty())
            throw Fail("NOT_AVAILABLE", other.civName + " 没有待回应的交易")
        if (idx < 0 || idx >= mine.size)
            throw Fail("BAD_PARAMS", "index 越界，共有 ${mine.size} 条")

        val req = mine[idx]
        val give = offersJson(req.trade.theirOffers)
        val want = offersJson(req.trade.ourOffers)

        if (accept) {
            val tl = TradeLogic(actor, other)
            tl.currentTrade.set(req.trade)
            try {
                tl.acceptTrade(true)
            } catch (e: Throwable) {
                throw Fail("NOT_AVAILABLE", "成交失败: " + e.message)
            }
        } else {
            req.decline(actor)
        }
        actor.tradeRequests.remove(req)

        events.add(
            """{"type":"${if (accept) "trade_accepted" else "trade_declined"}","civ":${q(other.civName)},"give":$give,"want":$want}"""
        )
    }

    // ---------- 宗教 ----------

    private fun resolveBeliefs(gi: GameInfo, raw: List<Any?>): List<Belief> {
        val rs = gi.ruleset
        val out = ArrayList<Belief>()
        val bad = ArrayList<String>()
        for (o in raw) {
            val name = o as? String
            val b = if (name == null) null else rs.beliefs[name]
            if (b == null) { bad.add(o.toString()); continue }
            out.add(b)
        }
        if (bad.isNotEmpty()) {
            val cand = ArrayList<String>()
            for (k in rs.beliefs.keys) {
                for (b in bad) {
                    if (k.lowercase().contains(b.lowercase())) { cand.add(k); break }
                }
                if (cand.size >= 10) break
            }
            throw Fail("UNKNOWN_ID", "没有这些信念: " + bad.joinToString("、"), cand)
        }
        return out
    }

    private fun expectedBeliefs(rm: ReligionManager, state: ReligionState): Map<String, Int> {
        val expected = LinkedHashMap<String, Int>()
        if (state == ReligionState.None || state == ReligionState.Pantheon) {
            expected[BeliefType.Pantheon.name] = 1
            return expected
        }
        val counter = if (state == ReligionState.EnhancingReligion)
            rm.getBeliefsToChooseAtEnhancing()
        else rm.getBeliefsToChooseAtFounding()
        for ((k, v) in counter) {
            if (v != null && v > 0) expected[k.name] = v
        }
        return expected
    }

    private fun applyChooseBeliefs(gi: GameInfo, actor: Civilization, target: String?, params: MutableMap<String, Any?>, events: MutableList<String>) {
        if (!gi.isReligionEnabled()) throw Fail("NOT_AVAILABLE", "这个规则集没开宗教")
        val rm = actor.religionManager

        // 带 unit 时先把大先知用掉，一步完成立教
        val unitRef = target ?: str(params, "unit")
        if (unitRef != null && rm.religionState != ReligionState.FoundingReligion) {
            val prophet = requireOwnUnit(gi, actor, unitRef)
            var ok: Boolean
            try {
                ok = UnitActions.invokeUnitAction(prophet, UnitActionType.FoundReligion, null)
            } catch (e: Throwable) {
                throw Fail("NOT_AVAILABLE", "立教失败: " + e.message)
            }
            if (!ok) throw Fail("NOT_AVAILABLE", prophet.name + " 现在不能立教")
        }

        val chosen = resolveBeliefs(gi, arr(params["beliefs"]))

        val state = rm.religionState
        if (state == ReligionState.None || state == ReligionState.Pantheon) {
            if (!rm.canFoundOrExpandPantheon()) {
                val why: String
                if (state == ReligionState.Pantheon)
                    why = "已经立过万神殿了，下一步是攒够信仰并派大先知立教"
                else if (rm.storedFaith < rm.faithForPantheon(0))
                    why = "还不能立万神殿：信仰 ${rm.storedFaith}/${rm.faithForPantheon(0)}"
                else
                    why = "现在不能立万神殿（万神殿名额已满，或本局没开宗教）"
                throw Fail("NOT_AVAILABLE", why)
            }
        } else if (state != ReligionState.FoundingReligion && state != ReligionState.EnhancingReligion) {
            throw Fail("NOT_AVAILABLE", "现在不能选信念，状态是 " + state.name)
        }

        val expected = expectedBeliefs(rm, state)
        var total = 0
        val want = ArrayList<String>()
        for ((k, v) in expected) {
            total += v
            want.add(k + "x" + v)
        }
        if (chosen.size != total)
            throw Fail("BAD_PARAMS", "这次要选 $total 个信念（" + want.joinToString(" ") +
                "），收到 ${chosen.size} 个", want)

        var name = str(params, "name")
        var displayName = str(params, "displayName")
        if (state == ReligionState.FoundingReligion) {
            if (name == null) throw Fail("BAD_PARAMS", "立国教需要 name（宗教标识）")
            if (displayName == null) displayName = name
            try {
                rm.foundReligion(displayName, name)
            } catch (e: Throwable) {
                throw Fail("NOT_AVAILABLE", "立教失败: " + e.message)
            }
        }
        try {
            rm.chooseBeliefs(chosen, rm.usingFreeBeliefs())
        } catch (e: Throwable) {
            throw Fail("NOT_AVAILABLE", "选信念失败: " + e.message)
        }

        val rel = rm.religion
        val names = ArrayList<String>()
        for (b in chosen) names.add(b.name)
        events.add(
            """{"type":"beliefs_chosen","state":${q(rm.religionState.name)},"religion":${if (rel == null) "null" else q(rel.displayName ?: rel.name)},"beliefs":[${quotedList(names)}]}"""
        )
    }

    // ---------- 城邦 ----------

    private fun applyGiftCityState(gi: GameInfo, actor: Civilization, target: String?, params: MutableMap<String, Any?>, events: MutableList<String>) {
        val cs = requireOtherCiv(gi, actor, civRef(params, target))
        if (!cs.isCityState) throw Fail("NOT_AVAILABLE", cs.civName + " 不是城邦")
        val gold = integer(params, "gold")
        val amount = gold ?: 250
        if (amount <= 0) throw Fail("BAD_PARAMS", "gold 必须是正数")
        if (actor.gold < amount)
            throw Fail("NOT_AVAILABLE", "金币不够，现在只有 ${actor.gold}")

        val gain = cs.cityStateFunctions.influenceGainedByGift(actor, amount)
        try {
            cs.cityStateFunctions.receiveGoldGift(actor, amount)
        } catch (e: Throwable) {
            throw Fail("NOT_AVAILABLE", "送礼失败: " + e.message)
        }
        var influence = 0
        // 影响力存在城邦那一侧的 DiplomacyManager 上
        if (cs.getDiplomacyManager(actor) != null)
            influence = cs.getDiplomacyManager(actor)!!.getInfluence().toInt()
        events.add(
            """{"type":"city_state_gift","civ":${q(cs.civName)},"gold":$amount,"influenceGained":$gain,"influence":$influence}"""
        )
    }

    private fun applyPledgeProtect(gi: GameInfo, actor: Civilization, target: String?, params: MutableMap<String, Any?>, events: MutableList<String>) {
        val cs = requireOtherCiv(gi, actor, civRef(params, target))
        if (!cs.isCityState) throw Fail("NOT_AVAILABLE", cs.civName + " 不是城邦")
        val p = params["protect"]
        val protect = p !is Boolean || p as Boolean

        val f: CityStateFunctions = cs.cityStateFunctions
        if (protect) {
            if (!f.otherCivCanPledgeProtection(actor)) {
                var why = "现在不能承诺保护 " + cs.civName
                try {
                    val dm = cs.getDiplomacyManager(actor)
                    if (dm != null) {
                        val inf = dm.getInfluence().toInt()
                        if (inf < 0) why += "：影响力是负的（$inf），回到 0 以上才行"
                        else if (dm.diplomaticStatus == com.unciv.logic.civilization.diplomacy.DiplomaticStatus.Protector)
                            why += "：已经在保护了"
                        else why += "：影响力 $inf，或处于战争状态"
                    }
                } catch (ignored: Throwable) { }
                throw Fail("NOT_AVAILABLE", why)
            }
            f.addProtectorCiv(actor)
        } else {
            if (!f.otherCivCanWithdrawProtection(actor)) {
                val dm = cs.getDiplomacyManager(actor)
                val isProtector = dm != null && dm.diplomaticStatus ==
                    com.unciv.logic.civilization.diplomacy.DiplomaticStatus.Protector
                throw Fail("NOT_AVAILABLE", if (isProtector)
                    "刚承诺保护，10 回合内不能撤回"
                else "现在没有对 " + cs.civName + " 的保护承诺")
            }
            f.removeProtectorCiv(actor, false)
        }
        events.add(
            """{"type":"${if (protect) "pledged_protection" else "withdrew_protection"}","civ":${q(cs.civName)}}"""
        )
    }

    // ---------- 地块分配 ----------

    private fun applySetWorkedTiles(gi: GameInfo, actor: Civilization, target: String?, params: MutableMap<String, Any?>, events: MutableList<String>) {
        val city = requireOwnCity(gi, actor, target)
        val raw = arr(params["tiles"])
        if (raw.isEmpty()) throw Fail("BAD_PARAMS", "需要 tiles 数组，元素是 [x,y]")

        val want = LinkedHashSet<HexCoord>()
        for (o in raw) {
            val pair = arr(o)
            if (pair.size < 2 || pair[0] !is Number || pair[1] !is Number)
                throw Fail("BAD_PARAMS", "tiles 里每一项要是 [x,y]")
            want.add(HexCoord((pair[0] as Number).toInt(), (pair[1] as Number).toInt()))
        }

        val bad = ArrayList<String>()
        for (c in want) {
            val t = tileAt(gi, c.x, c.y)
            if (t == null) { bad.add("${c.x},${c.y}"); continue }
            var workable = false
            for (wt in city.getWorkableTiles()) if (wt === t) { workable = true; break }
            if (!workable) bad.add("${c.x},${c.y}")
        }
        if (bad.isNotEmpty())
            throw Fail("ILLEGAL_TARGET", "这些格子不能被这座城工作: " + bad.joinToString(" "), bad)

        val pop = city.population.population
        if (want.size > pop)
            throw Fail("BAD_PARAMS", "人口只有 $pop，不能同时工作 ${want.size} 格")

        val current = workedTilesOf(city)
        for (t in current)
            if (!want.contains(t.position)) city.stopWorkingTile(t)

        for (c in want) {
            val t = tileAt(gi, c.x, c.y)
            if (t == null || city.isWorked(t)) continue
            try {
                city.workTile(t)
            } catch (e: Throwable) {
                throw Fail("NOT_AVAILABLE", "分配 ${c.x},${c.y} 失败: " + e.message)
            }
        }

        val lockObj = params["lock"]
        val lock = lockObj !is Boolean || lockObj as Boolean
        if (lock) {
            for (c in want) {
                val t = tileAt(gi, c.x, c.y)
                if (t != null && city.isWorked(t)) city.lockTile(t)
            }
        }

        val fillObj = params["autoFill"]
        val autoFill = fillObj !is Boolean || fillObj as Boolean
        if (autoFill) city.population.autoAssignPopulation()

        val worked = ArrayList<String>()
        for (t in workedTilesOf(city)) {
            val p = t.position
            worked.add("[${p.x},${p.y}]")
        }
        events.add(
            """{"type":"worked_tiles_set","city":${q("city-" + city.id)},"worked":[${worked.joinToString(",")}]}"""
        )
    }

    // ---------- 核弹 ----------

    private fun applyNuke(gi: GameInfo, actor: Civilization, target: String?, params: MutableMap<String, Any?>, events: MutableList<String>) {
        val unit = requireOwnUnit(gi, actor, target)
        val x = integer(params, "x")
        val y = integer(params, "y")
        if (x == null || y == null) throw Fail("BAD_PARAMS", "需要 x 与 y")
        if (!unit.isNuclearWeapon()) throw Fail("NOT_AVAILABLE", unit.name + " 不是核武器")

        val t = tileAt(gi, x, y) ?: throw Fail("ILLEGAL_TARGET", "没有这个格子: $x,$y")
        val combatant = MapUnitCombatant(unit)
        if (!Nuke.mayUseNuke(combatant, t))
            throw Fail("ILLEGAL_TARGET", "打不到这个格子（射程 " + unit.getRange() + "）或者会被外交规则挡住")

        try {
            Nuke.NUKE(combatant, t)
        } catch (e: Throwable) {
            throw Fail("NOT_AVAILABLE", "核打击失败: " + e.message)
        }
        events.add("""{"type":"nuke","unit":${q("unit-" + unit.id)},"at":[$x,$y]}""")
    }

    // ---------- 城市开火 ----------

    private fun applyCityBombard(gi: GameInfo, actor: Civilization, target: String?, params: MutableMap<String, Any?>, events: MutableList<String>) {
        val city = requireOwnCity(gi, actor, target)
        val x = integer(params, "x")
        val y = integer(params, "y")
        if (x == null || y == null) throw Fail("BAD_PARAMS", "需要 x 与 y")
        if (!city.canBombard()) throw Fail("NOT_AVAILABLE", city.name + " 现在不能开火（可能已经打过，或被围攻）")

        val combatant = com.unciv.logic.battle.CityCombatant(city)
        val candidates = ArrayList<String>()
        var hit: Tile? = null
        for (t in TargetHelper.getBombardableTiles(city)) {
            candidates.add("${t.position.x},${t.position.y}")
            if (t.position.x == x && t.position.y == y) hit = t
        }
        if (hit == null)
            throw Fail("ILLEGAL_TARGET", "城市射程内没有这个目标（射程 " + city.getBombardRange() + "）", candidates)

        val at = com.unciv.logic.battle.AttackableTile(
            city.getCenterTile(), hit, 1f, Battle.getMapCombatantOfTile(hit))
        val dmg = Battle.attackOrNuke(combatant, at)
        events.add(
            """{"type":"city_bombarded","city":${q("city-" + city.id)},"at":[$x,$y],"damageDealt":${dmg?.attackerDealt ?: 0},"damageTaken":${dmg?.defenderDealt ?: 0}}"""
        )
    }

    // ---------- 单位升级 ----------

    private fun applyUpgradeUnit(gi: GameInfo, actor: Civilization, target: String?, params: MutableMap<String, Any?>, events: MutableList<String>) {
        val unit = requireOwnUnit(gi, actor, target)
        val to = str(params, "to")

        val ups = unit.upgrade.getUpgradesOfKind(false)
        val names = ArrayList<String>()
        var pick: PossibleUpgrade? = null
        for (u in ups) {
            if (!u.isShownToPlayer) continue
            names.add(u.upgradedUnit.name)
            if (to != null && u.upgradedUnit.name.equals(to, ignoreCase = true)) pick = u
        }
        if (pick == null && to == null) {
            if (names.size == 1) {
                for (u in ups) if (u.isShownToPlayer) pick = u
            } else if (names.isEmpty()) {
                throw Fail("NOT_AVAILABLE", unit.name + " 现在没有可升级的目标")
            } else {
                throw Fail("BAD_PARAMS", "有多个升级目标，请用 to 指定", names)
            }
        }
        if (pick == null) throw Fail("UNKNOWN_ID", "不能升级到 $to", names)

        if (!unit.upgrade.canPerformUpgrade(pick, false))
            throw Fail("NOT_AVAILABLE", "升级条件不满足（要 " + pick.goldCostOfUpgrade +
                " 金币，现在 " + actor.gold + "）")

        val from = unit.name
        try {
            unit.upgrade.performUpgrade(pick, false)
        } catch (e: Throwable) {
            throw Fail("NOT_AVAILABLE", "升级失败: " + e.message)
        }
        events.add(
            """{"type":"unit_upgraded","unit":${q("unit-" + unit.id)},"from":${q(from)},"to":${q(pick.upgradedUnit.name)},"goldCost":${pick.goldCostOfUpgrade}}"""
        )
    }

    // ---------- 间谍 ----------

    private fun applySpyMove(gi: GameInfo, actor: Civilization, params: MutableMap<String, Any?>, events: MutableList<String>) {
        val ref = str(params, "spy") ?: throw Fail("BAD_PARAMS", "需要 spy（名字或序号）")
        val spies = actor.espionageManager.spyList
        var spy: Spy? = null
        for (i in spies.indices) {
            if (spies[i].name == ref || i.toString() == ref) spy = spies[i]
        }
        if (spy == null) {
            val cand = ArrayList<String>()
            for (s in spies) cand.add(s.name)
            throw Fail("UNKNOWN_ID", "没有这个间谍: $ref", cand)
        }

        val cityRef = str(params, "city")
        if (cityRef == null || cityRef.isEmpty() || cityRef.equals("hideout", ignoreCase = true)) {
            spy.moveTo(null)
            events.add("""{"type":"spy_moved","spy":${q(spy.name)},"to":"hideout"}""")
            return
        }

        var dest: City? = null
        val cand = ArrayList<String>()
        for (c in gi.getCities()) {
            cand.add(c.name)
            if (c.name == cityRef || ("city-" + c.id) == cityRef || c.id == cityRef)
                dest = c
        }
        if (dest == null) throw Fail("UNKNOWN_ID", "没有这个城市: $cityRef", cand)
        if (!spy.canMoveTo(dest)) throw Fail("NOT_AVAILABLE", "这个间谍不能去 " + dest.name)

        spy.moveTo(dest)
        events.add("""{"type":"spy_moved","spy":${q(spy.name)},"to":${q(dest.name)}}""")
    }

    // ---------- 专家分配 ----------

    private fun applySetSpecialists(gi: GameInfo, actor: Civilization, target: String?, params: MutableMap<String, Any?>, events: MutableList<String>) {
        val city = requireOwnCity(gi, actor, target)
        if (!params.containsKey("specialists"))
            throw Fail("BAD_PARAMS", "需要 specialists 对象，如 {\"Scientist\":1}；传 {} 表示全部取消")
        val want = obj(params["specialists"])

        val rs = gi.ruleset
        val allocations = city.population.specialistAllocations
        val max = city.population.getMaxSpecialists()

        val applied = LinkedHashMap<String, Int>()
        var totalAfter = 0
        for ((name, value) in want) {
            val count = if (value is Number) value.toInt() else null
            if (count == null) throw Fail("BAD_PARAMS", "$name 的数量必须是整数")
            if (!rs.specialists.containsKey(name)) {
                val cand = ArrayList(rs.specialists.keys)
                throw Fail("UNKNOWN_ID", "没有这个专家: $name", cand)
            }
            if (count < 0) throw Fail("BAD_PARAMS", "$name 不能是负数")
            val cap = max[name] ?: 0
            if (count > cap)
                throw Fail("NOT_AVAILABLE", city.name + " 最多只能有 $cap 个 " + name)
            applied[name] = count
            totalAfter += count
        }
        val pop = city.population.population
        if (totalAfter > pop)
            throw Fail("NOT_AVAILABLE", "人口只有 $pop，专家加起来会到 $totalAfter")

        // 名单里没提到的专家一律清零，传 {} 就是全部取消
        for (name in ArrayList(allocations.keys)) {
            if (applied.containsKey(name)) continue
            val cur = allocations[name] ?: 0
            if (cur != 0) allocations.add(name, -cur)
        }
        for ((name, count) in applied) {
            val cur = allocations[name] ?: 0
            val delta = count - cur
            if (delta != 0) allocations.add(name, delta)
        }
        city.manualSpecialists = true
        // 专家占的是人口，负的自由人口会让回合开始时乱分配，这里立刻把最差的地块放掉
        try {
            val free = city.population.getFreePopulation()
            if (free < 0) city.population.unassignExtraPopulation()
            else if (free > 0) city.population.autoAssignPopulation()
            // Java 版这里传的是 null，core 的非空参数在入口就抛 NPE，被外层吞掉，
            // 等于每次都没刷新城市产出。迁移时照搬会把这个 bug 一起搬过来，
            // 所以改成真正的 update()——和客户端改完专家后做的一样。
            city.cityStats.update()
        } catch (ignored: Throwable) { }

        val sb = StringBuilder()
        for ((name, count) in allocations) {
            if (count <= 0) continue
            if (sb.length > 0) sb.append(',')
            sb.append(q(name)).append(':').append(count)
        }
        events.add(
            """{"type":"specialists_set","city":${q("city-" + city.id)},"specialists":{$sb}}"""
        )
    }

    // ---------- 傀儡转直辖 ----------

    private fun applyAnnexCity(gi: GameInfo, actor: Civilization, target: String?, params: MutableMap<String, Any?>, events: MutableList<String>) {
        val city = requireOwnCity(gi, actor, target)
        if (!city.isPuppet) throw Fail("NOT_AVAILABLE", city.name + " 不是傀儡城，已经是直辖")
        try {
            city.annexCity()
        } catch (e: Throwable) {
            throw Fail("NOT_AVAILABLE", "直辖失败: " + e.message)
        }
        events.add(
            """{"type":"city_annexed","city":${q("city-" + city.id)},"name":${q(city.name)}}"""
        )
    }

    // ---------- 占城决策 ----------

    /** 找出本文明待处理的占城弹窗对应的城市 */
    private fun pendingCapture(gi: GameInfo, actor: Civilization, ref: String?): City? {
        for (alert in actor.popupAlerts) {
            if (alert.type != com.unciv.logic.civilization.AlertType.CityConquered) continue
            if (ref != null && ref != alert.value) continue
            for (c in gi.getCities()) {
                if (c.id == alert.value) return c
            }
        }
        return null
    }

    private fun applyCaptureCity(gi: GameInfo, actor: Civilization, target: String?, params: MutableMap<String, Any?>, events: MutableList<String>) {
        val cityId = if (target != null && target.startsWith("city-")) target.substring(5) else null
        val city = pendingCapture(gi, actor, cityId)
        if (city == null) {
            val waiting = ArrayList<String>()
            for (alert in actor.popupAlerts) {
                if (alert.type == com.unciv.logic.civilization.AlertType.CityConquered)
                    waiting.add("city-" + alert.value)
            }
            throw Fail("NOT_AVAILABLE", if (waiting.isEmpty())
                "现在没有待处理的占城"
            else "这个城市不在待处理列表里", waiting)
        }

        var action = str(params, "action")
        if (action == null) action = "puppet"
        action = action.lowercase()
        val mayAnnex = !actor.hasUnique(com.unciv.models.ruleset.unique.UniqueType.MayNotAnnexCities,
            actor.state)
        val name = city.name
        val oldOwner = city.civ.civName

        try {
            when (action) {
                "puppet" -> city.puppetCity(actor)
                "annex" -> {
                    if (!mayAnnex) throw Fail("NOT_AVAILABLE", "这个文明不能直辖城市")
                    city.puppetCity(actor)
                    city.annexCity()
                }
                "raze" -> {
                    if (!city.canBeDestroyed(true))
                        throw Fail("NOT_AVAILABLE", "$name 不能夷平（原首都或圣城）")
                    city.puppetCity(actor)
                    if (mayAnnex) city.annexCity()
                    city.isBeingRazed = true
                }
                "liberate" -> {
                    val founder = city.foundingCivObject
                        ?: throw Fail("NOT_AVAILABLE", "$name 没有原始所有者")
                    if (founder === actor) throw Fail("NOT_AVAILABLE", "$name 本来就是你的城")
                    city.liberateCity(actor)
                }
                else -> throw Fail("UNKNOWN_ID", "没有这个处理方式: $action",
                    listOf("puppet", "annex", "raze", "liberate"))
            }
        } catch (f: Fail) {
            throw f
        } catch (e: Throwable) {
            throw Fail("NOT_AVAILABLE", "处理占城失败: " + e.message)
        }

        // 清掉对应弹窗，否则下一次读档/回合还会挂着
        actor.popupAlerts.removeAll {
            it.type == com.unciv.logic.civilization.AlertType.CityConquered &&
                it.value == city.id
        }

        events.add(
            """{"type":"city_captured","city":${q("city-" + city.id)},"name":${q(name)},"from":${q(oldOwner)},"action":${q(action)},"isPuppet":${city.isPuppet}}"""
        )
    }

    // ---------- 对象解析 ----------

    private fun requireOwnUnit(gi: GameInfo, actor: Civilization, target: String?): MapUnit {
        val id = idOf(target, "unit") ?: throw Fail("BAD_PARAMS", "target 必须是 unit-<id>")
        for (u in actor.units.getCivUnits()) {
            if (u.id == id) return u
        }
        val mine = ArrayList<String>()
        for (u in actor.units.getCivUnits()) mine.add("unit-" + u.id)
        throw Fail("NOT_YOUR_UNIT", "你没有 id 为 $id 的单位", mine)
    }

    private fun requireOwnCity(gi: GameInfo, actor: Civilization, target: String?): City {
        val id = if (target != null && target.startsWith("city-")) target.substring(5) else null
            ?: throw Fail("BAD_PARAMS", "target 必须是 city-<id>")
        val mine = ArrayList<String>()
        for (c in actor.cities) {
            mine.add("city-" + c.id)
            if (c.id == id) return c
        }
        throw Fail("NOT_YOUR_CITY", "你没有 id 为 $id 的城市", mine)
    }

    @JvmStatic
    fun idOf(target: String?, prefix: String): Int? {
        if (target == null || !target.startsWith("$prefix-")) return null
        return target.substring(prefix.length + 1).toIntOrNull()
    }

    // ---------- 输出 ----------

    @JvmStatic
    fun q(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    private fun error(code: String, message: String, candidates: List<String>?): String {
        val sb = StringBuilder("""{"ok":false,"error":{"code":""")
            .append(q(code)).append(""","message":""").append(q(message))
        if (candidates != null) {
            sb.append(""","candidates":[""")
            for (i in candidates.indices) {
                if (i > 0) sb.append(',')
                sb.append(q(candidates[i]))
            }
            sb.append(']')
        }
        return sb.append("}}").toString()
    }

    // ---------- MCP ----------

    @JvmStatic
    fun handleMcp(body: String, gameInfo: GameInfo?, ref: UncivGameRef, actor: Civilization?): String {
        val req: MutableMap<String, Any?>
        try {
            req = obj(parse(body))
        } catch (e: Exception) {
            return """{"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"Parse error"}}"""
        }
        val idObj = req["id"]
        val idJson = if (idObj == null) "null"
            else if (idObj is String) q(idObj)
            else (idObj as Double).toInt().toString()
        val method = str(req, "method")

        if (method == "initialize") {
            return """{"jsonrpc":"2.0","id":$idJson,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"unciv-agent","version":"0.1.0"}}}"""
        }
        if (method == "notifications/initialized") {
            return "" // 通知不需要响应
        }
        if (method == "ping") {
            return """{"jsonrpc":"2.0","id":$idJson,"result":{}}"""
        }
        if (method == "tools/list") {
            val sb = StringBuilder("""{"jsonrpc":"2.0","id":$idJson,"result":{"tools":[""")
            var first = true
            for (t in TOOLS) {
                if (!first) sb.append(',')
                first = false
                sb.append("""{"name":""").append(q(t.name))
                    .append(""","description":""").append(q(t.description))
                    .append(""","inputSchema":""").append(t.schema).append('}')
            }
            return sb.append("]}}").toString()
        }
        if (method == "tools/call" && gameInfo == null) {
            return """{"jsonrpc":"2.0","id":$idJson,"error":{"code":-32000,"message":"no game loaded"}}"""
        }
        if (method == "tools/call") {
            val params = obj(req["params"])
            val name = str(params, "name")
            val args = obj(params["arguments"])
            if (name == null) {
                return """{"jsonrpc":"2.0","id":$idJson,"error":{"code":-32602,"message":"缺少 name"}}"""
            }
            val payload = dispatchTool(name, args, gameInfo!!, ref, actor!!)
            val isError = payload.contains("\"ok\":false") || payload.contains("\"error\"")
            return """{"jsonrpc":"2.0","id":$idJson,"result":{"content":[{"type":"text","text":${q(payload)}}],"isError":$isError}}"""
        }
        return """{"jsonrpc":"2.0","id":$idJson,"error":{"code":-32601,"message":"Method not found"}}"""
    }

    /** MCP 的 arguments 是扁平对象，翻译成 Intent 再走同一个内核 */
    private fun dispatchTool(name: String, args: MutableMap<String, Any?>, gameInfo: GameInfo, ref: UncivGameRef, actor: Civilization): String {
        when (name) {
            "get_state" -> return Agent.stateJson(gameInfo, actor)
            "get_units" -> return Agent.unitsJson(gameInfo, actor)
            "get_cities" -> return Agent.citiesJson(gameInfo, actor)
            "get_city_details" -> return Agent.cityDetailsJson(gameInfo, actor, str(args, "city"))
            "get_victory_progress" -> return Agent.victoryJson(gameInfo, actor)
            "get_tile" -> return Agent.tileJson(gameInfo, actor, integer(args, "x"), integer(args, "y"))
            "get_diplomacy" -> return Agent.diplomacyJson(gameInfo, actor)
            "get_policies" -> return Agent.policiesJson(gameInfo, actor)
            "get_map" -> return Agent.mapJson(gameInfo, actor,
                integer(args, "cx"), integer(args, "cy"), integer(args, "radius"))
            "get_events" -> return Agent.eventsJson(gameInfo, actor, integer(args, "limit"))
            "get_trade_offers" -> return Agent.tradeOffersJson(gameInfo, actor, str(args, "civ"))
            "get_guide" -> return Agent.guideJson(str(args, "name"), str(args, "category"))
            "get_targets" -> return Agent.targetsJson(gameInfo, actor, str(args, "unit"))
            "preview_move" -> return Agent.previewMoveJson(gameInfo, actor, str(args, "unit"),
                integer(args, "x"), integer(args, "y"))
            "get_reachable" -> return Agent.reachableJson(gameInfo, actor, str(args, "unit"))
            "get_stats" -> return Agent.statsJson(gameInfo, actor)
            "get_resources" -> return Agent.resourcesJson(gameInfo, actor)
            "preview_attack" -> return Agent.previewAttackJson(gameInfo, actor,
                str(args, "unit"), str(args, "city"), integer(args, "x"), integer(args, "y"))
            "get_briefing" -> return Agent.briefingJson(gameInfo, actor)
            "get_techs" -> return Agent.techsJson(gameInfo, actor)
            "get_religion" -> return Agent.religionJson(gameInfo, actor)
            "get_city_states" -> return Agent.cityStatesJson(gameInfo, actor)
            "get_spies" -> return Agent.spiesJson(gameInfo, actor)
            "declare_war", "make_peace", "adopt_policy", "set_city_focus", "purchase_construction",
            "attack", "bombard", "promote_unit", "unit_action", "move_unit", "found_city",
            "set_city_production", "set_research", "propose_trade", "respond_trade",
            "choose_beliefs", "gift_city_state", "pledge_protect", "set_worked_tiles", "nuke",
            "bombard_city", "upgrade_unit", "spy_move", "set_city_specialists", "annex_city",
            "capture_city", "end_turn" -> {
                val sb = StringBuilder("""{"type":${q(name)}""")
                val rest = LinkedHashMap<String, Any?>(args)
                var targetKey: String? = null
                if (rest.containsKey("unit")) targetKey = "unit"
                else if (rest.containsKey("city")) targetKey = "city"
                else if (rest.containsKey("civ")) targetKey = "civ"
                else if (rest.containsKey("policy")) targetKey = "policy"
                if (targetKey != null) {
                    val tv = rest.remove(targetKey)
                    sb.append(""","target":${if (tv is String) q(tv) else "null"}""")
                }
                sb.append(""","params":${write(rest)}}""")
                return handleIntent(sb.toString(), gameInfo, ref, actor)
            }
            else -> return """{"ok":false,"error":{"code":"UNKNOWN_TOOL","message":${q("没有这个工具: $name")}}}"""
        }
    }

}
