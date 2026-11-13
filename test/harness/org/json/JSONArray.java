package org.json;

import java.util.List;

/** 最小 JSONArray 替身，只实现 App 用到的 optJSONObject / length。 */
public class JSONArray {

    private final List<Object> list;

    JSONArray(List<Object> l) {
        this.list = l;
    }

    public int length() {
        return list.size();
    }

    public int optInt(int i, int def) {
        if (i < 0 || i >= list.size()) {
            return def;
        }
        Object o = list.get(i);
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

    public JSONObject optJSONObject(int i) {
        if (i < 0 || i >= list.size()) {
            return null;
        }
        Object o = list.get(i);
        if (o instanceof java.util.Map) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> m = (java.util.Map<String, Object>) o;
            return new JSONObject(m);
        }
        return null;
    }
}
