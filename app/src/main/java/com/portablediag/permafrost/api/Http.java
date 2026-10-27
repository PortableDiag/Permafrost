package com.portablediag.permafrost.api;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Just enough HTTP/1.1 to serve a local API, and no more: a request line,
 * headers, a {@code Content-Length} body. A web-server dependency would be
 * larger than the feature and break the no-third-party rule. No Android types,
 * so parsing is JVM-testable.
 */
public final class Http {

    /** Nothing this API accepts is a megabyte of JSON. */
    public static final int MAX_BODY = 1 << 20;

    /** A request line longer than this is not a request, it is someone probing. */
    private static final int MAX_LINE = 8 * 1024;

    private Http() {}

    /**
     * Reads one request. Null at a clean end of stream (a port scanner that
     * connected and left); {@link ApiException} for something that arrived but
     * is not a request this server can answer.
     */
    public static ApiRequest parse(InputStream in) throws IOException {
        String requestLine = readLine(in);
        if (requestLine == null || requestLine.trim().isEmpty()) return null;

        String[] parts = requestLine.split(" ");
        if (parts.length < 2) throw new ApiException(400, "malformed request line");
        String method = parts[0].toUpperCase(Locale.ROOT);
        String target = parts[1];

        Map<String, String> headers = new HashMap<>();
        while (true) {
            String line = readLine(in);
            if (line == null || line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    line.substring(colon + 1).trim());
        }

        int length = 0;
        try {
            String cl = headers.get("content-length");
            if (cl != null) length = Integer.parseInt(cl.trim());
        } catch (NumberFormatException ignored) {
        }
        if (length > MAX_BODY) throw new ApiException(413, "body larger than " + MAX_BODY + " bytes");
        byte[] body = length > 0 ? readFully(in, length) : new byte[0];

        int q = target.indexOf('?');
        String rawPath = q < 0 ? target : target.substring(0, q);
        String rawQuery = q < 0 ? "" : target.substring(q + 1);
        String path = decode(rawPath);
        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return new ApiRequest(method, path, parseQuery(rawQuery), headers, body);
    }

    public static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(decodeQuery(name), decodeQuery(value));
        }
        return out;
    }

    /** A {@code +} is a literal in a path... */
    static String decode(String raw) {
        try {
            return URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return raw;
        }
    }

    /** ...but a space in a query string. */
    static String decodeQuery(String raw) {
        try {
            return URLDecoder.decode(raw, "UTF-8");
        } catch (Exception e) {
            return raw;
        }
    }

    /** One CRLF-terminated line; a bare LF is accepted too, for hand-rolled clients. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b == -1) return sb.length() == 0 ? null : sb.toString();
            if (b == '\n') {
                int n = sb.length();
                if (n > 0 && sb.charAt(n - 1) == '\r') sb.setLength(n - 1);
                return sb.toString();
            }
            sb.append((char) b);
            if (sb.length() > MAX_LINE) throw new ApiException(413, "header line too long");
        }
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] out = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(out, read, length - read);
            if (n < 0) break;
            read += n;
        }
        if (read == length) return out;
        byte[] cut = new byte[read];
        System.arraycopy(out, 0, cut, 0, read);
        return cut;
    }
}
