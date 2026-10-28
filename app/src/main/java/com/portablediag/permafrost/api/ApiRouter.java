package com.portablediag.permafrost.api;

import com.portablediag.permafrost.model.ManagedApp;
import com.portablediag.permafrost.model.Mode;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The API's rules: which path means what, what each one accepts, and which
 * failures are the caller's fault. No Android types, so the whole surface can
 * run against a fake {@link ApiBackend}.
 *
 * <p>Two conventions run through all of it, as in Lull:
 * <ul>
 *   <li><b>Every endpoint is something the UI can already do.</b> The API is a
 *       second set of hands, not a second set of features.</li>
 *   <li><b>A wrong request gets a status and a sentence</b>, never a stack trace
 *       and never a silent 200.</li>
 * </ul>
 */
public class ApiRouter {

    /** Bumped when a response shape changes in a way a caller could notice. */
    public static final int API_VERSION = 1;

    private static final List<String> DARK_MODES = Arrays.asList("system", "light", "dark");

    private static final String NO_ROOT_HINT =
            "grant Permafrost root in your su manager (Magisk: Superuser tab), then retry";

    private final ApiBackend backend;
    private final Supplier<String> expectedKey;
    private final Supplier<PhoneApi.Grant> phoneApi;

    /** Who a request's key belongs to. */
    private enum Caller { NOBODY, OWNER, PHONEAPI }

    public ApiRouter(ApiBackend backend, Supplier<String> expectedKey) {
        this(backend, expectedKey, () -> null);
    }

    public ApiRouter(ApiBackend backend, Supplier<String> expectedKey, Supplier<PhoneApi.Grant> phoneApi) {
        this.backend = backend;
        this.expectedKey = expectedKey;
        this.phoneApi = phoneApi;
    }

    /** Every answer, errors and 401s too, carries PhoneAPI's proof once it holds a proof key. */
    public ApiResponse handle(ApiRequest r) {
        ApiResponse out = route(r);
        String challenge = PhoneApi.usableChallenge(r.header(PhoneApi.HEADER_CHALLENGE));
        PhoneApi.Grant g = challenge == null ? null : phoneApi.get();
        if (g == null) return out;
        return out.withHeader(PhoneApi.HEADER_PROOF, PhoneApi.proof(g.proofKey, challenge));
    }

    private ApiResponse route(ApiRequest r) {
        try {
            if (r.method.equals("OPTIONS")) return ApiResponse.noContent();
            if (!r.path.equals("/api") && !r.path.startsWith("/api/")) {
                return ApiResponse.error(404, "not an API path",
                        "everything this server answers lives under /api — GET /api/docs lists it");
            }
            // Liveness answers without a key, so a watchdog needs no credential.
            if (r.path.equals("/api/health")) return get(r, this::health);
            Caller who = caller(r);
            if (who == Caller.NOBODY) {
                return ApiResponse.error(401, "missing or invalid API key",
                        "send it as the X-API-Key header, or Authorization: Bearer <key>");
            }
            if (who == Caller.PHONEAPI) {
                PhoneApi.Grant g = phoneApi.get();
                boolean reading = r.method.equals("GET");
                if (g == null || (reading ? !g.see : !g.change)) {
                    return ApiResponse.error(403, "PhoneAPI's key may not "
                                    + (reading ? "read from" : "change anything in") + " Permafrost",
                            "the owner chose what it may do; Get a key in PhoneAPI (Providers, Permafrost) asks again");
                }
            }
            return dispatch(r);
        } catch (ApiException e) {
            return ApiResponse.error(e.status, e.getMessage(), e.hint);
        } catch (Exception e) {
            return ApiResponse.error(500, e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        }
    }

    private Caller caller(ApiRequest r) {
        String offered = offeredKey(r);
        if (offered == null) return Caller.NOBODY;
        if (same(offered, expectedKey.get())) return Caller.OWNER;
        PhoneApi.Grant g = phoneApi.get();
        if (g != null && same(offered, g.key)) return Caller.PHONEAPI;
        return Caller.NOBODY;
    }

    private static String offeredKey(ApiRequest r) {
        String offered = r.header("x-api-key");
        if (offered == null) {
            String auth = r.header("authorization");
            if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
                offered = auth.substring(7).trim();
            }
        }
        return offered;
    }

    /** Constant-time, so the timing of a compare can't leak how much of a key is right. */
    private static boolean same(String offered, String expected) {
        if (expected == null || expected.isEmpty()) return false;
        byte[] a = offered.getBytes(StandardCharsets.UTF_8);
        byte[] b = expected.getBytes(StandardCharsets.UTF_8);
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }

    // ---------------- Dispatch ----------------

    private ApiResponse dispatch(ApiRequest r) throws JSONException {
        List<String> seg = new ArrayList<>();
        for (String s : r.path.substring(4).split("/")) if (!s.isEmpty()) seg.add(s);
        if (seg.isEmpty()) return get(r, this::index);

        switch (seg.get(0)) {
            case "docs":
                return get(r, () -> ApiResponse.text(ApiDocs.MARKDOWN, "text/markdown; charset=utf-8"));
            case "instance":
                return get(r, this::instance);
            case "apps":
                return apps(r, seg.subList(1, seg.size()));
            case "installed":
                return get(r, () -> installed(r));
            case "settings":
                return settings(r);
            case "palettes":
                return get(r, this::palettes);
            case "crash":
                return get(r, this::crash);
            default:
                return notFound(r);
        }
    }

    private interface Handler {
        ApiResponse run() throws JSONException;
    }

    private ApiResponse get(ApiRequest r, Handler h) throws JSONException {
        return r.method.equals("GET") ? h.run() : methodNotAllowed(r, "GET");
    }

    private ApiResponse post(ApiRequest r, Handler h) throws JSONException {
        return r.method.equals("POST") ? h.run() : methodNotAllowed(r, "POST");
    }

    private static ApiResponse methodNotAllowed(ApiRequest r, String allowed) {
        JSONObject o = new JSONObject();
        try {
            o.put("error", r.method + " not allowed on " + r.path).put("allow", allowed);
        } catch (JSONException ignored) {
        }
        ApiResponse base = ApiResponse.json(o, 405);
        return new ApiResponse(405, base.contentType, base.body,
                Collections.singletonMap("Allow", allowed));
    }

    private static ApiResponse notFound(ApiRequest r) {
        return ApiResponse.error(404, "no route for " + r.method + " " + r.path,
                "GET /api/docs lists every endpoint");
    }

    // ---------------- About ----------------

    private ApiResponse health() throws JSONException {
        return ApiResponse.json(new JSONObject()
                .put("ok", true).put("app", "Permafrost").put("api", API_VERSION));
    }

    private ApiResponse index() throws JSONException {
        return ApiResponse.json(new JSONObject()
                .put("app", "Permafrost")
                .put("api", API_VERSION)
                .put("docs", "/api/docs")
                .put("endpoints", new JSONArray(Arrays.asList(ApiDocs.SUMMARY))));
    }

    private ApiResponse instance() throws JSONException {
        return ApiResponse.json(new JSONObject()
                .put("app", "Permafrost")
                .put("package", backend.selfPackage())
                .put("version", backend.versionName())
                .put("version_code", backend.versionCode())
                .put("api", API_VERSION)
                .put("sdk", backend.sdk())
                .put("root", backend.rootAvailable())
                .put("usage_access", backend.hasUsageAccess())
                .put("managed", backend.managed().size()));
    }

    // ---------------- Apps ----------------

    private ApiResponse apps(ApiRequest r, List<String> rest) throws JSONException {
        if (rest.isEmpty()) {
            switch (r.method) {
                case "GET": return listApps();
                case "POST": return addApps(r);
                default: return methodNotAllowed(r, "GET, POST");
            }
        }
        String pkg = rest.get(0);
        if (rest.size() == 1) {
            switch (r.method) {
                case "GET": return ApiResponse.json(appJson(requireManaged(pkg), live(requireManaged(pkg))));
                case "PATCH": return patchApp(r, requireManaged(pkg));
                case "DELETE": return removeApp(requireManaged(pkg));
                default: return methodNotAllowed(r, "GET, PATCH, DELETE");
            }
        }
        if (rest.size() != 2) return notFound(r);
        String action = rest.get(1);
        if (action.equals("icon")) return get(r, () -> icon(pkg));
        return post(r, () -> {
            ManagedApp app = requireManaged(pkg);
            switch (action) {
                case "freeze": return act(app, backend.freeze(requireRoot(app)), null);
                case "wake": return act(app, backend.wake(requireRoot(app)), null);
                case "launch": return launch(app);
                case "unlock-update": return act(app, backend.unlockForUpdate(requireRoot(app)), null);
                case "frost-icon": return frostIcon(app);
                default: return notFound(r);
            }
        });
    }

    private ApiResponse listApps() throws JSONException {
        List<ManagedApp> all = backend.managed();
        Map<String, Boolean> dormant = backend.dormantOnDevice(all);
        JSONArray arr = new JSONArray();
        for (ManagedApp a : all) arr.put(appJson(a, dormant.get(a.packageName)));
        return ApiResponse.json(new JSONObject()
                .put("root", backend.rootAvailable())
                .put("apps", arr));
    }

    private ApiResponse addApps(ApiRequest r) throws JSONException {
        List<String> pkgs = r.packages();
        if (pkgs.isEmpty()) {
            throw new ApiException(400, "no packages given",
                    "send {\"packages\":[\"com.example\"]}, {\"package\":\"com.example\"} or a bare array; "
                            + "GET /api/installed lists what can be added");
        }
        JSONObject body = safeJson(r);
        Mode mode = body.has("mode") ? requireMode(body.opt("mode")) : backend.defaultMode();
        boolean icon = body.optBoolean("frost_icon", false);
        if (!backend.rootAvailable()) throw noRoot();

        JSONArray results = new JSONArray();
        int ok = 0;
        for (String pkg : pkgs) {
            JSONObject res = new JSONObject().put("package", pkg);
            String why = null;
            if (pkg.equals(backend.selfPackage())) why = "Permafrost cannot freeze itself";
            else if (backend.managed(pkg) != null) why = "already managed";
            else if (!backend.isInstalled(pkg)) why = "not installed";
            if (why == null) why = backend.add(pkg, mode);
            ManagedApp app = backend.managed(pkg);
            if (why == null && app != null) {
                ok++;
                if (icon) res.put("frost_icon_requested", backend.pinShortcut(app));
                res.put("ok", true).put("app", appJson(app, true));
            } else {
                res.put("ok", false).put("error", why);
                // The picker keeps an app it managed to add even when the first
                // freeze failed; say so rather than leave it a surprise.
                if (app != null) res.put("app", appJson(app, live(app)));
            }
            results.put(res);
        }
        JSONObject out = new JSONObject().put("ok", ok == pkgs.size()).put("results", results);
        return ApiResponse.json(out, ok == 0 ? 409 : 200);
    }

    /** {@link ApiRequest#packages} accepts a bare array; the options live only in an object body. */
    private static JSONObject safeJson(ApiRequest r) {
        String text = new String(r.body, StandardCharsets.UTF_8).trim();
        if (!text.startsWith("{")) return new JSONObject();
        return r.json();
    }

    private ApiResponse patchApp(ApiRequest r, ManagedApp app) throws JSONException {
        JSONObject body = r.json();
        if (!body.has("mode")) {
            throw new ApiException(400, "nothing to change", "PATCH takes {\"mode\":\"freeze\"|\"ghost\"}");
        }
        Mode mode = requireMode(body.opt("mode"));
        return act(app, backend.changeMode(requireRoot(app), mode), null);
    }

    private ApiResponse removeApp(ManagedApp app) throws JSONException {
        requireRoot(app);
        backend.unmanage(app);
        return ApiResponse.json(new JSONObject()
                .put("ok", true).put("package", app.packageName).put("managed", false));
    }

    private ApiResponse launch(ManagedApp app) throws JSONException {
        String err = backend.launch(requireRoot(app));
        String warning = null;
        if (err == null && !app.updateUnlocked && !backend.hasUsageAccess()) {
            warning = "Usage access is not granted, so the app will NOT re-freeze when you leave it. "
                    + "Grant it in Settings > Apps > Special app access > Usage access.";
        }
        return act(app, err, warning);
    }

    private ApiResponse frostIcon(ManagedApp app) throws JSONException {
        boolean requested = backend.pinShortcut(app);
        if (!requested) {
            throw new ApiException(409, "the launcher did not accept a frost icon",
                    "most launchers only show the pin dialog while Permafrost is on screen; "
                            + "open Permafrost and retry, or use the app's Add frost icon button");
        }
        return ApiResponse.json(new JSONObject()
                .put("ok", true).put("requested", true)
                .put("note", "the launcher asks the user to confirm before the icon appears"));
    }

    private ApiResponse icon(String pkg) {
        byte[] png = backend.frostIconPng(pkg);
        if (png == null) throw new ApiException(404, "no icon for '" + pkg + "'");
        return ApiResponse.bytes(png, "image/png");
    }

    /** The result of one action: the app's fresh state, or a 500 with the device's error. */
    private ApiResponse act(ManagedApp app, String err, String warning) throws JSONException {
        if (err != null) throw new ApiException(500, err);
        ManagedApp now = backend.managed(app.packageName);
        JSONObject o = new JSONObject().put("ok", true);
        if (now != null) o.put("app", appJson(now, live(now)));
        if (warning != null) o.put("warning", warning);
        return ApiResponse.json(o);
    }

    private ApiResponse installed(ApiRequest r) throws JSONException {
        String q = r.query.get("q");
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        JSONArray arr = new JSONArray();
        for (ApiBackend.InstalledApp e : backend.installed()) {
            if (!needle.isEmpty()
                    && !e.label.toLowerCase(Locale.ROOT).contains(needle)
                    && !e.pkg.toLowerCase(Locale.ROOT).contains(needle)) continue;
            arr.put(new JSONObject()
                    .put("package", e.pkg)
                    .put("label", e.label)
                    .put("managed", backend.managed(e.pkg) != null));
        }
        return ApiResponse.json(new JSONObject().put("apps", arr));
    }

    private Boolean live(ManagedApp app) {
        return backend.dormantOnDevice(Collections.singletonList(app)).get(app.packageName);
    }

    private JSONObject appJson(ManagedApp a, Boolean dormant) throws JSONException {
        String state;
        if (a.updateUnlocked) state = "unlocked_for_update";
        else if (dormant == null) state = "unknown";
        else if (dormant) state = a.mode == Mode.GHOST ? "ghosted" : "frozen";
        else state = "awake";
        return new JSONObject()
                .put("package", a.packageName)
                .put("label", a.label)
                .put("mode", a.mode.name().toLowerCase(Locale.ROOT))
                .put("state", state)
                .put("dormant", dormant == null ? JSONObject.NULL : dormant)
                .put("update_unlocked", a.updateUnlocked)
                .put("frost_icon", a.hasShortcut)
                .put("has_backup", a.hasBackup);
    }

    private ManagedApp requireManaged(String pkg) {
        ManagedApp a = backend.managed(pkg);
        if (a == null) {
            throw new ApiException(404, "'" + pkg + "' is not managed by Permafrost",
                    "GET /api/apps lists managed apps; POST /api/apps adds one");
        }
        return a;
    }

    private ManagedApp requireRoot(ManagedApp app) {
        if (!backend.rootAvailable()) throw noRoot();
        return app;
    }

    private static ApiException noRoot() {
        return new ApiException(503, "root is not available to Permafrost", NO_ROOT_HINT);
    }

    private static Mode requireMode(Object raw) {
        String s = raw == null ? "" : raw.toString().trim().toUpperCase(Locale.ROOT);
        Mode m = Mode.fromName(s, null);
        if (m == null) throw new ApiException(400, "unknown mode '" + raw + "'", "one of: freeze, ghost");
        return m;
    }

    // ---------------- Settings ----------------

    private ApiResponse settings(ApiRequest r) throws JSONException {
        switch (r.method) {
            case "GET": return ApiResponse.json(settingsJson());
            case "PATCH": return patchSettings(r.json());
            default: return methodNotAllowed(r, "GET, PATCH");
        }
    }

    private JSONObject settingsJson() throws JSONException {
        return new JSONObject()
                .put("default_mode", backend.defaultMode().name().toLowerCase(Locale.ROOT))
                .put("refreeze_delay_sec", backend.refreezeDelaySec())
                .put("palette", backend.palette())
                .put("dark_mode", backend.darkMode())
                .put("app_lock", backend.appLock())
                .put("lock_frost_icons", backend.lockFrostIcons())
                .put("lock_method", backend.lockMethod());
    }

    /** Validates everything before changing anything, so a bad field never half-applies. */
    private ApiResponse patchSettings(JSONObject b) throws JSONException {
        Mode mode = b.has("default_mode") ? requireMode(b.opt("default_mode")) : null;

        Integer delay = null;
        if (b.has("refreeze_delay_sec")) {
            Object raw = b.opt("refreeze_delay_sec");
            if (!(raw instanceof Number) || ((Number) raw).doubleValue() % 1 != 0
                    || ((Number) raw).intValue() < 0 || ((Number) raw).intValue() > 15) {
                throw new ApiException(400, "refreeze_delay_sec must be a whole number from 0 to 15");
            }
            delay = ((Number) raw).intValue();
        }

        String palette = null;
        if (b.has("palette")) {
            String want = b.optString("palette").trim();
            for (Object[] p : backend.palettes()) {
                if (((String) p[0]).equalsIgnoreCase(want)) palette = (String) p[0];
            }
            if (palette == null) {
                throw new ApiException(400, "no palette called '" + want + "'",
                        "GET /api/palettes lists them");
            }
        }

        String dark = null;
        if (b.has("dark_mode")) {
            dark = b.optString("dark_mode").trim().toLowerCase(Locale.ROOT);
            if (!DARK_MODES.contains(dark)) {
                throw new ApiException(400, "unknown dark_mode '" + b.opt("dark_mode") + "'",
                        "one of: system, light, dark");
            }
        }

        Boolean lock = b.has("app_lock") ? requireBool(b, "app_lock") : null;
        Boolean lockIcons = b.has("lock_frost_icons") ? requireBool(b, "lock_frost_icons") : null;
        if (Boolean.TRUE.equals(lock) && backend.lockMethod().equals("none")) {
            throw new ApiException(409, "the device has no screen lock to challenge with",
                    "set a PIN, pattern or password on the device first");
        }
        boolean lockAfter = lock != null ? lock : backend.appLock();
        if (Boolean.TRUE.equals(lockIcons) && !lockAfter) {
            throw new ApiException(409, "lock_frost_icons needs app_lock on",
                    "send {\"app_lock\":true,\"lock_frost_icons\":true}");
        }

        if (mode != null) backend.setDefaultMode(mode);
        if (delay != null) backend.setRefreezeDelaySec(delay);
        if (palette != null || dark != null) backend.setTheme(palette, dark);
        if (lock != null) backend.setAppLock(lock);
        if (lockIcons != null) backend.setLockFrostIcons(lockIcons);
        return ApiResponse.json(settingsJson());
    }

    private static boolean requireBool(JSONObject b, String name) {
        Object raw = b.opt(name);
        if (!(raw instanceof Boolean)) throw new ApiException(400, name + " must be true or false");
        return (Boolean) raw;
    }

    private ApiResponse palettes() throws JSONException {
        JSONArray arr = new JSONArray();
        String current = backend.palette();
        for (Object[] p : backend.palettes()) {
            arr.put(new JSONObject()
                    .put("name", p[0])
                    .put("label", p[1])
                    .put("follows_dark_mode", p[2])
                    .put("mono", p[3])
                    .put("current", ((String) p[0]).equalsIgnoreCase(current)));
        }
        return ApiResponse.json(new JSONObject().put("palettes", arr));
    }

    private ApiResponse crash() {
        String report = backend.crashReport();
        if (report == null) throw new ApiException(404, "no crash report", "Permafrost has not crashed");
        return ApiResponse.text(report, "text/plain; charset=utf-8");
    }
}
