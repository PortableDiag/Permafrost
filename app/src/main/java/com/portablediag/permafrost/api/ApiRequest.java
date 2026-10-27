package com.portablediag.permafrost.api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A parsed request. {@link #path} is percent-decoded; header names are lower-cased. */
public class ApiRequest {
    public final String method;
    public final String path;
    public final Map<String, String> query;
    private final Map<String, String> headers;
    public final byte[] body;

    public ApiRequest(String method, String path, Map<String, String> query,
                      Map<String, String> headers, byte[] body) {
        this.method = method;
        this.path = path;
        this.query = query == null ? Collections.emptyMap() : query;
        this.headers = headers == null ? Collections.emptyMap() : headers;
        this.body = body == null ? new byte[0] : body;
    }

    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    /** The JSON body, or an empty object — a body-less POST is a legitimate "do it with defaults". */
    public JSONObject json() {
        String text = new String(body, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            throw new ApiException(400, "body is not a JSON object: " + e.getMessage());
        }
    }

    /**
     * Package names from the body, accepting every spelling a caller might
     * reasonably reach for: {@code {"packages":[..]}}, {@code {"package":".."}},
     * or a bare {@code [..]}.
     */
    public List<String> packages() {
        List<String> out = new ArrayList<>();
        String text = new String(body, StandardCharsets.UTF_8).trim();
        JSONArray arr = null;
        if (text.startsWith("[")) {
            try {
                arr = new JSONArray(text);
            } catch (Exception e) {
                throw new ApiException(400, "body is not a JSON array: " + e.getMessage());
            }
        } else {
            JSONObject o = json();
            arr = o.optJSONArray("packages");
            if (arr == null && o.has("package")) {
                out.add(o.optString("package").trim());
            }
        }
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "").trim();
                if (!s.isEmpty()) out.add(s);
            }
        }
        return out;
    }
}
