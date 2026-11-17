package com.termux.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Non-PTY command execution for the authenticated ADB endpoint ({@code EXEC}).
 *
 * <p>Wire format after the {@code OK} line (all integers are network order):
 * <ul>
 *   <li>Client to server: {@code 'I'} + int length + data (stdin), {@code 'E'} + int 0 (stdin EOF).</li>
 *   <li>Server to client: {@code 'O'}/{@code 'R'} + int length + data (stdout/stderr) and finally
 *       {@code 'X'} + int length 4 + signed int exit code (uniform framing).</li>
 * </ul>
 */
final class AdbShellExec {
    static final int MAX_REQUEST_BYTES = 64 * 1024;
    static final int MAX_CHUNK_BYTES = 64 * 1024;
    static final int MAX_ARGUMENTS = 4096;
    static final int MAX_SHELL_CHARS = 4096;
    static final String ARGV0 = "termuxpp-command";

    private static final int OUTPUT_BUFFER_BYTES = 16 * 1024;
    private static final long ABORT_POLL_MILLIS = 100;

    /** A validated EXEC request. */
    static final class Request {
        /** Shell override, or null to use the configured shell. */
        final String shell;
        final List<String> argv;

        Request(String shell, List<String> argv) {
            this.shell = shell;
            this.argv = Collections.unmodifiableList(new ArrayList<>(argv));
        }
    }

    static final class InvalidRequestException extends IOException {
        InvalidRequestException(String message) {
            super(message);
        }
    }

    /** Reads the length-prefixed request frame, enforcing {@link #MAX_REQUEST_BYTES}. */
    static byte[] readRequestFrame(InputStream input) throws IOException {
        DataInputStream data = new DataInputStream(input);
        int length = data.readInt();
        if (length < 1 || length > MAX_REQUEST_BYTES) {
            throw new InvalidRequestException("Invalid EXEC request length");
        }
        byte[] payload = new byte[length];
        data.readFully(payload);
        return payload;
    }

    /** Strictly parses {@code {"shell": name|null, "argv": [...]}}. */
    static Request parseRequest(byte[] payload) throws InvalidRequestException {
        if (payload == null || payload.length < 1 || payload.length > MAX_REQUEST_BYTES) {
            throw new InvalidRequestException("Invalid EXEC request length");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload)).toString();
        } catch (CharacterCodingException error) {
            throw new InvalidRequestException("EXEC request is not UTF-8");
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw new InvalidRequestException("EXEC request is not a JSON object");
        }

        JSONObject root;
        try {
            JSONTokener tokener = new JSONTokener(trimmed);
            Object value = tokener.nextValue();
            if (!(value instanceof JSONObject)) throw new InvalidRequestException("EXEC request is not an object");
            if (tokener.nextClean() != 0) throw new InvalidRequestException("Trailing data after EXEC request");
            root = (JSONObject) value;
        } catch (JSONException error) {
            throw new InvalidRequestException("Malformed EXEC request");
        }

        Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!"shell".equals(key) && !"argv".equals(key)) {
                throw new InvalidRequestException("Unknown EXEC request field");
            }
        }

        String shell = null;
        Object shellValue = root.opt("shell");
        if (shellValue != null && !JSONObject.NULL.equals(shellValue)) {
            if (!(shellValue instanceof String)) throw new InvalidRequestException("Invalid shell");
            shell = (String) shellValue;
            if (shell.isEmpty() || shell.length() > MAX_SHELL_CHARS || !shell.matches("[!-~]+")) {
                throw new InvalidRequestException("Invalid shell");
            }
        }

        Object argvValue = root.opt("argv");
        if (!(argvValue instanceof JSONArray)) throw new InvalidRequestException("argv must be an array");
        JSONArray array = (JSONArray) argvValue;
        if (array.length() < 1 || array.length() > MAX_ARGUMENTS) {
            throw new InvalidRequestException("Invalid argv length");
        }
        List<String> argv = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            Object element = array.opt(i);
            if (!(element instanceof String)) throw new InvalidRequestException("argv entries must be strings");
            String argument = (String) element;
            if (argument.indexOf('\0') >= 0) throw new InvalidRequestException("argv entries must not contain NUL");
            argv.add(argument);
        }
        // The command runs via `exec "$@"`; an empty or option-like command would be interpreted by exec itself.
        if (argv.get(0).isEmpty() || argv.get(0).startsWith("-")) {
            throw new InvalidRequestException("Invalid command");
        }
        return new Request(shell, argv);
    }

    /**
     * POSIX shells: {@code SHELL -l -c 'exec "$@"' termuxpp-command ARGV...}. Fish does not understand
     * {@code "$@"}; it populates {@code $argv} from every argument after the {@code -c} script, so it gets
     * {@code fish -l -c 'exec $argv' ARGV...} (no placeholder). Either way the argv entries stay literal.
     */
    static List<String> buildCommand(String shell, List<String> argv) {
        List<String> command = new ArrayList<>(argv.size() + 5);
        command.add(shell);
        command.add("-l");
        command.add("-c");
        if (isFish(shell)) {
            command.add("exec $argv");
        } else {
            command.add("exec \"$@\"");
            command.add(ARGV0);
        }
        command.addAll(argv);
        return command;
    }

    static boolean isFish(String shell) {
        return "fish".equals(new File(shell).getName());
    }

    static Process start(List<String> command, File workingDirectory, Map<String, String> environment)
        throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory);
        Map<String, String> target = builder.environment();
        target.clear();
        target.putAll(environment);
        return builder.start();
    }

    /** Starts {@code command} and streams it over the framed protocol; returns the exit code. */
    static int run(List<String> command, File workingDirectory, Map<String, String> environment,
                   InputStream clientInput, OutputStream clientOutput) throws IOException {
        return stream(start(command, workingDirectory, environment), clientInput, clientOutput);
    }

    /**
     * Pumps {@code process} over the framed protocol and always reaps it: a client disconnect, a protocol
     * violation or a failed write kills the process. Sends the {@code X} frame once it exited.
     */
    static int stream(final Process process, InputStream clientInput, OutputStream clientOutput) throws IOException {
        final FrameWriter writer = new FrameWriter(clientOutput);
        final DataInputStream input = new DataInputStream(clientInput);
        final AtomicBoolean aborted = new AtomicBoolean();
        Thread stdout = daemon("TermuxAdbExecStdout",
            () -> pumpOutput(process, process.getInputStream(), writer, 'O', aborted));
        Thread stderr = daemon("TermuxAdbExecStderr",
            () -> pumpOutput(process, process.getErrorStream(), writer, 'R', aborted));
        Thread stdin = daemon("TermuxAdbExecStdin", () -> pumpInput(process, input, aborted));
        stdout.start();
        stderr.start();
        stdin.start();
        try {
            int exitCode = process.waitFor();
            // Drain both streams completely before X so output is never truncated. A detached grandchild
            // that keeps a pipe open therefore keeps the command open until the client disconnects (the
            // input pump flags that), in which case no X is sent.
            joinOrAbort(stdout, aborted);
            joinOrAbort(stderr, aborted);
            // The pumps may all have finished after a disconnect/violation killed the process: that exit
            // status is not a result the client may treat as a success.
            if (aborted.get()) throw new IOException("Command aborted before completion");
            writer.writeExit(exitCode);
            return exitCode;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while running command");
        } finally {
            if (killIfRunning(process)) reap(process);
            closeQuietly(process.getOutputStream());
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
        }
    }

    private static void pumpOutput(Process process, InputStream source, FrameWriter writer, int type,
                                   AtomicBoolean aborted) {
        byte[] buffer = new byte[OUTPUT_BUFFER_BYTES];
        try {
            int count;
            while ((count = source.read(buffer)) != -1) {
                writer.write(type, buffer, count);
            }
        } catch (IOException error) {
            // Client gone (or our own cleanup closed the stream): never leave the command running.
            aborted.set(true);
            killIfRunning(process);
        }
    }

    private static void pumpInput(Process process, DataInputStream input, AtomicBoolean aborted) {
        OutputStream stdin = process.getOutputStream();
        boolean stdinBroken = false;
        boolean eof = false;
        byte[] buffer = new byte[OUTPUT_BUFFER_BYTES];
        try {
            while (true) {
                int type = input.read();
                if (type < 0) throw new EOFException("Client disconnected");
                int length = input.readInt();
                if (length < 0 || length > MAX_CHUNK_BYTES) throw new IOException("Invalid input frame length");
                if (eof) throw new IOException("Input after EOF");
                if (type == 'I') {
                    int remaining = length;
                    while (remaining > 0) {
                        int count = input.read(buffer, 0, Math.min(buffer.length, remaining));
                        if (count < 0) throw new EOFException("Client disconnected");
                        remaining -= count;
                        if (!stdinBroken) {
                            try {
                                stdin.write(buffer, 0, count);
                                stdin.flush();
                            } catch (IOException closed) {
                                // The command stopped reading; keep draining so the client sees its exit code.
                                stdinBroken = true;
                            }
                        }
                    }
                } else if (type == 'E') {
                    if (length != 0) throw new IOException("Invalid EOF frame");
                    eof = true;
                    closeQuietly(stdin);
                } else {
                    throw new IOException("Unknown input frame");
                }
            }
        } catch (IOException error) {
            // Disconnect, revocation or protocol violation. After a normal exit this is a no-op.
            aborted.set(true);
            killIfRunning(process);
        }
    }

    private static final class FrameWriter {
        private final OutputStream output;

        FrameWriter(OutputStream output) {
            this.output = output;
        }

        synchronized void write(int type, byte[] data, int length) throws IOException {
            byte[] frame = new byte[5 + length];
            frame[0] = (byte) type;
            putInt(frame, 1, length);
            System.arraycopy(data, 0, frame, 5, length);
            output.write(frame);
            output.flush();
        }

        synchronized void writeExit(int exitCode) throws IOException {
            byte[] frame = new byte[9];
            frame[0] = 'X';
            putInt(frame, 1, 4);
            putInt(frame, 5, exitCode);
            output.write(frame);
            output.flush();
        }

        private static void putInt(byte[] target, int offset, int value) {
            target[offset] = (byte) (value >>> 24);
            target[offset + 1] = (byte) (value >>> 16);
            target[offset + 2] = (byte) (value >>> 8);
            target[offset + 3] = (byte) value;
        }
    }

    private static Thread daemon(String name, Runnable runnable) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void joinOrAbort(Thread thread, AtomicBoolean aborted) throws InterruptedException, IOException {
        while (thread.isAlive()) {
            thread.join(ABORT_POLL_MILLIS);
            if (aborted.get() && thread.isAlive()) {
                throw new IOException("Client disconnected before command output was drained");
            }
        }
    }

    /** Returns true when the process was still running and has been told to die. */
    private static boolean killIfRunning(Process process) {
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException running) {
            process.destroy();
            return true;
        }
    }

    /** Waits (uninterruptibly, bounded) for a destroyed process so it does not linger as a zombie. */
    private static void reap(Process process) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                process.exitValue();
                break;
            } catch (IllegalThreadStateException running) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    private AdbShellExec() {}
}
