package com.portablediag.permafrost.api;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The socket half of the API: accept, read one request, answer, close. No
 * keep-alive — the callers are scripts making a call every few seconds, and a
 * connection that is always closed cannot be left half-read. Each connection
 * goes to a small pool, so one slow ghost restore can't block a health check.
 */
public class ApiServer {

    private static final String TAG = "PermafrostApi";
    private static final int WORKERS = 4;
    /** A client that connects and then says nothing gets dropped rather than held. */
    private static final int SOCKET_TIMEOUT_MS = 15_000;
    private static final int BACKLOG = 16;

    private final ApiRouter router;
    private final String bindAddress;
    private final int port;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private ExecutorService workers;

    public ApiServer(ApiRouter router, String bindAddress, int port) {
        this.router = router;
        this.bindAddress = bindAddress;
        this.port = port;
    }

    public boolean isRunning() {
        return running.get();
    }

    public String address() {
        return bindAddress + ":" + port;
    }

    /** @throws IOException when the port is taken or the address cannot be bound. */
    public void start() throws IOException {
        if (running.get()) return;
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new java.net.InetSocketAddress(InetAddress.getByName(bindAddress), port), BACKLOG);
        serverSocket = socket;
        workers = Executors.newFixedThreadPool(WORKERS);
        running.set(true);
        Thread t = new Thread(() -> acceptLoop(socket), "permafrost-api-accept");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "listening on " + address());
    }

    public void stop() {
        if (!running.getAndSet(false)) return;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        if (workers != null) workers.shutdownNow();
        serverSocket = null;
        workers = null;
        Log.i(TAG, "stopped");
    }

    private void acceptLoop(ServerSocket socket) {
        while (running.get()) {
            final Socket client;
            try {
                client = socket.accept();
            } catch (IOException e) {
                // A close() while blocked in accept() lands here; that is a stop, not a fault.
                if (running.get()) Log.w(TAG, "accept failed", e);
                return;
            }
            ExecutorService pool = workers;
            try {
                if (pool == null || pool.isShutdown()) throw new IllegalStateException("stopped");
                pool.execute(() -> serve(client));
            } catch (Exception e) {
                closeQuietly(client);
                if (pool == null || pool.isShutdown()) return;
            }
        }
    }

    private void serve(Socket socket) {
        try {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
            ApiResponse response;
            try {
                ApiRequest request = Http.parse(in);
                if (request == null) return;
                response = router.handle(request);
            } catch (ApiException e) {
                response = ApiResponse.error(e.status, e.getMessage(), e.hint);
            } catch (IOException e) {
                return;
            }
            write(out, response);
        } catch (Exception e) {
            Log.w(TAG, "request failed", e);
        } finally {
            closeQuietly(socket);
        }
    }

    private static void write(OutputStream out, ApiResponse r) throws IOException {
        StringBuilder head = new StringBuilder()
                .append("HTTP/1.1 ").append(r.status).append(' ').append(r.reason()).append("\r\n")
                .append("Content-Type: ").append(r.contentType).append("\r\n")
                .append("Content-Length: ").append(r.body.length).append("\r\n")
                .append("Connection: close\r\n")
                .append("Cache-Control: no-store\r\n")
                .append("Access-Control-Allow-Origin: *\r\n")
                .append("Access-Control-Allow-Headers: X-API-Key, Authorization, Content-Type\r\n")
                .append("Access-Control-Allow-Methods: GET, POST, PATCH, DELETE, OPTIONS\r\n");
        for (Map.Entry<String, String> h : r.extraHeaders.entrySet()) {
            head.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        }
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(r.body);
        out.flush();
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
