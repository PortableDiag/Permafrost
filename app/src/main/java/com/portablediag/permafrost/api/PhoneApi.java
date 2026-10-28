package com.portablediag.permafrost.api;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Permafrost as a PhoneAPI provider (PhoneAPI's docs/provider-contract.md §2–3),
 * the parts with no Android in them.
 *
 * <p>Any app can bind 8791 while the local API is off, and PhoneAPI's pin only
 * proves who is installed, not who is listening. So once PhoneAPI holds a proof
 * key from the handshake, every answer carries {@code X-PhoneAPI-Proof} =
 * HMAC-SHA256(proof key, the request's {@code X-PhoneAPI-Challenge}), and
 * PhoneAPI throws away an answer without one.
 */
public final class PhoneApi {

    public static final String PKG = "com.phoneapi.app";
    public static final String ACTION_GRANT_KEY = "com.phoneapi.action.GRANT_KEY";
    public static final String EXTRA_EVENT_PORT = "phoneapi_port";
    public static final String EXTRA_EVENT_KEY = "phoneapi_event_key";
    public static final String HEADER_CHALLENGE = "x-phoneapi-challenge";
    public static final String HEADER_PROOF = "X-PhoneAPI-Proof";

    /** PhoneAPI's release certificate (keystore-manager record PhoneAPI). A debug build of PhoneAPI is refused. */
    public static final String PIN =
            "AD:86:A7:04:86:39:86:F3:A4:ED:10:66:00:F1:EC:C2:19:EE:31:82:68:D4:84:BB:54:E9:07:9C:E3:B8:E0:02";

    private PhoneApi() {}

    /**
     * What the owner let PhoneAPI's key do. PhoneAPI exposes a REST provider as
     * two switches, {@code permafrost.get} and {@code permafrost.request}; these
     * are the same split on Permafrost's side.
     */
    public static final class Grant {
        public final String key;
        public final byte[] proofKey;
        /** GET: list apps, read settings, the docs. */
        public final boolean see;
        /** Everything else: freeze, wake, launch, add, remove, change settings. */
        public final boolean change;

        public Grant(String key, byte[] proofKey, boolean see, boolean change) {
            this.key = key;
            this.proofKey = proofKey;
            this.see = see;
            this.change = change;
        }
    }

    public static String b64url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Null when it isn't base64url (padded or not). */
    public static byte[] fromB64url(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return Base64.getUrlDecoder().decode(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String proof(byte[] proofKey, String challenge) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(proofKey, "HmacSHA256"));
            return b64url(mac.doFinal(challenge.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A challenge worth answering: present, short, printable ASCII (it is hashed, never echoed). */
    public static String usableChallenge(String c) {
        if (c == null) return null;
        c = c.trim();
        if (c.isEmpty() || c.length() > 256) return null;
        for (int i = 0; i < c.length(); i++) {
            char ch = c.charAt(i);
            if (ch < ' ' || ch > '~') return null;
        }
        return c;
    }

    /** Whether any of {@code certs} (SHA-256, hex, any case, colons or not) is PhoneAPI's release certificate. */
    public static boolean pinMatches(List<String> certs) {
        byte[] pin = norm(PIN).getBytes(StandardCharsets.US_ASCII);
        for (String c : certs) {
            if (c != null && MessageDigest.isEqual(norm(c).getBytes(StandardCharsets.US_ASCII), pin)) return true;
        }
        return false;
    }

    /** The caller of a GRANT_KEY: PhoneAPI's package, signed by the pin. */
    public static boolean callerTrusted(String callingPackage, List<String> certs) {
        return PKG.equals(callingPackage) && pinMatches(certs);
    }

    private static String norm(String s) {
        return s.replace(":", "").trim().toLowerCase(Locale.ROOT);
    }
}
