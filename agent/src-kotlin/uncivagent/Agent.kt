@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "UNCHECKED_CAST")

package uncivagent

import com.badlogic.gdx.files.FileHandle
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Notification
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.FileConversions
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActions
import com.unciv.logic.map.mapunit.movement.PathsToTilesWithinTurn
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.RulesetCache
import com.unciv.view.GameView
import com.unciv.view.MapUnitView

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.ArrayList
import java.util.Base64
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.regex.Pattern

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
object Agent {

    private lateinit var game: UncivGame
    private var gameInfo: GameInfo? = null

    /** token -> 文明名 */
    private val tokenToCiv = LinkedHashMap<String, String>()

    /** 本回合已提交的文明名 */
    private val submitted = LinkedHashSet<String>()

    /** 官方客户端文件目录 */
    private lateinit var fileRoot: File

    @JvmStatic
    fun main(args: Array<String>) {
        val port = env("UNCIV_AGENT_PORT", "8099").toInt()
        val save = System.getenv("UNCIV_AGENT_SAVE")
        val root = env("UNCIV_AGENT_FILES", "/home/node/.openclaw/state/unciv-host/files")
        fileRoot = File(root)
        fileRoot.mkdirs()

        boot()
        if (save != null) loadSave(save)

        val server = HttpServer.create(InetSocketAddress(port), 0)
        server.createContext("/") { ex -> handle(ex) }
        server.setExecutor(null)
        server.start()
        System.out.println("[unciv-agent] listening on 0.0.0.0:" + port +
            " cwd=" + File(".").getAbsolutePath() +
            " files=" + fileRoot.getAbsolutePath())
        if (gameInfo == null) System.out.println("[unciv-agent] 无存档，等 POST /load 或 POST /new")
    }

    /** 回合结束时把没被 capture_city 处理的占城按傀儡处理 */
    private fun resolveStaleCaptures(gi: GameInfo, actor: Civilization) {
        val pending = ArrayList<com.unciv.logic.civilization.PopupAlert>()
        for (a in actor.popupAlerts)
            if (a.type == com.unciv.logic.civilization.AlertType.CityConquered) pending.add(a)
        for (a in pending) {
            var city: City? = null
            for (c in gi.getCities()) {
                if (c.id == a.value) city = c
            }
            try {
                if (city != null && city.civ !== actor) city.puppetCity(actor)
            } catch (t: Throwable) {
                System.out.println("[unciv-agent] 自动处理占城失败: " + t)
            }
            actor.popupAlerts.remove(a)
        }
    }

    private fun env(k: String, d: String): String {
        val v = System.getenv(k)
        return if (v == null || v.isEmpty()) d else v
    }

    private fun boot() {
        game = UncivGame(true)
        UncivGame.Current = game
        game.settings = GameSettings()
        // 无头环境没有客户端那套 files 初始化，声音/存档辅助会碰 UncivGame.files。
        // 给一个真实的 UncivFiles 指向当前工作目录，找不到音频文件就静默跳过。
        try {
            if (com.badlogic.gdx.Gdx.files != null)
                game.files = com.unciv.logic.files.UncivFiles(com.badlogic.gdx.Gdx.files, null)
        } catch (t: Throwable) {
            System.out.println("[unciv-agent] files 初始化失败: " + t)
        }
        loadGuide()
        RulesetCache.loadRulesets(true, false)
        System.out.println("[unciv-agent] 规则集加载完成，共 " + RulesetCache.size + " 个")
    }

    private fun loadSave(path: String) {
        val gi = FileConversions.readJson(FileHandle(File(path)), GameInfo::class.java)
            ?: throw IllegalStateException("读不出存档: $path")
        gi.setTransients()
        gameInfo = gi
        game.gameInfo = gi
        submitted.clear()
        val sidecar = File("$path.submitted")
        if (sidecar.exists()) {
            try {
                for (line in Files.readAllLines(sidecar.toPath(), StandardCharsets.UTF_8)) {
                    if (line.trim().isNotEmpty()) submitted.add(line.trim())
                }
            } catch (ignored: IOException) { }
        }
        try {
            gi.currentPlayerCiv.updateStatsForNextTurn()
        } catch (ignored: Throwable) { }
        System.out.println("[unciv-agent] 载入存档 $path 回合 " + gi.turns)
    }

    // ---------------- 身份 ----------------

    private fun tokenOf(ex: HttpExchange): String? {
        val auth = ex.getRequestHeaders().getFirst("Authorization")
        if (auth != null && auth.startsWith("Bearer ")) return auth.substring(7).trim()
        val h = ex.getRequestHeaders().getFirst("X-Agent-Token")
        return h?.trim()
    }

    /** 解析出这次请求代表哪个文明；单人模式退回当前回合文明 */
    private fun actorFor(ex: HttpExchange): Civilization? {
        val gi = gameInfo ?: return null
        val token = tokenOf(ex)
        if (token != null) {
            val civName = tokenToCiv[token] ?: return null   // 未知 token，直接拒绝
            for (c in gi.civilizations) {
                if (c.civName == civName) return c
            }
            return null                                     // token 指向的文明不在本局
        }
        if (tokenToCiv.isNotEmpty()) return null            // 多文明模式必须带 token
        return gi.currentPlayerCiv
    }

    private fun humanCivs(gi: GameInfo): List<Civilization> {
        val out = ArrayList<Civilization>()
        for (c in gi.civilizations) {
            if (c.isHuman() && c.isAlive()) out.add(c)
        }
        return out
    }

    // ---------------- 回合推进 ----------------

    private val TURN = object : Kernel.UncivGameRef {
        override fun advanceTurn(gi: GameInfo, actor: Civilization) {
            // 有没处理的占城就按引擎默认的"傀儡"落地，避免城市卡在 0 血、旧主仍持有
            resolveStaleCaptures(gi, actor)
            val humans = humanCivs(gi)
            if (humans.size <= 1) {
                gi.nextTurn(null, false)
                return
            }
            submitted.add(actor.civName)
            for (h in humans) {
                if (!submitted.contains(h.civName)) return   // 还有人没交，等
            }
            // 都交了：一路推到下一个回合，让 AI 文明自己走完
            submitted.clear()
            val before = gi.turns
            var guard = 0
            do {
                gi.nextTurn(null, false)
                guard++
            } while (guard < 64 && (gi.turns == before || !gi.currentPlayerCiv.isHuman()))
        }
    }

    // ---------------- HTTP ----------------

    private fun handle(ex: HttpExchange) {
        try {
            var path = ex.getRequestURI().getPath()
            while (path.length > 1 && path.endsWith("/")) path = path.substring(0, path.length - 1)
            val method = ex.getRequestMethod().uppercase()
            Trace.begin(method, path, ex.getRequestURI().getRawQuery())

            // ---- 官方客户端协议 ----
            if (path == "/isalive") { send(ex, 200, """{"online":true}"""); return }
            if (path == "/auth") { handleAuth(ex, method); return }
            if (path.startsWith("/files/")) { handleFiles(ex, path.substring(7), method); return }

            // ---- 探活与管理 ----
            if (path == "/health") { send(ex, 200, """{"ok":true}"""); return }
            if (path == "/tools") { send(ex, 200, toolsJson()); return }
            if (path == "/guide") {
                send(ex, 200, guideJson(query(ex, "name"), query(ex, "category")))
                return
            }

            if (path == "/admin/civs") {
                val sb = StringBuilder("""{"turn":""").append(need().turns).append(""","civs":[""")
                var firstCiv = true
                for (c in need().civilizations) {
                    if (!firstCiv) sb.append(',')
                    firstCiv = false
                    var tok: String? = null
                    for (e in tokenToCiv.entries) {
                        if (e.value == c.civName) tok = e.key
                    }
                    sb.append("""{"civ":""").append(quote(c.civName))
                        .append(""","human":""").append(c.isHuman())
                        .append(""","alive":""").append(c.isAlive())
                        .append(""","submitted":""").append(submitted.contains(c.civName))
                        .append(""","hasToken":""").append(tok != null).append('}')
                }
                send(ex, 200, sb.append("]}").toString())
                return
            }

            if (path == "/admin/token" && method == "POST") {
                val body = obj(parse(readBody(ex)))
                val civ = str(body, "civ")
                if (civ == null) { send(ex, 400, """{"error":"需要 civ"}"""); return }
                var exists = false
                for (c in need().civilizations) {
                    if (c.civName == civ) exists = true
                }
                if (!exists) {
                    val names = StringBuilder()
                    for (c in need().civilizations) {
                        if (names.length > 0) names.append(',')
                        names.append(quote(c.civName))
                    }
                    send(ex, 404, """{"error":{"code":"UNKNOWN_ID","message":""" +
                        quote("本局没有这个文明: $civ") + ""","candidates":[$names]}}""")
                    return
                }
                var token = str(body, "token")
                if (token == null) token = java.util.UUID.randomUUID().toString()
                tokenToCiv[token] = civ
                send(ex, 200, """{"ok":true,"civ":${Kernel.q(civ)},"token":${Kernel.q(token)}}""")
                return
            }

            // ---- Agent 接口 ----
            var actor = actorFor(ex)

            if (isAgentPath(path) && actor == null && tokenToCiv.isNotEmpty()) {
                send(ex, 401, """{"error":{"code":"UNAUTHORIZED","message":"多文明模式必须带有效 token"}}""")
                return
            }
            if (actor == null && isAgentPath(path)) {
                actor = need().currentPlayerCiv
            }

            if (path == "/intent" && method == "POST") {
                send(ex, 200, Kernel.handleIntent(readBody(ex), need(), TURN, actor!!))
                return
            }
            if (path == "/mcp" && method == "POST") {
                val out = Kernel.handleMcp(readBody(ex), gameInfo, TURN, actor)
                if (out.isEmpty()) { ex.sendResponseHeaders(202, -1); ex.close(); return }
                send(ex, 200, out)
                return
            }

            if (path == "/state") { send(ex, 200, stateJson(need(), actor!!)); return }
            if (path == "/units") { send(ex, 200, unitsJson(need(), actor!!)); return }
            if (path == "/cities") { send(ex, 200, citiesJson(need(), actor!!)); return }
            if (path == "/map") {
                send(ex, 200, mapJson(need(), actor!!, intq(ex, "cx"), intq(ex, "cy"), intq(ex, "radius")))
                return
            }
            if (path == "/events") { send(ex, 200, eventsJson(need(), actor!!, intq(ex, "limit"))); return }
            if (path == "/diplomacy") { send(ex, 200, diplomacyJson(need(), actor!!)); return }
            if (path == "/policies") { send(ex, 200, policiesJson(need(), actor!!)); return }
            if (path == "/trade") { send(ex, 200, tradeOffersJson(need(), actor!!, query(ex, "civ"))); return }
            if (path == "/religion") { send(ex, 200, religionJson(need(), actor!!)); return }
            if (path == "/techs") { send(ex, 200, techsJson(need(), actor!!)); return }
            if (path == "/briefing") { send(ex, 200, briefingJson(need(), actor!!)); return }
            if (path == "/stats") { send(ex, 200, statsJson(need(), actor!!)); return }
            if (path == "/resources") { send(ex, 200, resourcesJson(need(), actor!!)); return }
            if (path == "/preview_move") {
                send(ex, 200, previewMoveJson(need(), actor!!, query(ex, "unit"),
                    intq(ex, "x"), intq(ex, "y")))
                return
            }
            if (path == "/reachable") {
                send(ex, 200, reachableJson(need(), actor!!, query(ex, "unit")))
                return
            }
            if (path == "/targets") {
                send(ex, 200, targetsJson(need(), actor!!, query(ex, "unit")))
                return
            }
            if (path == "/preview_attack") {
                send(ex, 200, previewAttackJson(need(), actor!!, query(ex, "unit"), query(ex, "city"),
                    intq(ex, "x"), intq(ex, "y")))
                return
            }
            if (path == "/victory") { send(ex, 200, victoryJson(need(), actor!!)); return }
            if (path == "/citystates") { send(ex, 200, cityStatesJson(need(), actor!!)); return }
            if (path == "/spies") { send(ex, 200, spiesJson(need(), actor!!)); return }
            if (path == "/tile") {
                send(ex, 200, tileJson(need(), actor!!, intq(ex, "x"), intq(ex, "y")))
                return
            }
            if (path.startsWith("/city/")) {
                send(ex, 200, cityDetailsJson(need(), actor!!, path.substring(6)))
                return
            }

            if (path == "/actions" && method == "GET") {
                val u = intq(ex, "unit")
                if (u == null) { send(ex, 400, err("需要 ?unit=<id>")); return }
                send(ex, 200, actionsJson(need(), actor!!, u))
                return
            }

            // 兼容旧接口（内部转成 Intent）
            if (path == "/act" && method == "POST") {
                val b = obj(parse(readBody(ex)))
                val type = str(b, "type")
                val unit = integer(b, "unit")
                if (type == null) { send(ex, 400, err("需要 type")); return }
                val id = str(b, "id")
                val params = if (id == null) "{}" else """{"id":${Kernel.q(id)}}"""
                val intent = """{"type":${Kernel.q(normalizeAction(type))},"target":""" +
                    (if (unit == null) "null" else Kernel.q("unit-$unit")) +
                    ""","params":$params}"""
                send(ex, 200, Kernel.handleIntent(intent, need(), TURN, actor!!))
                return
            }

            if (path == "/move" && method == "POST") {
                val b = obj(parse(readBody(ex)))
                val unit = integer(b, "unit")
                val x = integer(b, "x")
                val y = integer(b, "y")
                if (unit == null || x == null || y == null) { send(ex, 400, err("需要 unit、x、y")); return }
                val intent = """{"type":"move_unit","target":${Kernel.q("unit-$unit")},"params":{"x":$x,"y":$y}}"""
                send(ex, 200, Kernel.handleIntent(intent, need(), TURN, actor!!))
                return
            }

            if (path == "/endturn" && method == "POST") {
                send(ex, 200, Kernel.handleIntent("""{"type":"end_turn","params":{}}""", need(), TURN, actor!!))
                return
            }

            // ---- 存档管理 ----
            if (path == "/new" && method == "POST") {
                val b = obj(parse(readBody(ex)))
                val players = integer(b, "players")
                val seed = integer(b, "seed")
                val humans = integer(b, "humans")
                send(ex, 200, newGameJson(players ?: 2,
                    humans ?: 1,
                    str(b, "difficulty"), str(b, "speed"),
                    str(b, "civ"), seed?.toLong() ?: 0L,
                    str(b, "era"), str(b, "size")))
                return
            }
            if (path == "/load" && method == "POST") {
                val b = obj(parse(readBody(ex)))
                val p = str(b, "path")
                if (p == null) { send(ex, 400, err("需要 path")); return }
                if (!File(p).exists()) {
                    send(ex, 404, """{"error":{"code":"NOT_FOUND","message":""" + quote("存档不存在: $p") + "}}")
                    return
                }
                try {
                    loadSave(p)
                } catch (t: Throwable) {
                    send(ex, 400, """{"error":{"code":"BAD_SAVE","message":""" + quote("读不出这个存档: " + t.message) + "}}")
                    return
                }
                send(ex, 200, stateJson(need(), need().currentPlayerCiv))
                return
            }
            if (path == "/save" && method == "POST") {
                val b = obj(parse(readBody(ex)))
                val p = str(b, "path")
                if (p == null) { send(ex, 400, err("需要 path")); return }
                FileConversions.writeJson(FileHandle(File(p)), need(), false)
                try {
                    Files.write(File("$p.submitted").toPath(),
                        submitted.joinToString("\n").toByteArray(StandardCharsets.UTF_8))
                } catch (ignored: IOException) { }
                send(ex, 200, """{"ok":true,"path":${Kernel.q(p)}}""")
                return
            }

            send(ex, 404, err("没有这个接口: $path"))
        } catch (t: Throwable) {
            t.printStackTrace()
            val tb = StringBuilder(t.toString())
            val st = t.getStackTrace()
            for (i in 0 until Math.min(3, st.size)) tb.append(" | ").append(st[i])
            try { send(ex, 500, err(tb.toString())) } catch (ignored: Throwable) { }
        }
    }

    private fun isAgentPath(path: String): Boolean {
        return path == "/intent" || path == "/state" || path == "/units" ||
            path == "/cities" || path == "/map" || path == "/events" ||
            path == "/diplomacy" || path == "/policies" || path == "/trade" ||
            path == "/religion" || path == "/citystates" || path == "/spies" ||
            path == "/techs" || path == "/victory" || path == "/briefing" ||
            path == "/preview_attack" || path == "/targets" ||
            path == "/preview_move" || path == "/reachable" ||
            path == "/stats" || path == "/resources" ||
            path == "/tile" ||
            path.startsWith("/city/") || path == "/actions" ||
            path == "/act" || path == "/move" || path == "/endturn"
    }

    /** 旧的 /act 用驼峰名，Intent 用小写，这里做一次映射 */
    private fun normalizeAction(type: String): String {
        return when (type) {
            "FoundCity" -> "found_city"
            "Sleep", "Fortify", "Explore", "Automate", "Pillage", "SetUp", "Disband" -> "unit_action"
            else -> type
        }
    }

    // ---- 官方客户端文件协议 ----

    private fun handleAuth(ex: HttpExchange, method: String) {
        val auth = ex.getRequestHeaders().getFirst("Authorization")
        if (auth == null || !auth.startsWith("Basic ")) {
            ex.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"unciv\"")
            try { send(ex, 401, """{"error":"需要 Basic 认证"}""") } catch (ignored: IOException) { }
            return
        }
        try {
            val pair = String(Base64.getDecoder().decode(auth.substring(6)), StandardCharsets.UTF_8)
            val i = pair.indexOf(':')
            val user = if (i > 0) pair.substring(0, i) else pair
            send(ex, 200, """{"ok":true,"user":${Kernel.q(user)}}""")
        } catch (e: Exception) {
            try { send(ex, 400, err("认证头无法解析")) } catch (ignored: IOException) { }
        }
    }

    private fun handleFiles(ex: HttpExchange, name: String, method: String) {
        if (name.isEmpty() || name.contains("/") || name.contains("..")) {
            send(ex, 400, err("非法文件名"))
            return
        }
        val f = File(fileRoot, name)
        when (method) {
            "GET" -> {
                if (!f.exists()) { send(ex, 404, err("没有这个文件")); return }
                val data = Files.readAllBytes(f.toPath())
                ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8")
                ex.sendResponseHeaders(200, data.size.toLong())
                ex.getResponseBody().use { os -> os.write(data) }
                return
            }
            "PUT" -> {
                val data = ex.getRequestBody().readAllBytes()
                if (data.size > 32 * 1024 * 1024) { send(ex, 413, err("文件太大")); return }
                Files.write(f.toPath(), data)
                send(ex, 200, """{"ok":true,"name":${Kernel.q(name)},"bytes":${data.size}}""")
                return
            }
            "DELETE" -> {
                if (!f.exists()) { send(ex, 404, err("没有这个文件")); return }
                f.delete()
                send(ex, 200, """{"ok":true}""")
                return
            }
            else -> send(ex, 405, err("不支持的方法"))
        }
    }

    // ---------------- 工具与输出 ----------------

    private fun toolsJson(): String {
        val sb = StringBuilder("""{"tools":[""")
        var first = true
        for (t in Kernel.tools()) {
            if (!first) sb.append(',')
            first = false
            sb.append("""{"name":""").append(Kernel.q(t.name))
                .append(""","kind":""").append(Kernel.q(t.kind))
                .append(""","description":""").append(Kernel.q(t.description))
                .append(""","schema":""").append(t.schema).append('}')
        }
        return sb.append("]}").toString()
    }

    /** 逐请求轨迹：UNCIV_AGENT_TRACE=<file> 打开，一行一个 JSON，够复盘整局。
     *  记的是接口层，不依赖模型自己写报告。 */
    private object Trace {
        @JvmField val PATH: String? = System.getenv("UNCIV_AGENT_TRACE")
        @JvmField val ON: Boolean = PATH != null && !PATH.isEmpty()
        private val CUR = ThreadLocal<StringBuilder>()
        private val T0 = ThreadLocal<Long>()

        fun begin(method: String, path: String, query: String?) {
            if (!ON) return
            T0.set(System.currentTimeMillis())
            val sb = StringBuilder("""{"ts":""").append(System.currentTimeMillis())
                .append(""","method":""").append(quote(method))
                .append(""","path":""").append(quote(path))
            if (query != null && query.isNotEmpty()) sb.append(""","query":""").append(quote(query))
            CUR.set(sb)
        }

        fun body(body: String?) {
            if (!ON) return
            val sb = CUR.get()
            if (sb == null || body == null || body.isEmpty()) return
            sb.append(""","body":""").append(quote(if (body.length > 4000) body.substring(0, 4000) else body))
        }

        fun end(status: Int, response: String?) {
            if (!ON) return
            val sb = CUR.get()
            if (sb == null) return
            CUR.remove()
            val t0 = T0.get()
            T0.remove()
            var turn = -1
            try { turn = if (gameInfo == null) -1 else gameInfo!!.turns } catch (ignored: Throwable) { }
            sb.append(""","turn":""").append(if (turn < 0) "null" else turn.toString())
                .append(""","status":""").append(status)
                .append(""","bytes":""").append(if (response == null) 0
                    else response.toByteArray(StandardCharsets.UTF_8).size)
            if (t0 != null) sb.append(""","ms":""").append(System.currentTimeMillis() - t0)
            sb.append("}\n")
            synchronized(Trace::class.java) {
                try {
                    java.io.FileWriter(PATH!!, true).use { fw -> fw.write(sb.toString()) }
                } catch (ignored: Throwable) { }
            }
        }
    }

    private fun send(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.getResponseBody().use { os -> os.write(bytes) }
        Trace.end(code, body)
    }

    private fun readBody(ex: HttpExchange): String {
        val body = String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
        Trace.body(body)
        return body
    }

    private fun query(ex: HttpExchange, key: String): String? {
        val q = ex.getRequestURI().getRawQuery() ?: return null
        for (pair in q.split("&")) {
            val i = pair.indexOf('=')
            if (i > 0 && pair.substring(0, i) == key)
                return URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8)
        }
        return null
    }

    private fun intq(ex: HttpExchange, key: String): Int? {
        val v = query(ex, key)
        if (v == null || v.trim().isEmpty()) return null
        return v.trim().toIntOrNull()   // 参数写错不该是 500，交给下游的默认值/校验
    }

    private fun quote(s: String): String = Kernel.q(s)

    private fun err(msg: String): String = """{"error":${quote(msg)}}"""

    private fun need(): GameInfo {
        return gameInfo ?: throw IllegalStateException("没有载入游戏，先 POST /load 或 /new")
    }

    // ---------------- 读投影 ----------------

    fun stateJson(gi: GameInfo, c: Civilization): String {
        val cities = StringBuilder()
        for (city in c.cities) {
            if (cities.length > 0) cities.append(',')
            cities.append("""{"id":""").append(quote("city-" + city.id))
                .append(""","name":""").append(quote(city.name))
                .append(""","pop":""").append(city.population.population)
                .append(""","x":""").append(city.location.x)
                .append(""","y":""").append(city.location.y)
                .append(""","production":""").append(productionJson(city))
                .append('}')
        }
        val units = StringBuilder()
        for (u in c.units.getCivUnits()) {
            if (units.length > 0) units.append(',')
            val p = u.getTile().position
            units.append("""{"id":""").append(quote("unit-" + u.id))
                .append(""","name":""").append(quote(u.name))
                .append(""","x":""").append(p.x)
                .append(""","y":""").append(p.y)
                .append(""","hp":""").append(u.health)
                .append(""","moves":""").append(u.currentMovement).append('}')
        }
        val pending = StringBuilder()
        for (alert in c.popupAlerts) {
            if (alert.type != com.unciv.logic.civilization.AlertType.CityConquered) continue
            var cname: String? = null
            for (cc in gi.getCities()) {
                if (cc.id == alert.value) cname = cc.name
            }
            if (pending.length > 0) pending.append(',')
            pending.append("""{"city":""").append(quote("city-" + alert.value))
                .append(""","name":""").append(cname?.let { quote(it) } ?: "null").append('}')
        }
        val researching = c.tech.currentTechnologyName()
        var mapSeed = 0L
        try {
            mapSeed = gi.tileMap.mapParameters.seed
        } catch (ignored: Throwable) { }
        var difficulty: String? = null
        var speed: String? = null
        try {
            difficulty = gi.gameParameters.difficulty
            speed = gi.gameParameters.speed
        } catch (ignored: Throwable) { }
        return """{"turn":${gi.turns}""" +
            ""","difficulty":${difficulty?.let { quote(it) } ?: "null"}""" +
            ""","speed":${speed?.let { quote(it) } ?: "null"}""" +
            ""","seed":$mapSeed""" +
            ""","gameId":${quote(gi.gameId)}""" +
            ""","civ":${quote(c.civName)}""" +
            ""","alive":${c.isAlive()}""" +
            ""","defeated":${c.isDefeated()}""" +
            ""","gold":${c.gold}""" +
            ""","researching":${quote(researching ?: "")}""" +
            ""","techCount":${c.tech.techsResearched.size}""" +
            ""","stats":{${civStatsJson(c)}}""" +
            ""","cities":[$cities]""" +
            ""","units":[$units]""" +
            ""","pendingCaptures":[$pending]}"""
    }

    fun unitsJson(gi: GameInfo, c: Civilization): String {
        val gv = GameView(gi, c, false)
        val sb = StringBuilder("""{"units":[""")
        var first = true
        for (u in c.units.getCivUnits()) {
            val v = gv.getMapUnitView(u)
            if (!first) sb.append(',')
            first = false
            val p = u.getTile().position
            val promos = StringBuilder()
            for (promo in u.promotions.promotions) {
                if (promos.length > 0) promos.append(',')
                promos.append(quote(promo))
            }
            val ups = StringBuilder()
            try {
                for (pu in u.upgrade.getUpgradesOfKind(false)) {
                    if (!pu.isShownToPlayer) continue
                    if (ups.length > 0) ups.append(',')
                    ups.append(quote(pu.upgradedUnit.name))
                }
            } catch (ignored: Throwable) { }
            sb.append("""{"id":""").append(quote("unit-" + v.id))
                .append(""","name":""").append(quote(v.name))
                .append(""","x":""").append(p.x)
                .append(""","y":""").append(p.y)
                .append(""","hp":""").append(v.unitHealth)
                .append(""","moves":""").append(v.currentMovement)
                .append(""","maxMoves":""").append(v.getMaxMovement())
                .append(""","canAttack":""").append(v.canAttack())
                .append(""","military":""").append(u.isMilitary())
                .append(""","civilian":""").append(u.isCivilian())
                .append(""","embarked":""").append(u.isEmbarked())
                .append(""","range":""").append(u.getRange())
                .append(""","xp":""").append(u.promotions.XP)
                .append(""","canPromote":""").append(u.promotions.canBePromoted())
                .append(""","availablePromotions":""").append(availablePromotions(u))
                .append(""","promotions":[""").append(promos).append(']')
                .append(""","canUpgradeTo":[""").append(ups).append(']')
                .append('}')
        }
        return sb.append("]}").toString()
    }

    fun citiesJson(gi: GameInfo, c: Civilization): String {
        val sb = StringBuilder("""{"cities":[""")
        var first = true
        for (city in c.cities) {
            if (!first) sb.append(',')
            first = false
            sb.append("""{"id":""").append(quote("city-" + city.id))
                .append(""","name":""").append(quote(city.name))
                .append(""","pop":""").append(city.population.population)
                .append(""","x":""").append(city.location.x)
                .append(""","y":""").append(city.location.y)
                .append(""","production":""").append(productionJson(city))
                .append('}')
        }
        return sb.append("]}").toString()
    }

    fun cityDetailsJson(gi: GameInfo, c: Civilization, cityId: String?): String {
        var target: City? = null
        val cand = StringBuilder()
        for (city in c.cities) {
            if (cand.length > 0) cand.append(',')
            cand.append("""{"id":""").append(quote("city-" + city.id))
                .append(""","name":""").append(quote(city.name)).append('}')
            if (("city-" + city.id) == cityId || city.id == cityId || city.name == cityId) target = city
        }
        if (target == null) {
            return """{"ok":false,"error":{"code":"UNKNOWN_ID","message":""" +
                quote("没有这个城市: $cityId") + ""","candidates":[$cand]}}"""
        }

        val rs = gi.ruleset
        val pool = ArrayList<String>()
        pool.addAll(rs.buildings.keys)
        pool.addAll(rs.units.keys)
        val buildable = StringBuilder()
        var shown = 0
        for (name in pool) {
            if (shown >= 40) break
            try {
                val con = target.cityConstructions.getConstruction(name)
                if (con != null && target.cityConstructions.canAddToQueue(con,
                        target.cityConstructions.constructionQueue)) {
                    if (shown > 0) buildable.append(',')
                    buildable.append(quote(name))
                    shown++
                }
            } catch (ignored: Throwable) { }
        }
        val queue = StringBuilder()
        for (q in target.cityConstructions.constructionQueue) {
            if (queue.length > 0) queue.append(',')
            queue.append(quote(q))
        }
        val stats = target.cityStats.currentCityStats
        val maxHealth = target.getMaxHealth()
        return """{"city":${quote("city-" + target.id)}""" +
            ""","name":${quote(target.name)}""" +
            ""","pop":${target.population.population}""" +
            ""","at":[${target.location.x},${target.location.y}]""" +
            ""","focus":${quote(target.getCityFocus().name)}""" +
            ""","production":${productionJson(target)}""" +
            ""","queue":[$queue]""" +
            ""","workedTiles":${workedTilesJson(target)}""" +
            ""","stats":{"food":${round1(stats.food)}""" +
            ""","production":${round1(stats.production)}""" +
            ""","gold":${round1(stats.gold)}""" +
            ""","science":${round1(stats.science)}""" +
            ""","culture":${round1(stats.culture)}""" +
            ""","faith":${round1(stats.faith)}""" +
            ""","happiness":${round1(stats.happiness)}}""" +
            ""","health":${target.health}""" +
            ""","maxHealth":$maxHealth""" +
            ""","isPuppet":${target.isPuppet}""" +
            ""","isCapital":${target.isCapital()}""" +
            ""","connected":${target.isConnectedToCapital()}""" +
            ""","avoidGrowth":${target.avoidGrowth}""" +
            ""","inResistance":${target.isInResistance()}""" +
            ""","buildings":${buildingsJson(gi, target)}""" +
            ""","specialists":${counterJson(target.population.specialistAllocations)}""" +
            ""","maxSpecialists":${counterJson(target.population.getMaxSpecialists())}""" +
            ""","manualSpecialists":${target.manualSpecialists}""" +
            ""","turnsToGrowth":${if (target.population.getNumTurnsToNewPopulation() == null) "null" else target.population.getNumTurnsToNewPopulation()}""" +
            ""","foodToNextPop":${target.population.getFoodToNextPopulation()}""" +
            ""","religion":{"majority":${if (target.religion.getMajorityReligionName() == null) "null" else quote(target.religion.getMajorityReligionName()!!)}""" +
            ""","holyCityOf":${if (target.religion.religionThisIsTheHolyCityOf == null) "null" else quote(target.religion.religionThisIsTheHolyCityOf!!)}}""" +
            ""","turnsToBuild":${turnsToBuild(target)}""" +
            ""","remainingWork":${remainingWork(target)}""" +
            ""","defense":${cityDefense(target)}""" +
            ""","garrison":${if (target.getGarrison() == null) "null" else quote(target.getGarrison()!!.name)}""" +
            ""","canBombard":${target.canBombard()}""" +
            ""","purchasable":${purchasableJson(gi, target)}""" +
            ""","buildable":[$buildable]}"""
    }

    private fun tileYields(t: Tile, c: Civilization): String {
        try {
            val st = com.unciv.logic.map.tile.TileStatFunctions(t).getTileStats(c)
            return """{"food":${round1(st.food)}""" +
                ""","production":${round1(st.production)}""" +
                ""","gold":${round1(st.gold)}""" +
                ""","science":${round1(st.science)}""" +
                ""","culture":${round1(st.culture)}""" +
                ""","faith":${round1(st.faith)}}"""
        } catch (e: Throwable) {
            return "null"
        }
    }

    private fun civStatsJson(c: Civilization): String {
        val sb = StringBuilder()
        try {
            val st = c.stats.statsForNextTurn
            sb.append(""""goldPerTurn":""").append(round1(st.gold))
                .append(""","sciencePerTurn":""").append(round1(st.science))
                .append(""","culturePerTurn":""").append(round1(st.culture))
                .append(""","faithPerTurn":""").append(round1(st.faith))
                .append(""","productionPerTurn":""").append(round1(st.production))
                .append(""","foodPerTurn":""").append(round1(st.food))
            sb.append(""","happiness":""").append(c.stats.happiness)
            sb.append(""","unitSupply":""").append(c.stats.getUnitSupply())
        } catch (e: Throwable) {
            sb.append(""""error":""").append(quote(e.toString()))
        }
        return sb.toString()
    }

    private fun availablePromotions(u: MapUnit): String {
        val sb = StringBuilder("[")
        try {
            if (u.promotions.canBePromoted()) {
                var first = true
                for (p in u.promotions.getAvailablePromotions()) {
                    if (!first) sb.append(',')
                    first = false
                    sb.append(quote(p.name))
                }
            }
        } catch (ignored: Throwable) { }
        return sb.append(']').toString()
    }

    private fun round1(v: Float): String = (Math.round(v * 10f) / 10f).toString()

    private fun counterJson(c: com.unciv.models.Counter<String>): String {
        val sb = StringBuilder("{")
        var first = true
        for (e in c.entries) {
            if (e.value == null || e.value <= 0) continue
            if (!first) sb.append(',')
            first = false
            sb.append(quote(e.key)).append(':').append(e.value)
        }
        return sb.append('}').toString()
    }

    private fun buildingsJson(gi: GameInfo, city: City): String {
        val sb = StringBuilder("[")
        var first = true
        for (name in gi.ruleset.buildings.keys) {
            var built: Boolean
            try {
                built = city.cityConstructions.isBuilt(name)
            } catch (e: Throwable) {
                continue
            }
            if (!built) continue
            if (!first) sb.append(',')
            first = false
            sb.append(quote(name))
        }
        return sb.append(']').toString()
    }

    private fun remainingWork(city: City): String {
        try {
            val item = city.cityConstructions.getCurrentConstruction().name
            if (item == null || item.isEmpty() || item == "Nothing") return "null"
            return city.cityConstructions.getRemainingWork(item, false).toString()
        } catch (ignored: Throwable) {
            return "null"
        }
    }

    private fun cityDefense(city: City): String {
        try {
            return com.unciv.logic.battle.CityCombatant(city)
                .getCityStrength(null, com.unciv.logic.battle.CombatAction.Defend).toString()
        } catch (ignored: Throwable) {
            return "null"
        }
    }

    private fun turnsToBuild(city: City): String {
        try {
            val item = city.cityConstructions.getCurrentConstruction().name
            if (item == null || item.isEmpty() || item == "Nothing") return "null"
            val remaining = city.cityConstructions.getRemainingWork(item, false)
            val perTurn = city.cityStats.currentCityStats.production
            if (perTurn <= 0.01f) return "null"
            val turns = Math.ceil(remaining / perTurn.toDouble()).toInt()
            return Math.max(turns, 0).toString()
        } catch (ignored: Throwable) {
            return "null"
        }
    }

    /** 现在真的能买的项（含价格），省得 agent 反复试错 */
    private fun purchasableJson(gi: GameInfo, city: City): String {
        val sb = StringBuilder("[")
        var first = true
        try {
            val pool = ArrayList<String>()
            pool.addAll(gi.ruleset.buildings.keys)
            pool.addAll(gi.ruleset.units.keys)
            var shown = 0
            for (name in pool) {
                if (shown >= 20) break
                val con = city.cityConstructions.getConstruction(name)
                if (con !is com.unciv.models.ruleset.INonPerpetualConstruction) continue
                val gold = con.getStatBuyCost(city, com.unciv.models.stats.Stat.Gold)
                val faith = con.getStatBuyCost(city, com.unciv.models.stats.Stat.Faith)
                val goldOk = gold != null && city.cityConstructions
                    .isConstructionPurchaseAllowed(con, com.unciv.models.stats.Stat.Gold, gold)
                val faithOk = faith != null && city.cityConstructions
                    .isConstructionPurchaseAllowed(con, com.unciv.models.stats.Stat.Faith, faith)
                if (!goldOk && !faithOk) continue
                shown++
                if (!first) sb.append(',')
                first = false
                sb.append("""{"item":""").append(quote(name))
                if (goldOk) sb.append(""","gold":""").append(gold)
                if (faithOk) sb.append(""","faith":""").append(faith)
                sb.append('}')
            }
        } catch (ignored: Throwable) { }
        return sb.append(']').toString()
    }

    private fun workedTilesJson(city: City): String {
        val sb = StringBuilder("[")
        var first = true
        for (t in Kernel.workedTilesOf(city)) {
            val p = t.position
            if (!first) sb.append(',')
            first = false
            sb.append('[').append(p.x).append(',').append(p.y).append(']')
        }
        return sb.append(']').toString()
    }

    fun mapJson(gi: GameInfo, c: Civilization, cx: Int?, cy: Int?, radius: Int?): String {
        val r = if (radius == null) 3 else Math.max(0, Math.min(radius, 12))
        val centerX = cx ?: 0
        val centerY = cy ?: 0
        val sb = StringBuilder("""{"center":[""").append(centerX).append(',').append(centerY)
            .append("""],"radius":""").append(r).append(""","tiles":[""")
        var first = true
        for (dx in -r..r) {
            for (dy in -r..r) {
                val t = Kernel.tileAt(gi, centerX + dx, centerY + dy)
                if (t == null || !t.isExplored(c)) continue
                if (!first) sb.append(',')
                first = false
                sb.append("""{"x":""").append(t.position.x)
                    .append(""","y":""").append(t.position.y)
                    .append(""","terrain":""").append(quote(t.baseTerrain))
                    .append(""","visible":""").append(t.isVisible(c))
                val mf = StringBuilder()
                for (f in t.terrainFeatures) {
                    if (mf.length > 0) mf.append(',')
                    mf.append(quote(f))
                }
                sb.append(""","features":[""").append(mf).append(']')
                val res = t.resource
                if (res != null) sb.append(""","resource":""").append(quote(res))
                // 设施与路的归属：/map 少了这个，工人会对着已有 Farm 的地反复开工
                if (t.improvement != null)
                    sb.append(""","improvement":""").append(quote(t.improvement!!))
                val wip = t.improvementInProgress
                if (wip != null) sb.append(""","improvementInProgress":""").append(quote(wip))
                    .append(""","turnsToImprovement":""").append(t.turnsToImprovement)
                if (t.roadStatus != null && t.roadStatus!!.name == "Road")
                    sb.append(""","road":true""")
                else if (t.roadStatus != null && t.roadStatus!!.name == "Railroad")
                    sb.append(""","railroad":true""")
                val owner = t.getOwner()
                if (owner != null) sb.append(""","owner":""").append(quote(owner.civName))
                try {
                    val us = StringBuilder()
                    for (mu in t.getUnits()) {
                        val own = mu.civ === c
                        if (us.length > 0) us.append(',')
                        us.append("""{"id":""").append(quote("unit-" + mu.id))
                            .append(""","own":""").append(own)
                            .append(""","owner":""").append(quote(mu.civ.civName))
                            .append(""","name":""").append(quote(mu.name))
                            .append(""","hp":""").append(mu.health)
                            .append(""","military":""").append(mu.isMilitary()).append('}')
                    }
                    if (us.length > 0) sb.append(""","units":[""").append(us).append(']')
                } catch (ignored: Throwable) { }
                try {
                    val tc = if (t.isCityCenter()) t.getCity() else null
                    if (tc != null) sb.append(""","city":{"id":""").append(quote("city-" + tc.id))
                        .append(""","name":""").append(quote(tc.name))
                        .append(""","owner":""").append(quote(tc.civ.civName))
                        .append(""","pop":""").append(tc.population.population)
                        .append(""","hp":""").append(tc.health).append('}')
                } catch (ignored: Throwable) { }
                sb.append('}')
            }
        }
        return sb.append("]}").toString()
    }

    fun eventsJson(gi: GameInfo, c: Civilization, limit: Int?): String {
        val n = if (limit == null) 20 else Math.max(1, Math.min(limit, 200))
        val all = c.notifications
        val start = Math.max(0, all.size - n)
        val sb = StringBuilder("""{"total":""").append(all.size).append(""","events":[""")
        for (i in start until all.size) {
            val no = all[i]
            if (i > start) sb.append(',')
            sb.append("""{"category":""").append(quote(no.category.name))
                .append(""","text":""").append(quote(no.text)).append('}')
        }
        return sb.append("]}").toString()
    }

    fun victoryJson(gi: GameInfo, c: Civilization): String {
        val sb = StringBuilder("""{"turn":""").append(gi.turns).append(""","victories":[""")
        var first = true
        try {
            for (v in gi.ruleset.victories.values) {
                if (!first) sb.append(',')
                first = false
                sb.append("""{"name":""").append(quote(v.name))
                    .append(""","enabled":""").append(gi.gameParameters.victoryTypes.contains(v.name))
                    .append('}')
            }
        } catch (t: Throwable) {
            return """{"error":{"code":"INTERNAL","message":${quote(t.toString())}}}"""
        }
        return sb.append("]}").toString()
    }

    fun tileJson(gi: GameInfo, c: Civilization, x: Int?, y: Int?): String {
        if (x == null || y == null) return """{"error":{"code":"BAD_PARAMS","message":"需要 x 与 y"}}"""
        val t = Kernel.tileAt(gi, x, y)
            ?: return """{"error":{"code":"UNKNOWN_ID","message":${quote("没有这个格子")}}}"""
        val feat = StringBuilder()
        for (f in t.terrainFeatures) {
            if (feat.length > 0) feat.append(',')
            feat.append(quote(f))
        }
        var cityJson = StringBuilder("null")
        try {
            // getCity() 返回的是"拥有这块地的城"，任何领土格都非空；只有中心格才是那座城
            val tc = if (t.isCityCenter()) t.getCity() else null
            if (tc != null) {
                cityJson = StringBuilder("""{"id":""").append(quote("city-" + tc.id))
                    .append(""","name":""").append(quote(tc.name))
                    .append(""","owner":""").append(quote(tc.civ.civName))
                    .append(""","pop":""").append(tc.population.population)
                    .append(""","hp":""").append(tc.health)
                    .append('}')
            }
        } catch (ignored: Throwable) { }
        return """{"x":${t.position.x},"y":${t.position.y}""" +
            ""","terrain":${quote(t.baseTerrain)}""" +
            ""","features":[$feat]""" +
            ""","explored":${t.isExplored(c)}""" +
            ""","visible":${t.isVisible(c)}""" +
            ""","resource":${t.resource?.let { quote(it) } ?: "null"}""" +
            ""","improvement":${t.improvement?.let { quote(it) } ?: "null"}""" +
            ""","yields":${tileYields(t, c)}""" +
            ""","city":$cityJson""" +
            "}"
    }

    fun diplomacyJson(gi: GameInfo, c: Civilization): String {
        val sb = StringBuilder("""{"me":${quote(c.civName)},"known":[""")
        var first = true
        for (other in c.getKnownCivs()) {
            if (other === c) continue
            val dm = c.getDiplomacyManager(other)
            if (!first) sb.append(',')
            first = false
            sb.append("""{"civ":""").append(quote(other.civName))
                .append(""","isCityState":""").append(other.isCityState)
                .append(""","atWar":""").append(c.isAtWarWith(other))
                .append(""","canDeclareWar":""").append(dm != null && dm.canDeclareWar())
                .append('}')
        }
        return sb.append("]}").toString()
    }

    fun policiesJson(gi: GameInfo, c: Civilization): String {
        val adopted = StringBuilder()
        for (p in c.policies.getAdoptedPolicies()) {
            if (adopted.length > 0) adopted.append(',')
            adopted.append(quote(p))
        }
        val branches = StringBuilder()
        val tree = StringBuilder()
        var firstBranch = true
        try {
            for (b in c.policies.adoptableBranches) {
                if (branches.length > 0) branches.append(',')
                branches.append(quote(b.name))
            }
            for (b in gi.ruleset.policyBranches.values) {
                if (!firstBranch) tree.append(',')
                firstBranch = false
                tree.append("""{"branch":""").append(quote(b.name)).append(""","policies":[""")
                var firstPolicy = true
                for (p in b.policies) {
                    if (!firstPolicy) tree.append(',')
                    firstPolicy = false
                    val isAdopted = c.policies.isAdopted(p.name)
                    var requiresMet = true
                    for (req in p.requires ?: emptyList())
                        if (!c.policies.isAdopted(req)) requiresMet = false
                    tree.append("""{"name":""").append(quote(p.name))
                        .append(""","adopted":""").append(isAdopted)
                        .append(""","requires":[""").append(joinQuoted(p.requires ?: emptyList())).append(']')
                        .append(""","ready":""").append(!isAdopted && requiresMet)
                        .append('}')
                }
                tree.append("]}")
            }
        } catch (ignored: Throwable) { }
        var culture = 0
        var nextCost = 0
        try {
            culture = c.policies.getCultureNeededForNextPolicy()  // 其实是"还差多少"
            nextCost = culture
        } catch (ignored: Throwable) { }
        var stored = 0L
        try {
            stored = c.policies.storedCulture.toLong()
        } catch (ignored: Throwable) { }
        return """{"adopted":[$adopted]""" +
            ""","culture":$stored""" +
            ""","cultureNeededForNextPolicy":$nextCost""" +
            ""","canAdoptPolicy":${c.policies.canAdoptPolicy()}""" +
            ""","adoptableBranches":[$branches]""" +
            ""","tree":[$tree]}"""
    }

    fun joinQuoted(values: Collection<String>): String {
        val sb = StringBuilder()
        for (v in values) {
            if (sb.length > 0) sb.append(',')
            sb.append(quote(v))
        }
        return sb.toString()
    }

    fun tradeOffersJson(gi: GameInfo, c: Civilization, withCiv: String?): String {
        val pending = StringBuilder()
        for (r in c.tradeRequests) {
            val from = gi.getCivilization(r.requestingCiv)
            if (pending.length > 0) pending.append(',')
            pending.append("""{"civ":""").append(quote(if (from == null) r.requestingCiv else from.civName))
                .append(""","proposedByMe":""").append(c.civID == r.requestingCiv)
                .append(""","give":""").append(Kernel.offersJson(r.trade.theirOffers))
                .append(""","want":""").append(Kernel.offersJson(r.trade.ourOffers))
                .append('}')
        }
        val sb = StringBuilder("""{"pending":[""").append(pending).append(']')
        if (withCiv != null) {
            var other: Civilization? = null
            for (x in gi.civilizations) {
                if (x === c) continue
                if (x.civName == withCiv || x.civID == withCiv) other = x
            }
            if (other == null) {
                return """{"error":{"code":"UNKNOWN_ID","message":${quote("没有这个文明: $withCiv")}}}"""
            }
            val tl = com.unciv.logic.trade.TradeLogic(c, other)
            sb.append(""","with":""").append(quote(other.civName))
                .append(""","youCanGive":""").append(Kernel.offersJson(tl.ourAvailableOffers))
                .append(""","theyCanGive":""").append(Kernel.offersJson(tl.theirAvailableOffers))
        }
        return sb.append('}').toString()
    }

    /**
     * 一回合的完整简报：把客户端会呈现给玩家的东西合成一份，agent 每回合读这一条就够。
     * 只讲事实，不给建议。
     */
    fun briefingJson(gi: GameInfo, c: Civilization): String {
        val sb = StringBuilder("""{"turn":""").append(gi.turns)
            .append(""","civ":""").append(quote(c.civName))
        try {
            sb.append(""","era":""").append(quote(c.getEra().name))
        } catch (ignored: Throwable) { sb.append(""","era":null""") }

        val gold = c.gold
        var gpt = 0f
        var sci = 0f
        var cult = 0f
        var faith = 0f
        var happiness = 0
        try {
            val st = c.stats.statsForNextTurn
            gpt = st.gold; sci = st.science; cult = st.culture; faith = st.faith
            happiness = c.stats.happiness
        } catch (ignored: Throwable) { }
        try {
            sb.append(""","difficulty":""").append(quote(gi.gameParameters.difficulty))
                .append(""","speed":""").append(quote(gi.gameParameters.speed))
        } catch (ignored: Throwable) { }
        sb.append(""","gold":""").append(gold)
            .append(""","goldPerTurn":""").append(round1(gpt))
            .append(""","sciencePerTurn":""").append(round1(sci))
            .append(""","culture":""").append(c.policies.storedCulture)
            .append(""","culturePerTurn":""").append(round1(cult))
            .append(""","cultureForNextPolicy":""").append(c.policies.getCultureNeededForNextPolicy())
            .append(""","faith":""").append(c.religionManager.storedFaith)
            .append(""","faithPerTurn":""").append(round1(faith))
            .append(""","happiness":""").append(happiness)

        // 研究
        val cur = c.tech.currentTechnologyName()
        var available = 0
        try {
            for (t in gi.ruleset.technologies.keys)
                if (c.tech.canBeResearched(t)) available++
        } catch (ignored: Throwable) { }
        sb.append(""","research":{"current":""").append(if (cur == null || cur.isEmpty()) "null" else quote(cur))
            .append(""","turnsLeft":""")
        if (cur == null || cur.isEmpty() || sci <= 0.01f) sb.append("null")
        else {
            try {
                sb.append(Math.ceil(Math.max(0, c.tech.remainingScienceToTech(cur)) / sci.toDouble()).toInt())
            } catch (e: Throwable) { sb.append("null") }
        }
        sb.append(""","available":""").append(available).append('}')

        // 城市
        sb.append(""","cities":[""")
        var firstCity = true
        for (city in c.cities) {
            if (!firstCity) sb.append(',')
            firstCity = false
            var prod: String? = null
            var turns: Int? = null
            try {
                prod = city.cityConstructions.getCurrentConstruction().name
                if (prod != null && prod.isNotEmpty() && prod != "Nothing") {
                    val perTurn = city.cityStats.currentCityStats.production
                    val remaining = city.cityConstructions.getRemainingWork(prod, false)
                    turns = if (perTurn <= 0.01f) null else Math.ceil(remaining / perTurn.toDouble()).toInt()
                } else prod = null
            } catch (e: Throwable) { prod = null }
            var underAttack = false
            try {
                for (n in city.getCenterTile().getNeighbors()) {
                    if (hasForeignMilitary(n, c)) { underAttack = true; break }
                }
            } catch (ignored: Throwable) { }
            sb.append("""{"id":""").append(quote("city-" + city.id))
                .append(""","name":""").append(quote(city.name))
                .append(""","pop":""").append(city.population.population)
                .append(""","at":[""").append(city.location.x).append(',').append(city.location.y).append(']')
                .append(""","foodPerTurn":""").append(round1(city.cityStats.currentCityStats.food))
                .append(""","turnsToGrowth":""").append(if (city.population.getNumTurnsToNewPopulation() == null)
                    "null" else city.population.getNumTurnsToNewPopulation())
                .append(""","production":""").append(if (prod == null) "null" else quote(prod))
                .append(""","turnsToBuild":""").append(if (turns == null) "null" else turns)
                .append(""","isPuppet":""").append(city.isPuppet)
                .append(""","inResistance":""").append(city.isInResistance())
                .append(""","health":""").append(city.health)
                .append(""","canBombard":""").append(city.canBombard())
                .append(""","enemyAdjacent":""").append(underAttack)
                .append('}')
        }
        sb.append(']')

        // 需要操作的单位
        sb.append(""","unitsNeedingOrders":[""")
        var firstUnit = true
        for (u in c.units.getCivUnits()) {
            if (u.currentMovement <= 0.1f) continue
            if (!firstUnit) sb.append(',')
            firstUnit = false
            val p = u.getTile().position
            sb.append("""{"id":""").append(quote("unit-" + u.id))
                .append(""","name":""").append(quote(u.name))
                .append(""","x":""").append(p.x).append(""","y":""").append(p.y)
                .append(""","moves":""").append(u.currentMovement)
                .append(""","military":""").append(u.isMilitary())
                .append(""","civilian":""").append(u.isCivilian())
                .append(""","canAttack":""").append(u.canAttack())
                .append(""","canPromote":""").append(u.promotions.canBePromoted())
            // 工人：这格能造什么，别让模型自己翻规则
            if (u.cache.hasUniqueToBuildImprovements) {
                sb.append(""","canBuild":[""")
                var n = 0
                for (ti in u.getTile().ruleset.tileImprovements.values) {
                    var ok = false
                    try { ok = u.canBuildImprovement(ti, u.getTile()) } catch (ignored: Throwable) { }
                    if (!ok) continue
                    if (n++ > 0) sb.append(',')
                    sb.append(quote(ti.name))
                    if (n >= 8) break
                }
                sb.append(']')
                val wip = u.getTile().improvementInProgress
                sb.append(""","workingOn":""").append(if (wip == null) "null" else quote(wip))
                    .append(""","turnsLeft":""").append(u.getTile().turnsToImprovement)
            }
            // 军事：这回合打得到谁
            if (u.canAttack()) {
                sb.append(""","attackable":[""")
                try {
                    val paths = u.movement.getDistanceToTiles()
                    val list = com.unciv.logic.battle.TargetHelper.getAttackableEnemies(u, paths, null, false)
                    val seen = java.util.LinkedHashSet<String>()
                    var n = 0
                    for (at in list) {
                        val t = at.tileToAttack
                        val key = "${t.position.x},${t.position.y}"
                        if (!seen.add(key)) continue
                        if (n++ > 0) sb.append(',')
                        sb.append("""{"x":""").append(t.position.x)
                            .append(""","y":""").append(t.position.y)
                            .append(""","type":""").append(quote(if (t.isCityCenter()) "city" else "unit"))
                            .append('}')
                        if (n >= 6) break
                    }
                } catch (ignored: Throwable) { }
                sb.append(']')
            }
            sb.append('}')
        }
        sb.append(']')

        // 外交 / 城邦
        val known = StringBuilder()
        val cs = StringBuilder()
        var firstKnown = true
        var firstCs = true
        for (o in c.getKnownCivs()) {
            if (o === c) continue
            val dm = c.getDiplomacyManager(o)
            var influence = 0
            try {
                if (o.isCityState && o.getDiplomacyManager(c) != null)
                    influence = o.getDiplomacyManager(c)!!.influence.toInt()
            } catch (ignored: Throwable) { }
            if (!firstKnown) known.append(',')
            firstKnown = false
            known.append("""{"civ":""").append(quote(o.civName))
                .append(""","isCityState":""").append(o.isCityState)
                .append(""","atWar":""").append(c.isAtWarWith(o))
                .append(""","canDeclareWar":""").append(dm != null && dm.canDeclareWar())
                .append(""","influence":""").append(influence)
                .append('}')
            if (o.isCityState) {
                if (!firstCs) cs.append(',')
                firstCs = false
                cs.append("""{"civ":""").append(quote(o.civName))
                    .append(""","influence":""").append(influence)
                    .append(""","ally":""").append(if (o.allyCiv == null) "null" else quote(o.allyCiv!!.civName))
                    .append('}')
            }
        }
        sb.append(""","diplomacy":{"known":[""").append(known).append("]}")
        sb.append(""","cityStates":[""").append(cs).append(']')

        // 宗教
        sb.append(""","religion":{"state":""").append(quote(c.religionManager.religionState.name))
            .append(""","faith":""").append(c.religionManager.storedFaith)
            .append(""","hasFreeBeliefs":""").append(c.religionManager.hasFreeBeliefs())
            .append('}')

        // 待处理占城
        val pending = StringBuilder()
        for (a in c.popupAlerts) {
            if (a.type != com.unciv.logic.civilization.AlertType.CityConquered) continue
            if (pending.length > 0) pending.append(',')
            pending.append(quote("city-" + a.value))
        }
        sb.append(""","pendingCaptures":[""").append(pending).append(']')

        // 本回合通知
        val ev = StringBuilder()
        val all = c.notifications
        val start = Math.max(0, all.size - 25)
        for (i in start until all.size) {
            if (i > start) ev.append(',')
            ev.append("""{"category":""").append(quote(all[i].category.name))
                .append(""","text":""").append(quote(all[i].text)).append('}')
        }
        sb.append(""","events":[""").append(ev).append(']')

        // 提醒
        val warnings = ArrayList<String>()
        var idleCities = 0
        for (city in c.cities) {
            if (city.isPuppet || city.isInResistance()) continue
            val item = city.cityConstructions.getCurrentConstruction().name
            if (item == null || item.isEmpty() || item == "Nothing") idleCities++
        }
        if (idleCities > 0) warnings.add("$idleCities 座城没有在生产任何东西")
        if (cur == null || cur.isEmpty()) warnings.add("没有在研究的科技")
        if (gpt < 0 && gold >= 0) warnings.add("每回合亏 " + round1(gpt) + " 金币")
        if (gold <= -200) warnings.add("国库 $gold，已经在解散军队抵债")
        else if (gpt < 0) {
            val turnsLeft = Math.floor((gold + 200) / -gpt.toDouble()).toInt()
            warnings.add("每回合亏 " + round1(gpt) + " 金币，国库 $gold" +
                "，跌破 -200 才解散军队" +
                (if (turnsLeft > 0) "，大约 $turnsLeft 回合" else ""))
        }
        if (pending.length > 0) warnings.add("有刚打下的城还没决定吞并/傀儡")
        if (happiness < 0) warnings.add("快乐为负（$happiness），增长和产能会受影响")
        val w = StringBuilder()
        for (s in warnings) {
            if (w.length > 0) w.append(',')
            w.append(quote(s))
        }
        sb.append(""","warnings":[""").append(w).append("]}")
        return sb.toString()
    }

    private fun hasForeignMilitary(t: Tile, c: Civilization): Boolean {
        try {
            for (u in t.getUnits()) {
                if (u.civ !== c && u.isMilitary()) return true
            }
        } catch (ignored: Throwable) { }
        return false
    }

    /** 游戏自带教程/百科条目，给 agent 当攻略参考 */
    private var guideEntries: List<Any?> = ArrayList()

    private fun loadGuide() {
        val f = File("jsons/Tutorials.json")
        if (!f.exists()) {
            System.out.println("[unciv-agent] 没找到 jsons/Tutorials.json，/guide 会返回空")
            return
        }
        try {
            val text = String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)
            val parsed = parseRelaxed(text)
            guideEntries = arr(parsed)
            System.out.println("[unciv-agent] 载入教程条目 " + guideEntries.size + " 条")
        } catch (t: Throwable) {
            System.out.println("[unciv-agent] 教程载入失败: " + t)
        }
    }

    fun guideJson(nameFilter: String?, categoryFilter: String?): String {
        val sb = StringBuilder("""{"entries":[""")
        var first = true
        for (o in guideEntries) {
            val e = obj(o)
            val name = str(e, "name")
            val category = str(e, "category")
            if (nameFilter != null && (name == null || !name.lowercase().contains(nameFilter.lowercase()))) continue
            if (categoryFilter != null && (category == null || !category.lowercase().contains(categoryFilter.lowercase()))) continue
            if (!first) sb.append(',')
            first = false
            sb.append("""{"name":""").append(if (name == null) "null" else quote(name))
                .append(""","category":""").append(if (category == null) "null" else quote(category))
            val steps = arr(e["steps"])
            sb.append(""","steps":[""")
            for (i in steps.indices) {
                if (i > 0) sb.append(',')
                sb.append(quote(steps[i].toString()))
            }
            sb.append(']')
            val ctext = arr(e["civilopediaText"])
            val body = StringBuilder()
            for (line in ctext) {
                if (line !is Map<*, *>) continue
                val t = str(obj(line), "text")
                if (t != null && t.isNotEmpty()) {
                    if (body.length > 0) body.append("\n")
                    body.append(t)
                }
            }
            if (body.length == 0) {
                for (i in steps.indices) {
                    if (i > 0) body.append("\n")
                    body.append(steps[i].toString())
                }
            }
            sb.append(""","text":""").append(quote(body.toString())).append('}')
        }
        return sb.append("]}").toString()
    }

    /**
     * 攻击预览：不执行动作，只算伤害范围。客户端点目标时会显示这个，agent 之前只能打了才知道。
     */
    fun previewAttackJson(gi: GameInfo, c: Civilization, unitRef: String?, cityRef: String?, x: Int?, y: Int?): String {
        if (x == null || y == null) return """{"error":{"code":"BAD_PARAMS","message":"需要 x 与 y"}}"""
        val target = Kernel.tileAt(gi, x, y)
            ?: return """{"error":{"code":"UNKNOWN_ID","message":${quote("没有这个格子")}}}"""

        var defender: com.unciv.logic.battle.ICombatant? = null
        var targetType = "none"
        try {
            if (target.isCityCenter() && target.getCity() != null) {
                defender = com.unciv.logic.battle.CityCombatant(target.getCity()!!)
                targetType = "city"
            } else {
                for (mu in target.getUnits()) {
                    if (mu.civ !== c) {
                        defender = com.unciv.logic.battle.MapUnitCombatant(mu); targetType = "unit"; break
                    }
                }
            }
        } catch (ignored: Throwable) { }
        if (defender == null)
            return """{"error":{"code":"ILLEGAL_TARGET","message":${quote("这个格子上没有可打的敌人")}}}"""

        var attacker: com.unciv.logic.battle.ICombatant? = null
        var fromTile: Tile? = null
        var range = 1

        if (unitRef != null) {
            val id = Kernel.idOf(unitRef, "unit")
            var u: MapUnit? = null
            if (id != null) {
                for (m in c.units.getCivUnits()) { if (m.id == id) u = m }
            }
            if (u == null) return """{"error":{"code":"NOT_YOUR_UNIT","message":${quote("没有这个单位")}}}"""
            attacker = com.unciv.logic.battle.MapUnitCombatant(u)
            fromTile = u.getTile()
            range = u.getRange()
        } else if (cityRef != null) {
            var city: City? = null
            for (cc in c.cities)
                if (("city-" + cc.id) == cityRef || cc.id == cityRef || cc.name == cityRef) city = cc
            if (city == null) return """{"error":{"code":"NOT_YOUR_CITY","message":${quote("没有这个城市")}}}"""
            if (!city.canBombard())
                return """{"error":{"code":"NOT_AVAILABLE","message":${quote(city.name + " 现在不能开火")}}}"""
            attacker = com.unciv.logic.battle.CityCombatant(city)
            fromTile = city.getCenterTile()
            range = city.getBombardRange()
        } else {
            return """{"error":{"code":"BAD_PARAMS","message":${quote("需要 unit 或 city")}}}"""
        }

        val def = defender!!
        val distance = fromTile!!.aerialDistanceTo(target)
        val bd = com.unciv.logic.battle.BattleDamage
        val defMin: Int
        val defMax: Int
        val atkMin: Int
        val atkMax: Int
        val defStrength: Int
        val atkStrength: Int
        try {
            defMin = bd.calculateDamageToDefender(attacker!!, def, fromTile, 0f)
            defMax = bd.calculateDamageToDefender(attacker, def, fromTile, 1f)
            atkMin = bd.calculateDamageToAttacker(attacker, def, fromTile, 0f)
            atkMax = bd.calculateDamageToAttacker(attacker, def, fromTile, 1f)
            atkStrength = bd.getAttackingStrength(attacker, def, fromTile).toInt()
            defStrength = bd.getDefendingStrength(attacker, def, fromTile).toInt()
        } catch (e: Throwable) {
            return """{"error":{"code":"NOT_AVAILABLE","message":${quote("算不出伤害: " + e.message)}}}"""
        }
        val defenderHp = def.getHealth()
        val attackerHp = attacker!!.getHealth()
        val inRange = distance <= range

        return """{"target":[$x,$y]""" +
            ""","targetType":${quote(targetType)}""" +
            ""","distance":$distance""" +
            ""","range":$range""" +
            ""","inRange":$inRange""" +
            ""","attackerStrength":$atkStrength""" +
            ""","defenderStrength":$defStrength""" +
            ""","damageToDefender":{"min":$defMin,"max":$defMax}""" +
            ""","damageToAttacker":{"min":$atkMin,"max":$atkMax}""" +
            ""","defenderHp":$defenderHp""" +
            ""","attackerHp":$attackerHp""" +
            ""","wouldKillDefender":${defMax >= defenderHp}""" +
            ""","wouldKillAttacker":${atkMax >= attackerHp}}"""
    }

    /** 帝国总览的 Stats 页：金币/科研/文化/信仰按来源拆开，加快乐拆解。 */
    fun statsJson(gi: GameInfo, c: Civilization): String {
        val sb = StringBuilder("""{"turn":""").append(gi.turns)
            .append(""","gold":""").append(c.gold)
            .append(""","perTurn":{""")
        try {
            val statMap = c.stats.getStatMapForNextTurn()
            val total = com.unciv.models.stats.Stats()
            for (st in statMap.values) total.add(st)
            sb.append(""""gold":""").append(round1(total.gold))
                .append(""","science":""").append(round1(total.science))
                .append(""","culture":""").append(round1(total.culture))
                .append(""","faith":""").append(round1(total.faith))
                .append(""","happiness":""").append(round1(c.getHappiness().toFloat()))
            sb.append("""},"causes":[""")
            var first = true
            for (e in statMap.entries) {
                val st = e.value
                val any = Math.abs(st.gold) > 0.05f || Math.abs(st.science) > 0.05f ||
                    Math.abs(st.culture) > 0.05f || Math.abs(st.faith) > 0.05f
                if (!any) continue
                if (!first) sb.append(',')
                first = false
                sb.append("""{"cause":""").append(quote(e.key))
                if (Math.abs(st.gold) > 0.05f) sb.append(""","gold":""").append(round1(st.gold))
                if (Math.abs(st.science) > 0.05f) sb.append(""","science":""").append(round1(st.science))
                if (Math.abs(st.culture) > 0.05f) sb.append(""","culture":""").append(round1(st.culture))
                if (Math.abs(st.faith) > 0.05f) sb.append(""","faith":""").append(round1(st.faith))
                sb.append('}')
            }
            sb.append(']')
        } catch (ignored: Throwable) { sb.append(""")}, "causes":[]""") }
        sb.append(""","happinessCauses":[""")
        try {
            val hb = c.stats.getHappinessBreakdown()
            var first = true
            for (e in hb.entries) {
                if (Math.abs(e.value) < 0.05f) continue
                if (!first) sb.append(',')
                first = false
                sb.append("""{"cause":""").append(quote(e.key))
                    .append(""","value":""").append(round1(e.value)).append('}')
            }
        } catch (ignored: Throwable) { }
        sb.append(']')
        // 城市一级的产出，UI 的城市列表也是这么排的
        sb.append(""","cities":[""")
        var firstCity = true
        for (city in c.cities) {
            if (!firstCity) sb.append(',')
            firstCity = false
            val st = city.cityStats.currentCityStats
            sb.append("""{"name":""").append(quote(city.name))
                .append(""","pop":""").append(city.population.population)
                .append(""","food":""").append(round1(st.food))
                .append(""","production":""").append(round1(st.production))
                .append(""","gold":""").append(round1(st.gold))
                .append(""","science":""").append(round1(st.science))
                .append(""","culture":""").append(round1(st.culture))
                .append(""","faith":""").append(round1(st.faith))
                .append('}')
        }
        return sb.append("]}").toString()
    }

    /** 资源总览：奢侈品连没连、战略资源有多少。对应 UI 的 Resources 页。 */
    fun resourcesJson(gi: GameInfo, c: Civilization): String {
        val sb = StringBuilder("""{"luxuries":{"connected":[""")
        var amounts: HashMap<String, Int>
        try { amounts = c.getCivResourcesByName() } catch (e: Throwable) { amounts = HashMap() }
        var first = true
        try {
            for (r in gi.ruleset.tileResources.values) {
                if (r.resourceType != com.unciv.models.ruleset.tile.ResourceType.Luxury) continue
                val n = amounts.getOrDefault(r.name, 0)
                if (n <= 0) continue
                if (!first) sb.append(',')
                first = false
                sb.append("""{"name":""").append(quote(r.name))
                    .append(""","count":""").append(n).append('}')
            }
        } catch (ignored: Throwable) { }
        sb.append("""],"unconnected":[""")
        first = true
        val seen = java.util.LinkedHashSet<String>()
        try {
            for (city in c.cities) {
                for (t in city.getWorkableTiles()) {
                    val res = t.resource ?: continue
                    val tr = gi.ruleset.tileResources[res]
                    if (tr == null || tr.resourceType != com.unciv.models.ruleset.tile.ResourceType.Luxury) continue
                    if (amounts.getOrDefault(res, 0) > 0) continue
                    if (!seen.add(res)) continue
                    if (!first) sb.append(',')
                    first = false
                    sb.append("""{"name":""").append(quote(res))
                        .append(""","at":[""").append(t.position.x).append(',')
                        .append(t.position.y).append(']')
                        .append(""","improvement":""").append(if (t.improvement == null)
                            "null" else quote(t.improvement!!))
                        .append('}')
                }
            }
        } catch (ignored: Throwable) { }
        sb.append("""]},"strategic":[""")
        first = true
        try {
            for (r in gi.ruleset.tileResources.values) {
                if (r.resourceType != com.unciv.models.ruleset.tile.ResourceType.Strategic) continue
                val n = amounts.getOrDefault(r.name, 0)
                if (n <= 0) continue
                if (!first) sb.append(',')
                first = false
                sb.append("""{"name":""").append(quote(r.name))
                    .append(""","count":""").append(n).append('}')
            }
        } catch (ignored: Throwable) { }
        sb.append("]}")
        return sb.toString()
    }

    /** 移动预览：一条路线、每格代价、几回合到、到不了为什么。只读，不消耗移动力。 */
    fun previewMoveJson(gi: GameInfo, c: Civilization, unitRef: String?, x: Int?, y: Int?): String {
        val id = Kernel.idOf(unitRef, "unit")
        var unit: MapUnit? = null
        if (id != null) {
            for (m in c.units.getCivUnits()) { if (m.id == id) unit = m }
        }
        if (unit == null)
            return """{"error":{"code":"NOT_YOUR_UNIT","message":${quote("没有这个单位")}}}"""
        if (x == null || y == null)
            return """{"error":{"code":"BAD_PARAMS","message":${quote("需要 x 与 y")}}}"""
        val dest = Kernel.tileAt(gi, x, y)
            ?: return """{"error":{"code":"ILLEGAL_TARGET","message":${quote("地图外: $x,$y")}}}"""

        val from = unit.getTile()
        val fp = from.position
        val sb = StringBuilder("""{"unit":""").append(quote("unit-" + unit.id))
            .append(""","name":""").append(quote(unit.name))
            .append(""","from":[""").append(fp.x).append(',').append(fp.y).append(']')
            .append(""","to":[""").append(x).append(',').append(y).append(']')
            .append(""","distance":""").append(from.aerialDistanceTo(dest))
            .append(""","moves":""").append(unit.currentMovement)
            .append(""","maxMoves":""").append(unit.getMaxMovement(false))

        var reason: String? = null
        try {
            val r = unit.movement.getCannotMoveToReason(dest, false, true, false, null)
            if (r != null) reason = r.toString()
        } catch (ignored: Throwable) { }

        // 这回合能不能到
        var paths: PathsToTilesWithinTurn? = null
        try { paths = unit.movement.getDistanceToTiles() }
        catch (ignored: Throwable) { }

        if (paths != null && paths.containsKey(dest)) {
            sb.append(""","reachableThisTurn":true,"turnsToReach":1""")
            var total = 0f
            try { total = paths[dest]!!.totalMovement } catch (ignored: Throwable) { }
            sb.append(""","totalMovement":""").append(round2(total))
                .append(""","movesLeftAfter":""").append(round2(Math.max(0f, unit.currentMovement - total)))
            sb.append(""","path":""")
            appendPath(sb, unit, paths, dest)
        } else {
            sb.append(""","reachableThisTurn":false""")
            // 多回合：按代价做 Dijkstra，再按每回合移动力模拟
            var route: List<Tile>? = null
            var cost = 0f
            try {
                val res = planRoute(unit, from, dest)
                if (res != null) { route = res[0] as List<Tile>; cost = res[1] as Float }
            } catch (ignored: Throwable) { }
            if (route == null) {
                sb.append(""","turnsToReach":null,"path":[]""")
            } else {
                val sim = simulateTurns(unit, route)
                sb.append(""","turnsToReach":""").append(sim[0])
                    .append(""","totalMovement":""").append(round2(cost))
                    .append(""","movesLeftAfter":""").append(round2(sim[1] / 100f))
                    .append(""","path":""")
                val pj = StringBuilder("[")
                for (i in route.indices) {
                    val t = route[i]
                    if (i > 0) pj.append(',')
                    pj.append("""{"x":""").append(t.position.x)
                        .append(""","y":""").append(t.position.y)
                        .append(""","terrain":""").append(quote(if (t.lastTerrain == null) "" else t.lastTerrain!!.name))
                        .append('}')
                }
                pj.append(']')
                sb.append(pj)
            }
        }

        // 目标格本身的信息
        sb.append(""","destination":{"terrain":""")
            .append(quote(if (dest.lastTerrain == null) "" else dest.lastTerrain!!.name))
            .append(""","improvement":""").append(if (dest.improvement == null) "null" else quote(dest.improvement!!))
            .append(""","city":""").append(if (dest.isCityCenter() && dest.getCity() != null)
                quote(dest.getCity()!!.name) else "null")
            .append('}')
        sb.append(""","blocked":""").append(if (reason == null) "null" else quote(reason))
        sb.append(""","blockedHint":""").append(if (reason == null) "null" else quote(moveReasonHint(reason)))
        return sb.append('}').toString()
    }

    private fun appendPath(sb: StringBuilder, unit: MapUnit, paths: PathsToTilesWithinTurn, dest: Tile) {
        val route: List<Tile>
        try { route = paths.getPathToTile(dest) }
        catch (e: Throwable) { sb.append("[]"); return }
        sb.append('[')
        var prev = unit.getTile()
        var first = true
        for (t in route) {
            if (!first) sb.append(',')
            first = false
            var step = 0f
            try {
                step = com.unciv.logic.map.mapunit.movement.MovementCost
                    .getMovementCostBetweenAdjacentTiles(unit, prev, t, false)
            } catch (ignored: Throwable) { }
            sb.append("""{"x":""").append(t.position.x)
                .append(""","y":""").append(t.position.y)
                .append(""","terrain":""").append(quote(if (t.lastTerrain == null) "" else t.lastTerrain!!.name))
                .append(""","cost":""").append(round2(step))
                .append('}')
            prev = t
        }
        sb.append(']')
    }

    /** 按步进代价做 Dijkstra，只走 canPassThrough 的格子；返回 {List<Tile> 路线, Float 总代价} 或 null。 */
    private fun planRoute(unit: MapUnit, from: Tile, dest: Tile): Array<Any?>? {
        val cap = 1500
        val dist = java.util.HashMap<Tile, Float>()
        val prev = java.util.HashMap<Tile, Tile>()
        val pq = java.util.PriorityQueue<Array<Any?>>(16) { a, b -> (a[0] as Float).compareTo(b[0] as Float) }
        dist[from] = 0f
        pq.add(arrayOf<Any?>(0f, from))
        var visited = 0
        while (!pq.isEmpty() && visited++ < cap) {
            val cur = pq.poll()
            val d = cur[0] as Float
            val t = cur[1] as Tile
            if (d > dist.getOrDefault(t, Float.MAX_VALUE)) continue
            if (t === dest) break
            for (n in t.getNeighbors()) {
                var pass = false
                try { pass = unit.movement.canPassThrough(n, false) } catch (ignored: Throwable) { }
                if (!pass && n !== dest) continue
                val step: Float
                try {
                    step = com.unciv.logic.map.mapunit.movement.MovementCost
                        .getMovementCostBetweenAdjacentTiles(unit, t, n, false)
                } catch (e: Throwable) { continue }
                if (step >= 100f) continue
                val nd = d + step
                if (nd < dist.getOrDefault(n, Float.MAX_VALUE)) {
                    dist[n] = nd
                    prev[n] = t
                    pq.add(arrayOf<Any?>(nd, n))
                }
            }
        }
        if (!dist.containsKey(dest)) return null
        val route = ArrayList<Tile>()
        var cur: Tile? = dest
        while (cur !== from) {
            route.add(0, cur!!)
            cur = prev[cur]
            if (cur == null) return null
        }
        return arrayOf(route, dist[dest])
    }

    /** 按每回合重置移动力模拟走这条路线；返回 {回合数, 最后剩余移动力×100}。 */
    private fun simulateTurns(unit: MapUnit, route: List<Tile>): IntArray {
        val max = Math.max(1, unit.getMaxMovement(false))
        var moves = unit.currentMovement
        var turns = 1
        var prev = unit.getTile()
        for (t in route) {
            var step = 1f
            try {
                step = com.unciv.logic.map.mapunit.movement.MovementCost
                    .getMovementCostBetweenAdjacentTiles(unit, prev, t, false)
            } catch (e: Throwable) { }
            if (moves <= 0.05f) { turns++; moves = max.toFloat() }
            moves = Math.max(0f, moves - step)
            prev = t
        }
        return intArrayOf(turns, Math.round(moves * 100))
    }

    private fun moveReasonHint(reason: String): String {
        return when (reason) {
            "TerrainImpassable" -> "地形不可通行"
            "BoatCannotGoOnLand" -> "船不能上岸"
            "CannotEmbark" -> "这个单位不能上船/下水"
            "CannotEnterOcean" -> "还不能进远洋，需要相关科技"
            "CannotEnterForeignLand" -> "不能进入别国领土（没开边）"
            "CannotEnterCityCenter" -> "不能进入别人城市中心"
            "TileIsNotEmpty" -> "目标格已经有单位"
            "NoAirUnitTransport" -> "没有可用的空中运输"
            else -> reason
        }
    }

    /** 这回合能走到的所有格子，带剩余移动力；客户端移动覆盖层的等价物。 */
    fun reachableJson(gi: GameInfo, c: Civilization, unitRef: String?): String {
        val id = Kernel.idOf(unitRef, "unit")
        var unit: MapUnit? = null
        if (id != null) {
            for (m in c.units.getCivUnits()) { if (m.id == id) unit = m }
        }
        if (unit == null)
            return """{"error":{"code":"NOT_YOUR_UNIT","message":${quote("没有这个单位")}}}"""
        val p = unit.getTile().position
        val sb = StringBuilder("""{"unit":""").append(quote("unit-" + unit.id))
            .append(""","name":""").append(quote(unit.name))
            .append(""","from":[""").append(p.x).append(',').append(p.y).append(']')
            .append(""","moves":""").append(unit.currentMovement)
            .append(""","tiles":[""")
        var n = 0
        try {
            val paths = unit.movement.getDistanceToTiles()
            val keys = ArrayList(paths.keys)
            keys.sortWith { a, b -> paths[a]!!.totalMovement.compareTo(paths[b]!!.totalMovement) }
            for (t in keys) {
                if (t === unit.getTile()) continue
                if (n > 0) sb.append(',')
                n++
                sb.append("""{"x":""").append(t.position.x)
                    .append(""","y":""").append(t.position.y)
                    .append(""","cost":""").append(round2(paths[t]!!.totalMovement))
                    .append(""","terrain":""").append(quote(if (t.lastTerrain == null) "" else t.lastTerrain!!.name))
                if (t.isCityCenter() && t.getCity() != null)
                    sb.append(""","city":""").append(quote(t.getCity()!!.name))
                if (t.improvement != null)
                    sb.append(""","improvement":""").append(quote(t.improvement!!))
                sb.append('}')
            }
        } catch (ignored: Throwable) { }
        return sb.append("""],"count":""").append(n).append('}').toString()
    }

    /** 空生产在三个投影里必须同一个写法：null，不是 "Nothing" */
    fun productionJson(city: City): String {
        try {
            val n = city.cityConstructions.getCurrentConstruction().name
            if (n == null || n.isEmpty() || n == "Nothing") return "null"
            return quote(n)
        } catch (e: Throwable) {
            return "null"
        }
    }

    private fun round2(f: Float): String = (Math.round(f * 100f) / 100f).toString()

    /** 某个单位这回合能打的所有目标，附伤害区间；省得模型自己算六边形几何 */
    fun targetsJson(gi: GameInfo, c: Civilization, unitRef: String?): String {
        val id = Kernel.idOf(unitRef, "unit")
        var unit: MapUnit? = null
        if (id != null) {
            for (m in c.units.getCivUnits()) { if (m.id == id) unit = m }
        }
        if (unit == null) return """{"error":{"code":"NOT_YOUR_UNIT","message":${quote("没有这个单位")}}}"""

        val sb = StringBuilder("""{"unit":""").append(quote("unit-" + unit.id))
            .append(""","name":""").append(quote(unit.name))
            .append(""","range":""").append(unit.getRange())
            .append(""","targets":[""")
        var first = true
        try {
            val bd = com.unciv.logic.battle.BattleDamage
            val attacker = com.unciv.logic.battle.MapUnitCombatant(unit)
            val paths = unit.movement.getDistanceToTiles()
            val list = com.unciv.logic.battle.TargetHelper.getAttackableEnemies(unit, paths, null, false)
            val seen = LinkedHashSet<String>()
            for (at in list) {
                val t = at.tileToAttack
                val key = "${t.position.x},${t.position.y}"
                if (!seen.add(key)) continue
                val defender = at.combatant!!
                var dMin = 0
                var dMax = 0
                var aMin = 0
                var aMax = 0
                try {
                    dMin = bd.calculateDamageToDefender(attacker, defender, at.tileToAttackFrom, 0f)
                    dMax = bd.calculateDamageToDefender(attacker, defender, at.tileToAttackFrom, 1f)
                    aMin = bd.calculateDamageToAttacker(attacker, defender, at.tileToAttackFrom, 0f)
                    aMax = bd.calculateDamageToAttacker(attacker, defender, at.tileToAttackFrom, 1f)
                } catch (ignored: Throwable) { }
                if (!first) sb.append(',')
                first = false
                sb.append("""{"x":""").append(t.position.x)
                    .append(""","y":""").append(t.position.y)
                    .append(""","type":""").append(quote(if (t.isCityCenter()) "city" else "unit"))
                    .append(""","damageToDefender":{"min":""").append(dMin).append(""","max":""").append(dMax).append('}')
                    .append(""","damageToAttacker":{"min":""").append(aMin).append(""","max":""").append(aMax).append('}')
                    .append('}')
            }
        } catch (ignored: Throwable) { }
        return sb.append("]}").toString()
    }

    fun techsJson(gi: GameInfo, c: Civilization): String {
        val sb = StringBuilder("""{"researching":""")
        val cur = c.tech.currentTechnologyName()
        sb.append(if (cur == null || cur.isEmpty()) "null" else quote(cur))
        sb.append(""","sciencePerTurn":""").append(round1(c.stats.statsForNextTurn.science))
        sb.append(""","available":[""")
        var first = true
        try {
            for (name in gi.ruleset.technologies.keys) {
                if (!c.tech.canBeResearched(name)) continue
                if (!first) sb.append(',')
                first = false
                val cost = c.tech.costOfTech(name)
                var remaining = cost
                try {
                    remaining = Math.max(0, c.tech.remainingScienceToTech(name))
                } catch (ignored: Throwable) { }
                val perTurn = c.stats.statsForNextTurn.science.toDouble()
                sb.append("""{"name":""").append(quote(name))
                    .append(""","cost":""").append(cost)
                    .append(""","remaining":""").append(remaining)
                    .append(""","turns":""").append(if (perTurn <= 0.01) "null"
                        else Math.ceil(remaining / perTurn).toInt().toString())
                    .append('}')
            }
        } catch (ignored: Throwable) { }
        sb.append("""],"researched":[""")
        var firstDone = true
        try {
            val known = ArrayList(c.tech.techsResearched)
            java.util.Collections.sort(known)
            for (t in known) {
                if (!firstDone) sb.append(',')
                firstDone = false
                sb.append(quote(t))
            }
        } catch (ignored: Throwable) { }
        return sb.append("]}").toString()
    }

    fun religionJson(gi: GameInfo, c: Civilization): String {
        if (!gi.isReligionEnabled()) return """{"enabled":false}"""
        val rm = c.religionManager
        val rel = rm.religion

        val beliefs = StringBuilder()
        if (rel != null) {
            for (b in rel.getAllBeliefsOrdered()) {
                if (beliefs.length > 0) beliefs.append(',')
                beliefs.append("""{"name":""").append(quote(b.name))
                    .append(""","type":""").append(quote(b.type.name)).append('}')
            }
        }

        val used = java.util.HashSet<String>()
        for (r in gi.religions.values) {
            for (b in r.getAllBeliefsOrdered()) used.add(b.name)
        }
        val available = StringBuilder()
        for (e in gi.ruleset.beliefs.entries) {
            if (used.contains(e.key)) continue
            if (available.length > 0) available.append(',')
            available.append("""{"name":""").append(quote(e.key))
                .append(""","type":""").append(quote(e.value.type.name)).append('}')
        }

        var canPantheon = false
        try { canPantheon = rm.canFoundOrExpandPantheon() } catch (ignored: Throwable) { }
        val must = StringBuilder("{")
        try {
            val state = rm.religionState
            // 只有还没立过万神殿时才要求选万神殿信念；state==Pantheon 时引擎会拒绝，
            // 之前这里照报 Pantheon:1，和 choose_beliefs 的返回直接矛盾。
            if (state == com.unciv.logic.civilization.managers.ReligionState.None) {
                must.append(""""Pantheon":1""")
            } else {
                val cnt = if (state == com.unciv.logic.civilization.managers.ReligionState.EnhancingReligion)
                    rm.getBeliefsToChooseAtEnhancing() else rm.getBeliefsToChooseAtFounding()
                var first = true
                for (e in cnt.entries) {
                    if (e.value == null || e.value <= 0) continue
                    if (!first) must.append(',')
                    first = false
                    must.append(quote(e.key.name)).append(':').append(e.value)
                }
            }
        } catch (ignored: Throwable) { }
        must.append('}')

        return """{"enabled":true,"state":${quote(rm.religionState.name)}""" +
            ""","faith":${rm.storedFaith}""" +
            ""","faithForPantheon":${rm.faithForPantheon(0)}""" +
            ""","religion":${if (rel == null) "null" else quote(rel.displayName ?: rel.name)}""" +
            ""","beliefs":[$beliefs]""" +
            ""","availableBeliefs":[$available]""" +
            ""","canChoosePantheon":$canPantheon""" +
            ""","pantheonFaithNeeded":${rm.faithForPantheon(0)}""" +
            ""","mustChoose":$must}"""
    }

    fun cityStatesJson(gi: GameInfo, c: Civilization): String {
        val sb = StringBuilder("""{"cityStates":[""")
        var first = true
        for (cs in gi.civilizations) {
            if (!cs.isCityState || !c.knows(cs)) continue
            if (!first) sb.append(',')
            first = false

            val dm = cs.getDiplomacyManager(c)
            val influence = if (dm == null) 0 else dm.influence.toInt()
            val ally = cs.allyCiv

            sb.append("""{"civ":""").append(quote(cs.civName))
                .append(""","influence":""").append(influence)
                .append(""","ally":""").append(if (ally == null) "null" else quote(ally.civName))
                .append(""","atWar":""").append(c.isAtWarWith(cs))
                .append(""","canPledgeProtection":""").append(cs.cityStateFunctions.otherCivCanPledgeProtection(c))
                .append(""","giftInfluence":[""")
            val tiers = intArrayOf(250, 500, 1000)
            for (i in tiers.indices) {
                if (i > 0) sb.append(',')
                val gain = cs.cityStateFunctions.influenceGainedByGift(c, tiers[i])
                sb.append("""{"gold":""").append(tiers[i]).append(""","influence":""").append(gain).append('}')
            }
            sb.append("""],"quests":[""")
            var firstQuest = true
            try {
                for (aq in cs.questManager.getAssignedQuestsFor(c)) {
                    if (!firstQuest) sb.append(',')
                    firstQuest = false
                    sb.append("""{"name":""").append(quote(aq.questName))
                        .append(""","description":""").append(quote(aq.getDescription()))
                        .append(""","influence":""").append(aq.getInfluence().toInt())
                        .append(""","turnsLeft":""").append(if (aq.doesExpire()) aq.getRemainingTurns() else -1)
                        .append('}')
                }
            } catch (ignored: Throwable) { }
            sb.append("]}")
        }
        return sb.append("]}").toString()
    }

    fun spiesJson(gi: GameInfo, c: Civilization): String {
        val sb = StringBuilder("""{"spies":[""")
        var first = true
        for (s in c.espionageManager.spyList) {
            if (!first) sb.append(',')
            first = false
            val loc = s.getCityOrNull()
            sb.append("""{"name":""").append(quote(s.name))
                .append(""","rank":""").append(s.rank)
                .append(""","action":""").append(quote(s.action.name))
                .append(""","turnsRemaining":""").append(s.turnsRemainingForAction)
                .append(""","city":""").append(if (loc == null) "null" else quote(loc.name))
                .append(""","cityId":""").append(if (loc == null) "null" else quote("city-" + loc.id))
                .append(""","setUp":""").append(s.isSetUp()).append('}')
        }
        return sb.append("]}").toString()
    }

    private fun findUnit(gi: GameInfo, c: Civilization, id: Int): MapUnit? {
        for (u in c.units.getCivUnits()) {
            if (u.id == id) return u
        }
        return null
    }

    private fun actionsJson(gi: GameInfo, c: Civilization, unitId: Int): String {
        val unit = findUnit(gi, c, unitId) ?: return err("没有这个单位: $unitId")
        val sb = StringBuilder("""{"unit":""").append(quote("unit-$unitId")).append(""","actions":[""")
        var first = true
        var truncated: String? = null
        try {
            for (a in UnitActions.getUnitActions(unit)) {
                if (!first) sb.append(',')
                first = false
                sb.append("""{"type":""").append(quote(a.type.name))
                    .append(""","title":""").append(quote(a.title))
                    .append(""","available":""").append(a.action != null)
                    .append('}')
            }
        } catch (t: Throwable) {
            truncated = t.javaClass.simpleName
        }
        // 引擎的 UnitActions 不含 UI 专用动作，这里补上内核真正实现的那两个
        try {
            if (unit.cache.hasUniqueToBuildImprovements) {
                sb.append(""",{"type":""").append(quote("build_improvement"))
                    .append(""","title":""").append(quote("造设施"))
                    .append(""","available":""").append(unit.hasMovement())
                    .append('}')
            }
            sb.append(""",{"type":""").append(quote("disband_unit"))
                .append(""","title":""").append(quote("解散单位"))
                .append(""","available":true""")
                .append('}')
        } catch (ignored: Throwable) { }
        sb.append("""],"truncated":""").append(if (truncated == null) "null" else quote(truncated)).append('}')
        return sb.toString()
    }

    // ---------------- 新局 ----------------

    private fun gi_nations(): Collection<com.unciv.models.ruleset.nation.Nation> {
        try {
            return RulesetCache.getVanillaRuleset().nations.values
        } catch (ignored: Throwable) { }
        return emptyList()
    }

    private fun gi_eras(gp: GameParameters): List<String> {
        val out = ArrayList<String>()
        try {
            for (e in UncivGame.Current.gameInfo!!.ruleset.eras.values)
                out.add(e.name)
        } catch (ignored: Throwable) { }
        if (out.isEmpty()) {
            for (s in arrayOf("Ancient era", "Classical era", "Medieval era", "Renaissance era",
                    "Industrial era", "Modern era", "Atomic era", "Information era", "Future era")) out.add(s)
        }
        return out
    }

    private fun newGameJson(players: Int, humans: Int, difficulty: String?, speed: String?, civ: String?, seed: Long, era: String?, size: String?): String {
        val gp = GameParameters()
        if (difficulty != null) gp.difficulty = difficulty
        if (speed != null) gp.speed = speed
        if (era != null) {
            var match: String? = null
            for (name in gi_eras(gp)) if (name.equals(era, ignoreCase = true)) match = name
            if (match == null) return """{"error":{"code":"UNKNOWN_ID","message":${quote("没有这个时代: $era")}}}"""
            gp.startingEra = match
        }
        gp.numberOfCityStates = Math.max(0, players - 1)
        val ps = ArrayList<Player>()
        var humanNation = civ
        if (humanNation == null || humanNation.equals("Random", ignoreCase = true)) {
            // 同一个 seed 必须给同一个文明，否则复现不了
            val majors = ArrayList<String>()
            try {
                for (n in gi_nations()) {
                    if (!n.isCityState && !n.isBarbarian && !n.isSpectator) majors.add(n.name)
                }
            } catch (ignored: Throwable) { }
            if (majors.isEmpty()) humanNation = "Random"
            else {
                java.util.Collections.sort(majors)
                val s2 = if (seed == 0L) 1L else Math.abs(seed)
                humanNation = majors[(s2 % majors.size).toInt()]
            }
        }
        ps.add(Player(humanNation!!, PlayerType.Human, "agent0"))
        for (i in 1 until players) {
            val isHuman = i < humans
            ps.add(Player("Random", if (isHuman) PlayerType.Human else PlayerType.AI,
                (if (isHuman) "agent" else "ai") + i))
        }
        gp.players = ps

        val mp = MapParameters()
        var preset = MapSize.Predefined.Small
        if (size != null) {
            for (p in MapSize.Predefined.values())
                if (p.name.equals(size, ignoreCase = true)) preset = p
        }
        mp.mapSize = MapSize(preset)
        if (seed != 0L) mp.seed = seed

        val gi = GameStarter.startNewGame(GameSetupInfo(gp, mp))
        // 默认把所有胜利条件打开（GameParameters 默认是空列表，等于全关）
        try {
            gi.gameParameters.victoryTypes.clear()
            gi.gameParameters.victoryTypes.addAll(gi.ruleset.victories.keys)
        } catch (ignored: Throwable) { }
        gameInfo = gi
        game.gameInfo = gi
        submitted.clear()
        tokenToCiv.clear()   // 旧 token 不能跨局复用
        // 第一回合开始前 stats 还是空的，算一次让 /state /techs 一开始就有数
        try {
            gi.currentPlayerCiv.updateStatsForNextTurn()
        } catch (ignored: Throwable) { }
        return stateJson(gi, gi.currentPlayerCiv)
    }
}
