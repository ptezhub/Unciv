@file:JvmName("Json")

package uncivagent

/**
 * 极简 JSON 解析器。够用就行：对象/数组/字符串/数字/布尔/null，不追求流式与性能。
 *
 * 用 @file:JvmName("Json") 保持 Java 侧的调用点（`Json.str(m, "k")`）不用改，
 * 这样可以从 Java 逐步搬到 Kotlin，而不是一次性全换。
 */
private class JsonParser(private val s: String) {
    private var i = 0

    fun parse(): Any? {
        ws()
        val v = value()
        ws()
        require(i >= s.length) { "尾部有多余内容: ${s.substring(i)}" }
        return v
    }

    private fun ws() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun value(): Any? = when (s[i]) {
        '{' -> obj()
        '[' -> arr()
        '"' -> string()
        't' -> { expect("true"); true }
        'f' -> { expect("false"); false }
        'n' -> { expect("null"); null }
        else -> number()
    }

    private fun expect(lit: String) {
        require(s.startsWith(lit, i)) { "位置 $i 期望 $lit" }
        i += lit.length
    }

    private fun obj(): MutableMap<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        i++ // {
        ws()
        if (s[i] == '}') { i++; return m }
        while (true) {
            ws()
            val k = if (s[i] == '"') string() else bareKey()
            ws()
            require(s[i] == ':') { "位置 $i 期望冒号" }
            i++
            ws()
            m[k] = value()
            ws()
            when (s[i++]) {
                '}' -> return m
                ',' -> {}
                else -> throw IllegalArgumentException("位置 ${i - 1} 期望逗号或右花括号")
            }
        }
    }

    private fun arr(): MutableList<Any?> {
        val l = ArrayList<Any?>()
        i++ // [
        ws()
        if (s[i] == ']') { i++; return l }
        while (true) {
            ws()
            l.add(value())
            ws()
            when (s[i++]) {
                ']' -> return l
                ',' -> {}
                else -> throw IllegalArgumentException("位置 ${i - 1} 期望逗号或右方括号")
            }
        }
    }

    /** Unciv 的 jsons 允许不加引号的键，这里照读 */
    private fun bareKey(): String {
        val start = i
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] in "_-.")) i++
        require(i > start) { "位置 $start 期望键名" }
        return s.substring(start, i)
    }

    private fun string(): String {
        require(s[i] == '"') { "位置 $i 期望字符串" }
        i++
        val sb = StringBuilder()
        while (true) {
            when (val c = s[i++]) {
                '"' -> return sb.toString()
                '\\' -> when (val e = s[i++]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        sb.append(s.substring(i, i + 4).toInt(16).toChar())
                        i += 4
                    }
                    else -> throw IllegalArgumentException("未知转义 \\$e")
                }
                else -> sb.append(c)
            }
        }
    }

    private fun number(): Any {
        val start = i
        while (i < s.length && s[i] in "-+.eE0123456789") i++
        val n = s.substring(start, i)
        require(n.isNotEmpty()) { "位置 $start 不是数字" }
        return n.toDouble()
    }
}

fun parse(text: String): Any? = JsonParser(text).parse()

@Suppress("UNCHECKED_CAST")
fun obj(o: Any?): MutableMap<String, Any?> =
    if (o is Map<*, *>) o as MutableMap<String, Any?> else LinkedHashMap()

@Suppress("UNCHECKED_CAST")
fun arr(o: Any?): MutableList<Any?> =
    if (o is List<*>) o as MutableList<Any?> else ArrayList()

fun str(m: Map<String, Any?>, k: String): String? = m[k] as? String

fun integer(m: Map<String, Any?>, k: String): Int? = when (val v = m[k]) {
    is Number -> v.toInt()
    is String -> v.toIntOrNull()
    else -> null
}

/** 去掉 // 行注释（Unciv 的 json 里到处都是），字符串里的 // 不动 */
fun stripComments(text: String): String {
    val out = StringBuilder(text.length)
    var inString = false
    var k = 0
    while (k < text.length) {
        val c = text[k]
        if (inString) {
            out.append(c)
            if (c == '\\' && k + 1 < text.length) { out.append(text[++k]); k++; continue }
            if (c == '"') inString = false
            k++
            continue
        }
        if (c == '"') { inString = true; out.append(c); k++; continue }
        if (c == '/' && k + 1 < text.length && text[k + 1] == '/') {
            while (k < text.length && text[k] != '\n') k++
            out.append('\n')
            continue
        }
        out.append(c)
        k++
    }
    return out.toString()
}

/** 宽容解析：先去注释，再按 Unciv 的裸键规则读 */
fun parseRelaxed(text: String): Any? = parse(stripComments(text))

// ---------- 序列化（只给 MCP 参数回填用） ----------

fun write(o: Any?): String = StringBuilder().also { writeTo(it, o) }.toString()

private fun writeTo(sb: StringBuilder, o: Any?) {
    when (o) {
        null -> sb.append("null")
        is String -> quoteTo(sb, o)
        is Boolean -> sb.append(o)
        is Number -> {
            val d = o.toDouble()
            if (d == Math.rint(d) && !d.isInfinite()) sb.append(d.toLong()) else sb.append(d)
        }
        is Map<*, *> -> {
            sb.append('{')
            var first = true
            for ((k, v) in o) {
                if (!first) sb.append(',')
                first = false
                quoteTo(sb, k.toString())
                sb.append(':')
                writeTo(sb, v)
            }
            sb.append('}')
        }
        is Iterable<*> -> {
            sb.append('[')
            var first = true
            for (v in o) {
                if (!first) sb.append(',')
                first = false
                writeTo(sb, v)
            }
            sb.append(']')
        }
        else -> quoteTo(sb, o.toString())
    }
}

private fun quoteTo(sb: StringBuilder, s: String) {
    sb.append('"')
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
        }
    }
    sb.append('"')
}
