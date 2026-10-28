package com.portablediag.permafrost.api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** One response, held in memory — nothing this API returns is large. */
public class ApiResponse {

    private static final Map<Integer, String> REASONS = new HashMap<>();

    static {
        REASONS.put(200, "OK");
        REASONS.put(201, "Created");
        REASONS.put(204, "No Content");
        REASONS.put(400, "Bad Request");
        REASONS.put(401, "Unauthorized");
        REASONS.put(403, "Forbidden");
        REASONS.put(404, "Not Found");
        REASONS.put(405, "Method Not Allowed");
        REASONS.put(409, "Conflict");
        REASONS.put(413, "Payload Too Large");
        REASONS.put(500, "Internal Server Error");
        REASONS.put(503, "Service Unavailable");
    }

    private static final String JSON = "application/json; charset=utf-8";

    public final int status;
    public final String contentType;
    public final byte[] body;
    public final Map<String, String> extraHeaders;

    public ApiResponse(int status, String contentType, byte[] body, Map<String, String> extraHeaders) {
        this.status = status;
        this.contentType = contentType;
        this.body = body == null ? new byte[0] : body;
        this.extraHeaders = extraHeaders == null ? Collections.emptyMap() : extraHeaders;
    }

    /** The same response with one more header. */
    public ApiResponse withHeader(String name, String value) {
        Map<String, String> h = new LinkedHashMap<>(extraHeaders);
        h.put(name, value);
        return new ApiResponse(status, contentType, body, h);
    }

    public String reason() {
        String r = REASONS.get(status);
        return r == null ? "OK" : r;
    }

    public static ApiResponse json(JSONObject o) {
        return json(o, 200);
    }

    public static ApiResponse json(JSONObject o, int status) {
        return new ApiResponse(status, JSON, utf8(indent(o)), null);
    }

    public static ApiResponse json(JSONArray a) {
        String s;
        try {
            s = a.toString(2);
        } catch (Exception e) {
            s = a.toString();
        }
        return new ApiResponse(200, JSON, utf8(s), null);
    }

    public static ApiResponse text(String body, String type) {
        return new ApiResponse(200, type, utf8(body), null);
    }

    public static ApiResponse bytes(byte[] body, String type) {
        return new ApiResponse(200, type, body, null);
    }

    public static ApiResponse error(int status, String message, String hint) {
        JSONObject o = new JSONObject();
        try {
            o.put("error", message);
            if (hint != null) o.put("hint", hint);
        } catch (Exception ignored) {
        }
        return json(o, status);
    }

    public static ApiResponse noContent() {
        return new ApiResponse(204, "text/plain", new byte[0], null);
    }

    private static String indent(JSONObject o) {
        try {
            return o.toString(2);
        } catch (Exception e) {
            return o.toString();
        }
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
