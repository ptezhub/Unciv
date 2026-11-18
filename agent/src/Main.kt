package uncivagent

import com.badlogic.gdx.files.FileHandle
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.files.FileConversions
import com.unciv.logic.map.HexCoord
import com.unciv.models.UnitActionType
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import com.unciv.view.GameView
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder

/**
 * Unciv 无头 agent 接口。
 *
 * 用法（工作目录必须是 android/assets，规则集从这里加载）：
 *   UNCIV_AGENT_SAVE=/abs/path/game.json UNCIV_AGENT_PORT=8099 \
 *     agent/build/install/agent/bin/agent
 *
 * 没有存档时启动空壳，等 POST /load。
 */
private lateinit var game: UncivGame
private var gameInfo: GameInfo? = null

fun main(args: Array<String>) {
    val port = System.getenv("UNCIV_AGENT_PORT")?.toIntOrNull() ?: 8099
    val savePath = System.getenv("UNCIV_AGENT_SAVE")

    boot(savePath)

    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.createContext("/") { ex -> handle(ex) }
    server.executor = null
    server.start()
    println("[unciv-agent] listening on 0.0.0.0:$port  cwd=${File(".").absolutePath}")
    if (gameInfo == null) println("[unciv-agent] 没有载入存档，等 POST /load")
}

private fun boot(savePath: String?) {
    game = UncivGame(true)
    UncivGame.Current = game
    game.settings = GameSettings()
    RulesetCache.loadRulesets(true)
    if (savePath != null) loadSave(savePath)
}

private fun loadSave(path: String) {
    val gi = FileConversions.readJson(FileHandle(File(path)), GameInfo::class.java)
        ?: error("读不出存档: $path")
    gi.setTransients()
    gameInfo = gi
    game.gameInfo = gi
    println("[unciv-agent] 载入存档 $path  回合 ${gi.turns}")
}

// ---------------- HTTP ----------------

private fun handle(ex: HttpExchange) {
    try {
        val path = ex.requestURI.path.trimEnd('/').ifEmpty { "/" }
        val method = ex.requestMethod.uppercase()
        when {
            path == "/health" -> send(ex, 200, """{"ok":true}""")
            path == "/state" -> send(ex, 200, stateJson())
            path == "/units" -> send(ex, 200, unitsJson())
            path == "/cities" -> send(ex, 200, citiesJson())
            path == "/actions" && method == "GET" -> {
                val id = query(ex, "unit")?.toIntOrNull()
                if (id == null) send(ex, 400, err("需要 ?unit=<id>")) else send(ex, 200, actionsJson(id))
            }
            path == "/act" && method == "POST" -> {
                val body = readBody(ex)
                val unitId = num(body, "unit")?.toInt()
                val type = str(body, "type")
                val actionId = str(body, "id")
                if (unitId == null || type == null) {
                    send(ex, 400, err("需要 unit 与 type"))
                } else {
                    send(ex, 200, actJson(unitId, type, actionId))
                }
            }
            path == "/move" && method == "POST" -> {
                val body = readBody(ex)
                val unitId = num(body, "unit")?.toInt()
                val x = num(body, "x")?.toInt()
                val y = num(body, "y")?.toInt()
                if (unitId == null || x == null || y == null) {
                    send(ex, 400, err("需要 unit、x、y"))
                } else {
                    send(ex, 200, moveJson(unitId, x, y))
                }
            }
            path == "/endturn" && method == "POST" -> send(ex, 200, endTurnJson())
            path == "/load" && method == "POST" -> {
                val body = readBody(ex)
                val p = str(body, "path")
                if (p == null) send(ex, 400, err("需要 path")) else {
                    loadSave(p)
                    send(ex, 200, stateJson())
                }
            }
            path == "/save" && method == "POST" -> {
                val body = readBody(ex)
                val p = str(body, "path")
                val gi = gameInfo
                if (p == null) send(ex, 400, err("需要 path")) else if (gi == null) {
                    send(ex, 409, err("没有载入游戏"))
                } else {
                    FileConversions.writeJson(FileHandle(File(p)), gi, false)
                    send(ex, 200, """{"ok":true,"path":${quote(p)}}""")
                }
            }
            else -> send(ex, 404, err("没有这个接口: $path"))
        }
    } catch (t: Throwable) {
        try {
            send(ex, 500, err(t.toString()))
        } catch (_: Throwable) {
        }
    }
}

private fun send(ex: HttpExchange, code: Int, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
    ex.sendResponseHeaders(code, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
}

private fun readBody(ex: HttpExchange): String = ex.requestBody.readBytes().toString(Charsets.UTF_8)

private fun query(ex: HttpExchange, key: String): String? {
    val q = ex.requestURI.rawQuery ?: return null
    for (pair in q.split("&")) {
        val i = pair.indexOf('=')
        if (i > 0 && pair.substring(0, i) == key) return URLDecoder.decode(pair.substring(i + 1), "UTF-8")
    }
    return null
}

// ---------------- 极简 JSON ----------------

private fun quote(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) when (c) {
        '"' -> sb.append("\\\"")
        '\\' -> sb.append("\\\\")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
    }
    return sb.append('"').toString()
}

private fun num(body: String, key: String): Double? =
    Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(-?[0-9.]+)").find(body)?.groupValues?.get(1)?.toDoubleOrNull()

private fun str(body: String, key: String): String? =
    Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)

private fun err(msg: String) = """{"error":${quote(msg)}}"""

// ---------------- 业务 ----------------

private fun needGame(): GameInfo = gameInfo ?: error("没有载入游戏，先 POST /load")

private fun civ() = needGame().currentPlayerCiv

private fun view(): GameView = GameView(needGame(), civ())

private fun stateJson(): String {
    val gi = needGame()
    val c = civ()
    val cities = c.cities.joinToString(",") { city ->
        val prod = city.cityConstructions.getCurrentConstruction().name
        """{"name":${quote(city.name)},"pop":${city.population.population},"x":${city.location.x},"y":${city.location.y},"production":${quote(prod)}}"""
    }
    val units = c.units.getCivUnits().joinToString(",") { u ->
        val p = u.getTile().position
        """{"id":${u.id},"name":${quote(u.name)},"x":${p.x},"y":${p.y},"hp":${u.health},"moves":${u.currentMovement}}"""
    }
    val techs = c.tech.techsResearched.joinToString(",") { quote(it) }
    return """{"turn":${gi.turns},"civ":${quote(c.civName)},"gold":${c.gold},"""
        .plus(""""researching":${quote(c.tech.currentTechnologyName() ?: "")},"techCount":${c.tech.techsResearched.size},""")
        .plus(""""cities":[$cities],"units":[$units],"techs":[$techs]}""")
}

private fun unitsJson(): String {
    val gv = view()
    val list = civ().units.getCivUnits().joinToString(",") { u ->
        val v = gv.getMapUnitView(u)
        val p = u.getTile().position
        """{"id":${v.id},"name":${quote(v.name)},"x":${p.x},"y":${p.y},"hp":${v.unitHealth},"""
            .plus(""""moves":${v.currentMovement},"maxMoves":${v.getMaxMovement()},""")
            .plus(""""due":${v.due},"canAttack":${v.canAttack()}}""")
    }
    return """{"units":[$list]}"""
}

private fun citiesJson(): String {
    val list = civ().cities.joinToString(",") { city ->
        val prod = city.cityConstructions.getCurrentConstruction().name
        """{"name":${quote(city.name)},"pop":${city.population.population},"""
            .plus(""""x":${city.location.x},"y":${city.location.y},"production":${quote(prod)}}""")
    }
    return """{"cities":[$list]}"""
}

private fun findUnit(id: Int) = civ().units.getCivUnits().firstOrNull { it.id == id }

private fun actionsJson(unitId: Int): String {
    val unit = findUnit(unitId) ?: return err("没有这个单位: $unitId")
    val v = view().getMapUnitView(unit)
    val list = v.getActions().joinToString(",") { a ->
        """{"type":${quote(a.type.name)},"id":${a.id?.let { quote(it) } ?: "null"},"available":${a.available}}"""
    }
    return """{"unit":$unitId,"actions":[$list]}"""
}

private fun actJson(unitId: Int, typeName: String, actionId: String?): String {
    val unit = findUnit(unitId) ?: return err("没有这个单位: $unitId")
    val type = try {
        UnitActionType.valueOf(typeName)
    } catch (e: IllegalArgumentException) {
        return err("未知动作类型: $typeName")
    }
    val v = view().getMapUnitView(unit)
    val ok = v.tryInvokeUnitAction(type, actionId)
    return """{"ok":$ok,"unit":$unitId,"type":${quote(typeName)},"turn":${needGame().turns}}"""
}

private fun moveJson(unitId: Int, x: Int, y: Int): String {
    val gi = needGame()
    val unit = findUnit(unitId) ?: return err("没有这个单位: $unitId")
    val tile = gi.tileMap[HexCoord(x, y)] ?: return err("没有这个格子: $x,$y")
    unit.movement.moveToTile(tile)
    val p = unit.getTile().position
    return """{"ok":true,"unit":$unitId,"x":${p.x},"y":${p.y},"moves":${unit.currentMovement}}"""
}

private fun endTurnJson(): String {
    val gi = needGame()
    val before = gi.turns
    gi.nextTurn()
    return """{"ok":true,"turnBefore":$before,"turn":${gi.turns},"civ":${quote(gi.currentPlayerCiv.civName)}}"""
}
