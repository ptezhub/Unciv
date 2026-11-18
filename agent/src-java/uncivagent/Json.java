package uncivagent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 极简 JSON 解析器。够用就行：对象/数组/字符串/数字/布尔/null，不追求流式与性能。 */
public final class Json {

    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i < p.s.length()) throw new IllegalArgumentException("尾部有多余内容: " + p.s.substring(p.i));
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<>();
    }

    public static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof String ? (String) v : null;
    }

    public static Integer integer(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt((String) v); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private Object value() {
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private void expect(String lit) {
        if (!s.startsWith(lit, i)) throw new IllegalArgumentException("位置 " + i + " 期望 " + lit);
        i += lit.length();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; // {
        ws();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            String k = s.charAt(i) == '"' ? string() : bareKey();
            ws();
            if (s.charAt(i) != ':') throw new IllegalArgumentException("位置 " + i + " 期望冒号");
            i++;
            ws();
            m.put(k, value());
            ws();
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw new IllegalArgumentException("位置 " + (i - 1) + " 期望逗号或右花括号");
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++; // [
        ws();
        if (s.charAt(i) == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw new IllegalArgumentException("位置 " + (i - 1) + " 期望逗号或右方括号");
        }
    }

    /** Unciv 的 jsons 允许不加引号的键，这里照读 */
    private String bareKey() {
        int start = i;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.') i++;
            else break;
        }
        if (i == start) throw new IllegalArgumentException("位置 " + start + " 期望键名");
        return s.substring(start, i);
    }

    /** 去掉 // 行注释（Unciv 的 json 里到处都是），字符串里的 // 不动 */
    public static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inString = false;
        for (int k = 0; k < text.length(); k++) {
            char c = text.charAt(k);
            if (inString) {
                out.append(c);
                if (c == '\\' && k + 1 < text.length()) { out.append(text.charAt(++k)); continue; }
                if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; out.append(c); continue; }
            if (c == '/' && k + 1 < text.length() && text.charAt(k + 1) == '/') {
                while (k < text.length() && text.charAt(k) != '\n') k++;
                out.append('\n');
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** 宽容解析：先去注释，再按 Unciv 的裸键规则读 */
    public static Object parseRelaxed(String text) {
        return parse(stripComments(text));
    }

    private String string() {
        if (s.charAt(i) != '"') throw new IllegalArgumentException("位置 " + i + " 期望字符串");
        i++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: throw new IllegalArgumentException("未知转义 \\" + e);
                }
            } else sb.append(c);
        }
    }

    private Object number() {
        int start = i;
        while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
        String n = s.substring(start, i);
        if (n.isEmpty()) throw new IllegalArgumentException("位置 " + start + " 不是数字");
        return Double.valueOf(n);
    }

    // ---------- 序列化（只给 MCP 参数回填用） ----------

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        writeTo(sb, o);
        return sb.toString();
    }

    private static void writeTo(StringBuilder sb, Object o) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof String) { quoteTo(sb, (String) o); return; }
        if (o instanceof Boolean) { sb.append(o); return; }
        if (o instanceof Number) {
            double d = ((Number) o).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) sb.append((long) d);
            else sb.append(d);
            return;
        }
        if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quoteTo(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeTo(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (o instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object v : (Iterable<?>) o) {
                if (!first) sb.append(',');
                first = false;
                writeTo(sb, v);
            }
            sb.append(']');
            return;
        }
        quoteTo(sb, String.valueOf(o));
    }

    private static void quoteTo(StringBuilder sb, String s) {
        sb.append('"');
        for (int k = 0; k < s.length(); k++) {
            char ch = s.charAt(k);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        sb.append('"');
    }
}
