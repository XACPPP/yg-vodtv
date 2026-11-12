package org.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仅用于在桌面 JVM 上运行 Api.java / Vod.java 的最小 JSON 替身。
 * 只实现 App 实际用到的几个方法，语义与 Android 的 org.json 对齐
 * （opt* 在键缺失或类型不符时返回默认值，不抛异常）。
 */
public class JSONObject {

    private final Map<String, Object> map = new LinkedHashMap<String, Object>();

    public JSONObject(String text) throws JSONException {
        Object o = new P(text).parseValue();
        if (!(o instanceof Map)) {
            throw new JSONException("root is not object: " + head(text));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) o;
        map.putAll(m);
    }

    JSONObject(Map<String, Object> m) {
        map.putAll(m);
    }

    public int optInt(String key, int def) {
        Object o = map.get(key);
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        if (o instanceof String) {
            try {
                return Integer.parseInt((String) o);
            } catch (Throwable t) {
                return def;
            }
        }
        return def;
    }

    public String optString(String key, String def) {
        Object o = map.get(key);
        if (o == null) {
            return def;
        }
        return o instanceof String ? (String) o : String.valueOf(o);
    }

    public long optLong(String key, long def) {
        Object o = map.get(key);
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        if (o instanceof String) {
            try {
                return Long.parseLong((String) o);
            } catch (Throwable t) {
                return def;
            }
        }
        return def;
    }

    public JSONArray optJSONArray(String key) {
        Object o = map.get(key);
        if (o instanceof List) {
            return new JSONArray((List<Object>) o);
        }
        return null;
    }

    private static String head(String s) {
        if (s == null) {
            return "null";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() > 60 ? t.substring(0, 60) : t;
    }

    /** 递归下降解析，够用即可。 */
    private static final class P {
        private final String s;
        private int i;

        P(String s) {
            this.s = s == null ? "" : s;
        }

        Object parseValue() throws JSONException {
            ws();
            if (i >= s.length()) {
                throw new JSONException("unexpected end");
            }
            char c = s.charAt(i);
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '"') {
                return parseString();
            }
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            return parseNumber();
        }

        private Map<String, Object> parseObject() throws JSONException {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = parseString();
                ws();
                expect(':');
                Object v = parseValue();
                m.put(k, v);
                ws();
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                expect('}');
                return m;
            }
        }

        private List<Object> parseArray() throws JSONException {
            List<Object> a = new ArrayList<Object>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return a;
            }
            while (true) {
                a.add(parseValue());
                ws();
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                expect(']');
                return a;
            }
        }

        private String parseString() throws JSONException {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i >= s.length()) {
                    break;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'u':
                        if (i + 4 <= s.length()) {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        break;
                    default:
                        sb.append(e);
                }
            }
            throw new JSONException("unterminated string");
        }

        private Object parseNumber() throws JSONException {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E'
                        || (c >= '0' && c <= '9')) {
                    i++;
                } else {
                    break;
                }
            }
            String n = s.substring(start, i);
            if (n.length() == 0) {
                throw new JSONException("bad number at " + i);
            }
            if (n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0) {
                return Long.parseLong(n);
            }
            return Double.parseDouble(n);
        }

        private void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\uFEFF') {
                    i++;
                } else {
                    break;
                }
            }
        }

        private void expect(char c) throws JSONException {
            if (i >= s.length() || s.charAt(i) != c) {
                throw new JSONException("expect '" + c + "' at " + i);
            }
            i++;
        }
    }
}
