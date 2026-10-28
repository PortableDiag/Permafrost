package com.portablediag.permafrost.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.portablediag.permafrost.model.ManagedApp;
import com.portablediag.permafrost.model.Mode;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** The whole API surface against an in-memory phone: routes, auth, errors, and PhoneAPI's key. */
public class ApiRouterTest {

    private static final String KEY = "owner-key-0123456789";
    private static final String PHONEAPI_KEY = "phoneapi-key-9876543210";
    private static final byte[] PROOF_KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    private static final String PKG = "com.example.compass";

    private FakeBackend phone;
    private PhoneApi.Grant grant;
    private ApiRouter router;

    @Before
    public void setUp() {
        phone = new FakeBackend();
        grant = null;
        router = new ApiRouter(phone, () -> KEY, () -> grant);
    }

    // ---------------- helpers ----------------

    private ApiResponse call(String method, String path, String body, String... headers) {
        Map<String, String> h = new HashMap<>();
        for (int i = 0; i + 1 < headers.length; i += 2) h.put(headers[i].toLowerCase(), headers[i + 1]);
        Map<String, String> query = new HashMap<>();
        int q = path.indexOf('?');
        if (q >= 0) {
            for (String kv : path.substring(q + 1).split("&")) {
                String[] p = kv.split("=", 2);
                query.put(p[0], p.length > 1 ? p[1] : "");
            }
            path = path.substring(0, q);
        }
        byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        return router.handle(new ApiRequest(method, path, query, h, b));
    }

    private ApiResponse owner(String method, String path, String body) {
        return call(method, path, body, "X-API-Key", KEY);
    }

    private static JSONObject json(ApiResponse r) {
        try {
            return new JSONObject(new String(r.body, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError("not JSON (" + r.status + "): " + new String(r.body, StandardCharsets.UTF_8));
        }
    }

    // ---------------- every documented route answers ----------------

    @Test
    public void everyDocumentedRouteAnswers() {
        phone.manage(PKG, Mode.FREEZE, true);
        phone.crash = "boom";
        for (String line : ApiDocs.SUMMARY) {
            String[] parts = line.trim().split("\\s+");
            String method = parts[0];
            String path = parts[1].replace("{package}", PKG).replace("?q=", "?q=comp");
            String body = null;
            if (method.equals("POST") && path.equals("/api/apps")) body = "{\"package\":\"com.example.notes\"}";
            if (method.equals("PATCH") && path.startsWith("/api/apps/")) body = "{\"mode\":\"ghost\"}";
            if (method.equals("PATCH") && path.equals("/api/settings")) body = "{\"refreeze_delay_sec\":3}";
            ApiResponse r = owner(method, path, body);
            assertTrue(line + " answered " + r.status, r.status < 400);
            // Removal is last for this package only if the docs keep that order; re-add it.
            if (phone.managed(PKG) == null) phone.manage(PKG, Mode.FREEZE, true);
        }
    }

    @Test
    public void indexListsTheSummary() throws Exception {
        JSONObject o = json(owner("GET", "/api", null));
        assertEquals(ApiDocs.SUMMARY.length, o.getJSONArray("endpoints").length());
    }

    // ---------------- auth ----------------

    @Test
    public void healthNeedsNoKey() throws Exception {
        ApiResponse r = call("GET", "/api/health", null);
        assertEquals(200, r.status);
        assertTrue(json(r).getBoolean("ok"));
    }

    @Test
    public void everythingElseNeedsTheKey() {
        assertEquals(401, call("GET", "/api/apps", null).status);
        assertEquals(401, call("GET", "/api/apps", null, "X-API-Key", "wrong").status);
        assertEquals(401, call("GET", "/api/apps", null, "X-API-Key", KEY + "x").status);
        assertEquals(200, call("GET", "/api/apps", null, "Authorization", "Bearer " + KEY).status);
        assertEquals(200, call("GET", "/api/apps", null, "authorization", "bearer " + KEY).status);
    }

    @Test
    public void anEmptyOwnerKeyOpensNothing() {
        router = new ApiRouter(phone, () -> "", () -> null);
        assertEquals(401, call("GET", "/api/apps", null, "X-API-Key", "").status);
    }

    // ---------------- conventions ----------------

    @Test
    public void notAnApiPathIs404() {
        assertEquals(404, owner("GET", "/", null).status);
        assertEquals(404, owner("GET", "/apix", null).status);
    }

    @Test
    public void unknownRouteIs404WithAHint() throws Exception {
        ApiResponse r = owner("GET", "/api/nope", null);
        assertEquals(404, r.status);
        assertTrue(json(r).getString("hint").contains("/api/docs"));
    }

    @Test
    public void wrongMethodIs405WithAllow() {
        ApiResponse r = owner("DELETE", "/api/settings", null);
        assertEquals(405, r.status);
        assertEquals("GET, PATCH", r.extraHeaders.get("Allow"));
        assertEquals(405, owner("GET", "/api/apps/" + PKG + "/freeze", null).status);
    }

    @Test
    public void optionsIsNoContent() {
        assertEquals(204, call("OPTIONS", "/api/apps", null).status);
    }

    @Test
    public void badJsonIs400() {
        phone.manage(PKG, Mode.FREEZE, true);
        assertEquals(400, owner("PATCH", "/api/apps/" + PKG, "{nope").status);
    }

    // ---------------- apps ----------------

    @Test
    public void listReportsTheDevicesState() throws Exception {
        phone.manage(PKG, Mode.FREEZE, true);
        phone.manage("com.example.notes", Mode.GHOST, false);
        JSONObject o = json(owner("GET", "/api/apps", null));
        assertEquals("frozen", o.getJSONArray("apps").getJSONObject(0).getString("state"));
        assertEquals("awake", o.getJSONArray("apps").getJSONObject(1).getString("state"));
    }

    @Test
    public void withoutRootStateIsUnknown() throws Exception {
        phone.manage(PKG, Mode.FREEZE, true);
        phone.root = false;
        JSONObject a = json(owner("GET", "/api/apps", null)).getJSONArray("apps").getJSONObject(0);
        assertEquals("unknown", a.getString("state"));
        assertTrue(a.isNull("dormant"));
    }

    @Test
    public void unmanagedPackageIs404() {
        assertEquals(404, owner("GET", "/api/apps/com.not.managed", null).status);
        assertEquals(404, owner("POST", "/api/apps/com.not.managed/freeze", null).status);
    }

    @Test
    public void actionsNeedRoot() throws Exception {
        phone.manage(PKG, Mode.FREEZE, true);
        phone.root = false;
        ApiResponse r = owner("POST", "/api/apps/" + PKG + "/wake", null);
        assertEquals(503, r.status);
        assertTrue(json(r).getString("hint").contains("Magisk"));
        assertTrue(phone.calls.isEmpty());
    }

    @Test
    public void aDeviceRefusalIs500WithItsText() throws Exception {
        phone.manage(PKG, Mode.FREEZE, false);
        phone.failWith = "pm: Shell cannot change component state";
        ApiResponse r = owner("POST", "/api/apps/" + PKG + "/freeze", null);
        assertEquals(500, r.status);
        assertEquals("pm: Shell cannot change component state", json(r).getString("error"));
    }

    @Test
    public void wakeAndFreezeChangeState() throws Exception {
        phone.manage(PKG, Mode.FREEZE, true);
        assertEquals("awake", json(owner("POST", "/api/apps/" + PKG + "/wake", null))
                .getJSONObject("app").getString("state"));
        assertEquals("frozen", json(owner("POST", "/api/apps/" + PKG + "/freeze", null))
                .getJSONObject("app").getString("state"));
        assertEquals(Arrays.asList("wake " + PKG, "freeze " + PKG), phone.calls);
    }

    @Test
    public void launchWarnsWithoutUsageAccess() throws Exception {
        phone.manage(PKG, Mode.FREEZE, true);
        phone.usageAccess = false;
        JSONObject o = json(owner("POST", "/api/apps/" + PKG + "/launch", null));
        assertTrue(o.getString("warning").contains("Usage access"));
        phone.usageAccess = true;
        assertFalse(json(owner("POST", "/api/apps/" + PKG + "/launch", null)).has("warning"));
    }

    @Test
    public void unlockForUpdateReportsIt() throws Exception {
        phone.manage(PKG, Mode.FREEZE, true);
        JSONObject a = json(owner("POST", "/api/apps/" + PKG + "/unlock-update", null)).getJSONObject("app");
        assertEquals("unlocked_for_update", a.getString("state"));
        assertTrue(a.getBoolean("update_unlocked"));
    }

    @Test
    public void patchMode() throws Exception {
        phone.manage(PKG, Mode.FREEZE, true);
        assertEquals("ghost", json(owner("PATCH", "/api/apps/" + PKG, "{\"mode\":\"Ghost\"}"))
                .getJSONObject("app").getString("mode"));
        assertEquals(400, owner("PATCH", "/api/apps/" + PKG, "{\"mode\":\"sleep\"}").status);
        assertEquals(400, owner("PATCH", "/api/apps/" + PKG, "{}").status);
    }

    @Test
    public void aRefusedModeChangeIs500() {
        phone.manage(PKG, Mode.FREEZE, true);
        phone.failWith = "backup failed: cannot archive data dir";
        assertEquals(500, owner("PATCH", "/api/apps/" + PKG, "{\"mode\":\"ghost\"}").status);
        assertEquals(Mode.FREEZE, phone.managed(PKG).mode);
    }

    @Test
    public void deleteUnmanages() throws Exception {
        phone.manage(PKG, Mode.FREEZE, true);
        JSONObject o = json(owner("DELETE", "/api/apps/" + PKG, null));
        assertFalse(o.getBoolean("managed"));
        assertNull(phone.managed(PKG));
    }

    @Test
    public void addTakesEverySpelling() throws Exception {
        assertEquals(200, owner("POST", "/api/apps", "{\"package\":\"com.example.compass\"}").status);
        phone.apps.clear();
        assertEquals(200, owner("POST", "/api/apps", "{\"packages\":[\"com.example.compass\"]}").status);
        phone.apps.clear();
        JSONObject o = json(owner("POST", "/api/apps", "[\"com.example.compass\",\"com.example.notes\"]"));
        assertTrue(o.getBoolean("ok"));
        assertEquals(2, o.getJSONArray("results").length());
        assertEquals("frozen", o.getJSONArray("results").getJSONObject(1).getJSONObject("app").getString("state"));
    }

    @Test
    public void addUsesTheDefaultModeUnlessTold() {
        phone.defaultMode = Mode.GHOST;
        owner("POST", "/api/apps", "{\"package\":\"com.example.compass\"}");
        assertEquals(Mode.GHOST, phone.managed(PKG).mode);
        owner("POST", "/api/apps", "{\"package\":\"com.example.notes\",\"mode\":\"freeze\"}");
        assertEquals(Mode.FREEZE, phone.managed("com.example.notes").mode);
    }

    @Test
    public void addRefusals() throws Exception {
        assertEquals(400, owner("POST", "/api/apps", "{}").status);
        ApiResponse r = owner("POST", "/api/apps",
                "[\"com.portablediag.permafrost\",\"com.not.installed\"]");
        assertEquals(409, r.status);
        JSONObject o = json(r);
        assertFalse(o.getBoolean("ok"));
        assertTrue(o.getJSONArray("results").getJSONObject(0).getString("error").contains("itself"));
        assertEquals("not installed", o.getJSONArray("results").getJSONObject(1).getString("error"));

        phone.manage(PKG, Mode.FREEZE, true);
        r = owner("POST", "/api/apps", "[\"" + PKG + "\",\"com.example.notes\"]");
        assertEquals(200, r.status);
        assertFalse(json(r).getBoolean("ok"));
        assertEquals("already managed", json(r).getJSONArray("results").getJSONObject(0).getString("error"));
    }

    @Test
    public void addNeedsRoot() {
        phone.root = false;
        assertEquals(503, owner("POST", "/api/apps", "{\"package\":\"com.example.compass\"}").status);
        assertNull(phone.managed(PKG));
    }

    @Test
    public void installedFilters() throws Exception {
        phone.manage(PKG, Mode.FREEZE, true);
        JSONObject o = json(owner("GET", "/api/installed?q=COMP", null));
        assertEquals(1, o.getJSONArray("apps").length());
        assertTrue(o.getJSONArray("apps").getJSONObject(0).getBoolean("managed"));
        assertEquals(2, json(owner("GET", "/api/installed", null)).getJSONArray("apps").length());
    }

    @Test
    public void iconIsPngOr404() {
        phone.manage(PKG, Mode.FREEZE, true);
        ApiResponse r = owner("GET", "/api/apps/" + PKG + "/icon", null);
        assertEquals(200, r.status);
        assertEquals("image/png", r.contentType);
        assertEquals(404, owner("GET", "/api/apps/com.other/icon", null).status);
    }

    @Test
    public void crashIs404UntilThereIsOne() {
        assertEquals(404, owner("GET", "/api/crash", null).status);
        phone.crash = "java.lang.RuntimeException";
        ApiResponse r = owner("GET", "/api/crash", null);
        assertEquals(200, r.status);
        assertTrue(r.contentType.startsWith("text/plain"));
    }

    // ---------------- settings ----------------

    @Test
    public void settingsRoundTrip() throws Exception {
        JSONObject o = json(owner("PATCH", "/api/settings",
                "{\"default_mode\":\"ghost\",\"refreeze_delay_sec\":0,\"palette\":\"zoro\",\"dark_mode\":\"DARK\"}"));
        assertEquals("ghost", o.getString("default_mode"));
        assertEquals(0, o.getInt("refreeze_delay_sec"));
        assertEquals("Zoro", o.getString("palette"));
        assertEquals("dark", o.getString("dark_mode"));
        assertEquals("Zoro", json(owner("GET", "/api/settings", null)).getString("palette"));
    }

    @Test
    public void aBadFieldChangesNothing() {
        ApiResponse r = owner("PATCH", "/api/settings", "{\"palette\":\"Zoro\",\"refreeze_delay_sec\":16}");
        assertEquals(400, r.status);
        assertEquals("Material You", phone.palette);
        assertEquals(400, owner("PATCH", "/api/settings", "{\"refreeze_delay_sec\":1.5}").status);
        assertEquals(400, owner("PATCH", "/api/settings", "{\"refreeze_delay_sec\":\"2\"}").status);
        assertEquals(400, owner("PATCH", "/api/settings", "{\"palette\":\"Nope\"}").status);
        assertEquals(400, owner("PATCH", "/api/settings", "{\"dark_mode\":\"dim\"}").status);
        assertEquals(400, owner("PATCH", "/api/settings", "{\"app_lock\":\"yes\"}").status);
        assertEquals(2, phone.delay);
    }

    @Test
    public void appLockRules() {
        phone.lockMethod = "none";
        assertEquals(409, owner("PATCH", "/api/settings", "{\"app_lock\":true}").status);
        assertFalse(phone.appLock);
        phone.lockMethod = "credential";
        assertEquals(409, owner("PATCH", "/api/settings", "{\"lock_frost_icons\":true}").status);
        assertEquals(200, owner("PATCH", "/api/settings", "{\"app_lock\":true,\"lock_frost_icons\":true}").status);
        assertTrue(phone.appLock);
        assertTrue(phone.lockIcons);
    }

    @Test
    public void palettesMarkTheCurrentOne() throws Exception {
        phone.palette = "Zoro";
        JSONObject o = json(owner("GET", "/api/palettes", null));
        assertEquals(3, o.getJSONArray("palettes").length());
        assertFalse(o.getJSONArray("palettes").getJSONObject(0).getBoolean("current"));
        assertTrue(o.getJSONArray("palettes").getJSONObject(1).getBoolean("current"));
        assertTrue(o.getJSONArray("palettes").getJSONObject(2).getBoolean("mono"));
    }

    // ---------------- PhoneAPI's key ----------------

    private ApiResponse phoneApi(String method, String path, String body) {
        return call(method, path, body, "X-API-Key", PHONEAPI_KEY);
    }

    @Test
    public void phoneApiKeyDoesOnlyWhatWasTicked() {
        phone.manage(PKG, Mode.FREEZE, true);
        grant = new PhoneApi.Grant(PHONEAPI_KEY, PROOF_KEY, true, false);
        assertEquals(200, phoneApi("GET", "/api/apps", null).status);
        assertEquals(403, phoneApi("POST", "/api/apps/" + PKG + "/wake", null).status);
        assertEquals(403, phoneApi("PATCH", "/api/settings", "{\"palette\":\"Zoro\"}").status);
        assertTrue(phone.calls.isEmpty());
        assertEquals("Material You", phone.palette);

        grant = new PhoneApi.Grant(PHONEAPI_KEY, PROOF_KEY, false, true);
        assertEquals(403, phoneApi("GET", "/api/apps", null).status);
        assertEquals(200, phoneApi("POST", "/api/apps/" + PKG + "/wake", null).status);

        grant = new PhoneApi.Grant(PHONEAPI_KEY, PROOF_KEY, true, true);
        assertEquals(200, phoneApi("GET", "/api/settings", null).status);
        assertEquals(200, phoneApi("DELETE", "/api/apps/" + PKG, null).status);
    }

    @Test
    public void phoneApiKeyStopsWhenRevoked() {
        grant = new PhoneApi.Grant(PHONEAPI_KEY, PROOF_KEY, true, true);
        assertEquals(200, phoneApi("GET", "/api/apps", null).status);
        grant = null;
        assertEquals(401, phoneApi("GET", "/api/apps", null).status);
    }

    @Test
    public void ownerKeyIsUnaffectedByTheGrant() {
        grant = new PhoneApi.Grant(PHONEAPI_KEY, PROOF_KEY, false, false);
        phone.manage(PKG, Mode.FREEZE, true);
        assertEquals(200, owner("POST", "/api/apps/" + PKG + "/wake", null).status);
    }

    @Test
    public void phoneApiKeyIsNotTheOwnersKey() {
        grant = new PhoneApi.Grant(KEY, PROOF_KEY, false, false);
        // Same string as the owner key: the owner wins, never the narrower grant.
        phone.manage(PKG, Mode.FREEZE, true);
        assertEquals(200, owner("POST", "/api/apps/" + PKG + "/wake", null).status);
    }

    // ---------------- the proof header ----------------

    @Test
    public void everyAnswerCarriesTheProof() {
        grant = new PhoneApi.Grant(PHONEAPI_KEY, PROOF_KEY, true, false);
        String want = PhoneApi.proof(PROOF_KEY, "abc123");
        String[][] cases = {
                {"GET", "/api/health", ""},          // 200, no key
                {"GET", "/api/apps", "nokey"},       // 401
                {"GET", "/api/nope", ""},            // 404
                {"POST", "/api/settings", ""},       // 405 (owner) / 403 (phoneapi)
                {"GET", "/", ""},                    // 404 outside /api
        };
        for (String[] c : cases) {
            ApiResponse r = c[2].equals("nokey")
                    ? call(c[0], c[1], null, "X-PhoneAPI-Challenge", "abc123")
                    : call(c[0], c[1], null, "X-API-Key", PHONEAPI_KEY, "X-PhoneAPI-Challenge", "abc123");
            assertEquals(c[0] + " " + c[1] + " (" + r.status + ")", want, r.extraHeaders.get(PhoneApi.HEADER_PROOF));
        }
    }

    @Test
    public void theProofKeepsOtherHeaders() {
        grant = new PhoneApi.Grant(PHONEAPI_KEY, PROOF_KEY, true, true);
        ApiResponse r = call("DELETE", "/api/settings", null, "X-API-Key", PHONEAPI_KEY, "X-PhoneAPI-Challenge", "c");
        assertEquals(405, r.status);
        assertEquals("GET, PATCH", r.extraHeaders.get("Allow"));
        assertNotNull(r.extraHeaders.get(PhoneApi.HEADER_PROOF));
    }

    @Test
    public void noProofWithoutAGrantOrAChallenge() {
        ApiResponse r = call("GET", "/api/health", null, "X-PhoneAPI-Challenge", "abc");
        assertNull(r.extraHeaders.get(PhoneApi.HEADER_PROOF));
        grant = new PhoneApi.Grant(PHONEAPI_KEY, PROOF_KEY, true, true);
        assertNull(call("GET", "/api/health", null).extraHeaders.get(PhoneApi.HEADER_PROOF));
        assertNull(call("GET", "/api/health", null, "X-PhoneAPI-Challenge", "café")
                .extraHeaders.get(PhoneApi.HEADER_PROOF));
        char[] big = new char[257];
        Arrays.fill(big, 'a');
        assertNull(call("GET", "/api/health", null, "X-PhoneAPI-Challenge", new String(big))
                .extraHeaders.get(PhoneApi.HEADER_PROOF));
    }

    @Test
    public void proofDependsOnKeyAndChallenge() {
        String a = PhoneApi.proof(PROOF_KEY, "one");
        assertNotEquals(a, PhoneApi.proof(PROOF_KEY, "two"));
        assertNotEquals(a, PhoneApi.proof("another-key-another-key-another!".getBytes(StandardCharsets.US_ASCII), "one"));
        assertFalse(a.contains("=") || a.contains("+") || a.contains("/"));
    }

    @Test
    public void proofIsHmacSha256() {
        // RFC 4231 test case 2.
        String hex = "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843";
        byte[] want = new byte[32];
        for (int i = 0; i < 32; i++) want[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        assertEquals(PhoneApi.b64url(want),
                PhoneApi.proof("Jefe".getBytes(StandardCharsets.US_ASCII), "what do ya want for nothing?"));
    }

    @Test
    public void b64urlRoundTrips() {
        byte[] b = new byte[32];
        for (int i = 0; i < b.length; i++) b[i] = (byte) (i * 37);
        assertTrue(Arrays.equals(b, PhoneApi.fromB64url(PhoneApi.b64url(b))));
        assertNull(PhoneApi.fromB64url("not base64!"));
        assertNull(PhoneApi.fromB64url(""));
    }

    // ---------------- the caller check ----------------

    @Test
    public void onlyPhoneApisReleaseBuildIsTrusted() {
        String pin = PhoneApi.PIN;
        String bare = pin.replace(":", "").toLowerCase();
        assertTrue(PhoneApi.callerTrusted(PhoneApi.PKG, Collections.singletonList(bare)));
        assertTrue(PhoneApi.callerTrusted(PhoneApi.PKG, Arrays.asList("00ff", pin)));
        String debug = bare.substring(0, bare.length() - 1) + "3";
        assertFalse(PhoneApi.callerTrusted(PhoneApi.PKG, Collections.singletonList(debug)));
        assertFalse(PhoneApi.callerTrusted("com.evil.app", Collections.singletonList(bare)));
        assertFalse(PhoneApi.callerTrusted(null, Collections.singletonList(bare)));
        assertFalse(PhoneApi.callerTrusted(PhoneApi.PKG, Collections.<String>emptyList()));
    }

    @Test
    public void unusedAppIsUntouchedByRefusals() {
        ManagedApp a = phone.manage(PKG, Mode.FREEZE, true);
        owner("POST", "/api/apps/" + PKG + "/explode", null);
        assertTrue(a.dormant);
        assertTrue(phone.calls.isEmpty());
    }
}
