package com.portablediag.permafrost.core;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal root shell. Each call spawns a fresh {@code su} process, feeds it the
 * command(s), and collects stdout+stderr. Kept deliberately simple and
 * stateless so a wedged shell can never poison later calls.
 */
public class Root {

    public static class Result {
        public final int code;
        public final String out;
        public final String err;

        Result(int code, String out, String err) {
            this.code = code;
            this.out = out;
            this.err = err;
        }

        public boolean ok() {
            return code == 0;
        }

        public String combined() {
            String o = out == null ? "" : out.trim();
            String e = err == null ? "" : err.trim();
            if (o.isEmpty()) return e;
            if (e.isEmpty()) return o;
            return o + "\n" + e;
        }
    }

    private static volatile Boolean cachedAvailable = null;

    /** True if {@code su} exists and grants us a root shell. Cached after first success. */
    public static boolean isAvailable() {
        if (Boolean.TRUE.equals(cachedAvailable)) return true;
        Result r = run("id -u");
        boolean ok = r.ok() && r.out != null && r.out.trim().equals("0");
        cachedAvailable = ok;
        return ok;
    }

    /** Run a single command string through {@code su -c} equivalent (via stdin). */
    public static Result run(String command) {
        return run(new String[]{command});
    }

    /** Run several commands in one root shell, in order. */
    public static Result run(String[] commands) {
        Process p = null;
        try {
            p = new ProcessBuilder("su").redirectErrorStream(false).start();
            DataOutputStream stdin = new DataOutputStream(p.getOutputStream());
            for (String c : commands) {
                stdin.writeBytes(c + "\n");
            }
            stdin.writeBytes("exit\n");
            stdin.flush();

            String out = readAll(p.getInputStream());
            String err = readAll(p.getErrorStream());
            int code = p.waitFor();
            return new Result(code, out, err);
        } catch (Exception e) {
            return new Result(-1, "", e.getMessage());
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static String readAll(java.io.InputStream in) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                if (!first) sb.append('\n');
                sb.append(line);
                first = false;
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    /** Convenience: split a shell command's stdout into non-empty trimmed lines. */
    public static List<String> lines(String command) {
        List<String> result = new ArrayList<>();
        Result r = run(command);
        if (r.out == null) return result;
        for (String l : r.out.split("\n")) {
            String t = l.trim();
            if (!t.isEmpty()) result.add(t);
        }
        return result;
    }
}
