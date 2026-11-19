package com.termux.app;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Local session controls for processes running under the TermuxPP Android UID. */
final class SessionCommandServer implements AutoCloseable {
    private static final int MAX_REQUEST_BYTES = 4096;
    private final LocalServerSocket listener;
    private volatile boolean closed;

    SessionCommandServer(TermuxService service) throws IOException {
        listener = new LocalServerSocket("dev.pschmitt.termux.sessions");
        Handler main = new Handler(Looper.getMainLooper());
        Thread thread = new Thread(() -> {
            while (!closed) {
                LocalSocket client;
                try {
                    client = listener.accept();
                } catch (IOException e) {
                    break;
                }
                try (LocalSocket connection = client) {
                    if (connection.getPeerCredentials().getUid() != Process.myUid()) continue;
                    connection.setSoTimeout(2000);
                    JSONObject response = new JSONObject();
                    try {
                        JSONArray arguments = new JSONArray(readRequest(connection));
                        FutureTask<Object> command = new FutureTask<>(() -> {
                            if (closed) throw new IllegalStateException("TermuxPP service is stopping");
                            return service.runSessionCommand(arguments);
                        });
                        if (!main.post(command)) throw new IllegalStateException("TermuxPP service is stopping");
                        try {
                            response.put("result", command.get(2, TimeUnit.SECONDS));
                        } catch (TimeoutException | InterruptedException e) {
                            command.cancel(false);
                            throw new IllegalStateException("TermuxPP did not respond in time");
                        }
                    } catch (Exception e) {
                        Throwable error = e instanceof ExecutionException ? e.getCause() : e;
                        response.put("error", error.getMessage() == null ? "Session command failed" : error.getMessage());
                    }
                    connection.getOutputStream().write((response + "\n").getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                    // A disconnected or unauthorized client does not stop the listener.
                }
            }
        }, "TermuxPP session commands");
        thread.setDaemon(true);
        thread.start();
    }

    private static String readRequest(LocalSocket connection) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        InputStream input = new BufferedInputStream(connection.getInputStream());
        for (int i = 0; i < MAX_REQUEST_BYTES; i++) {
            int value = input.read();
            if (value == '\n') return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            if (value == -1) break;
            bytes.write(value);
        }
        throw new IOException("Incomplete or oversized session command");
    }

    @Override
    public void close() throws IOException {
        closed = true;
        listener.close();
    }
}
