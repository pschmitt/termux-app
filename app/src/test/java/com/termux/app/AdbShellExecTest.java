package com.termux.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class AdbShellExecTest {
    private static AdbShellExec.Request parse(String json) throws Exception {
        return AdbShellExec.parseRequest(json.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertInvalid(String json) {
        try {
            parse(json);
            fail("Accepted: " + json);
        } catch (Exception expected) {
            assertTrue(expected instanceof AdbShellExec.InvalidRequestException);
        }
    }

    @Test public void parsesShellOverrideAndLiteralArguments() throws Exception {
        AdbShellExec.Request request = parse(
            "{\"shell\":\"zsh\",\"argv\":[\"printf\",\"%s\\n\",\"a b\",\"$HOME;`id`\",\"'q'\\\"\",\"\\u00e4\\n\",\"\"]}");
        assertEquals("zsh", request.shell);
        assertEquals(Arrays.asList("printf", "%s\n", "a b", "$HOME;`id`", "'q'\"", "ä\n", ""), request.argv);
    }

    @Test public void shellNullOrAbsentUsesConfiguredShell() throws Exception {
        assertNull(parse("{\"shell\":null,\"argv\":[\"id\"]}").shell);
        assertNull(parse("{\"argv\":[\"id\"]}").shell);
        assertEquals("/data/data/x/usr/bin/fish", parse("{\"shell\":\"/data/data/x/usr/bin/fish\",\"argv\":[\"id\"]}").shell);
    }

    @Test public void rejectsInvalidShell() {
        assertInvalid("{\"shell\":\"\",\"argv\":[\"id\"]}");
        assertInvalid("{\"shell\":\"bash -c id\",\"argv\":[\"id\"]}");
        assertInvalid("{\"shell\":\"bash\\nLIST\",\"argv\":[\"id\"]}");
        assertInvalid("{\"shell\":7,\"argv\":[\"id\"]}");
        assertInvalid("{\"shell\":[\"bash\"],\"argv\":[\"id\"]}");
    }

    @Test public void rejectsInvalidArgv() {
        assertInvalid("{\"shell\":null}");
        assertInvalid("{\"argv\":[]}");
        assertInvalid("{\"argv\":\"id\"}");
        assertInvalid("{\"argv\":null}");
        assertInvalid("{\"argv\":[1]}");
        assertInvalid("{\"argv\":[\"id\",null]}");
        assertInvalid("{\"argv\":[\"id\",[\"x\"]]}");
        assertInvalid("{\"argv\":[\"id\",\"a\\u0000b\"]}");
        assertInvalid("{\"argv\":[\"\"]}");
        assertInvalid("{\"argv\":[\"-l\"]}");
    }

    @Test public void rejectsMalformedOrUnexpectedRequests() {
        assertInvalid("[\"id\"]");
        assertInvalid("\"id\"");
        assertInvalid("{\"argv\":[\"id\"]");
        assertInvalid("{\"argv\":[\"id\"]}x");
        assertInvalid("{\"argv\":[\"id\"],\"env\":{}}");
        assertInvalid("not json");
        assertInvalid("{}");
    }

    @Test public void rejectsInvalidUtf8AndEmptyPayload() {
        try {
            AdbShellExec.parseRequest(new byte[]{'{', '"', (byte) 0xff, '"', ':', '1', '}'});
            fail("Accepted invalid UTF-8");
        } catch (AdbShellExec.InvalidRequestException expected) {}
        try {
            AdbShellExec.parseRequest(new byte[0]);
            fail("Accepted empty payload");
        } catch (AdbShellExec.InvalidRequestException expected) {}
        try {
            AdbShellExec.parseRequest(new byte[AdbShellExec.MAX_REQUEST_BYTES + 1]);
            fail("Accepted oversize payload");
        } catch (AdbShellExec.InvalidRequestException expected) {}
    }

    @Test public void enforcesArgumentCount() throws Exception {
        StringBuilder atLimit = new StringBuilder("{\"argv\":[\"id\"");
        for (int i = 1; i < AdbShellExec.MAX_ARGUMENTS; i++) atLimit.append(",\"\"");
        assertEquals(AdbShellExec.MAX_ARGUMENTS, parse(atLimit + "]}").argv.size());
        assertInvalid(atLimit + ",\"\"]}");
    }

    private static byte[] frame(int length, byte[] body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) (length >>> 24), (byte) (length >>> 16), (byte) (length >>> 8), (byte) length});
        out.write(body);
        return out.toByteArray();
    }

    @Test public void requestFrameLengthIsBounded() throws Exception {
        byte[] body = "{\"argv\":[\"id\"]}".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(body, AdbShellExec.readRequestFrame(new ByteArrayInputStream(frame(body.length, body))));
        for (int length : new int[]{0, -1, AdbShellExec.MAX_REQUEST_BYTES + 1, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            try {
                AdbShellExec.readRequestFrame(new ByteArrayInputStream(frame(length, body)));
                fail("Accepted length " + length);
            } catch (AdbShellExec.InvalidRequestException expected) {}
        }
        byte[] max = new byte[AdbShellExec.MAX_REQUEST_BYTES];
        Arrays.fill(max, (byte) ' ');
        assertEquals(max.length, AdbShellExec.readRequestFrame(new ByteArrayInputStream(frame(max.length, max))).length);
        try {
            AdbShellExec.readRequestFrame(new ByteArrayInputStream(frame(body.length + 5, body)));
            fail("Accepted truncated frame");
        } catch (IOException expected) {}
    }

    @Test public void buildsLoginShellCommandKeepingArgumentsLiteral() {
        List<String> argv = Arrays.asList("echo", "a b", "$(id)");
        assertEquals(Arrays.asList("/bin/zsh", "-l", "-c", "exec \"$@\"", "termuxpp-command", "echo", "a b", "$(id)"),
            AdbShellExec.buildCommand("/bin/zsh", argv));
    }

    @Test public void buildsFishCommandWithArgvAndNoPlaceholder() {
        List<String> argv = Arrays.asList("echo", "a b", "$(id)", "--");
        assertEquals(Arrays.asList("/data/data/x/usr/bin/fish", "-l", "-c", "exec $argv", "echo", "a b", "$(id)", "--"),
            AdbShellExec.buildCommand("/data/data/x/usr/bin/fish", argv));
        assertEquals(Arrays.asList("fish", "-l", "-c", "exec $argv", "id"),
            AdbShellExec.buildCommand("fish", Arrays.asList("id")));
        // Only the exact basename selects fish.
        assertEquals("termuxpp-command", AdbShellExec.buildCommand("/usr/bin/fishy", Arrays.asList("id")).get(4));
        assertEquals("termuxpp-command", AdbShellExec.buildCommand("/usr/bin/bash", Arrays.asList("id")).get(4));
    }

    // --- Streaming (needs a POSIX shell; skipped elsewhere) ---

    /** The POSIX shell resolved from PATH (not assumed to live in /bin), or null when none is found. */
    private static final String SHELL = findOnPath("sh");

    private static String findOnPath(String name) {
        String path = System.getenv("PATH");
        if (path != null) {
            for (String directory : path.split(File.pathSeparator)) {
                if (directory.isEmpty()) continue;
                File candidate = new File(directory, name);
                if (candidate.isFile() && candidate.canExecute()) return candidate.getPath();
            }
        }
        return null;
    }

    private static List<String> sh(String... argv) {
        List<String> command = new ArrayList<>(Arrays.asList(SHELL, "-c", "exec \"$@\"", AdbShellExec.ARGV0));
        command.addAll(Arrays.asList(argv));
        return command;
    }

    private static java.util.Map<String, String> environment(String... extra) {
        java.util.Map<String, String> environment = new java.util.HashMap<>();
        String path = System.getenv("PATH");
        environment.put("PATH", path != null ? path : "/usr/bin:/bin");
        for (int i = 0; i + 1 < extra.length; i += 2) environment.put(extra[i], extra[i + 1]);
        return environment;
    }

    private static byte[] input(char type, byte[] data) throws IOException {
        return concat(new byte[]{(byte) type}, frame(data.length, data));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    /** Client input that stays open (like a real connection waiting for the X frame) until closed. */
    private static final class OpenInput {
        final PipedOutputStream writer = new PipedOutputStream();
        final InputStream reader;

        OpenInput() throws IOException {
            reader = new PipedInputStream(writer, 1 << 16);
        }
    }

    private static final class Decoded {
        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Integer exit;
        int frames;
    }

    private static Decoded decode(byte[] bytes) throws IOException {
        Decoded decoded = new Decoded();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        while (in.available() > 0) {
            int type = in.readUnsignedByte();
            decoded.frames++;
            if (type == 'X') {
                assertEquals("Exit frame carries a 4-byte value", 4, in.readInt());
                decoded.exit = in.readInt();
                assertEquals("Nothing may follow the exit frame", 0, in.available());
                continue;
            }
            int length = in.readInt();
            assertTrue("Chunk too large: " + length, length >= 0 && length <= AdbShellExec.MAX_CHUNK_BYTES);
            byte[] data = new byte[length];
            in.readFully(data);
            if (type == 'O') decoded.stdout.write(data);
            else if (type == 'R') decoded.stderr.write(data);
            else fail("Unexpected frame type " + type);
        }
        return decoded;
    }

    @Test public void streamsStdinStdoutStderrAndExitCode() throws Exception {
        assumeTrue(SHELL != null);
        OpenInput client = new OpenInput();
        client.writer.write(input('I', "hello ".getBytes(StandardCharsets.UTF_8)));
        client.writer.write(input('I', "world".getBytes(StandardCharsets.UTF_8)));
        client.writer.write(new byte[]{'E', 0, 0, 0, 0});
        client.writer.flush();
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        int exit = AdbShellExec.run(sh(SHELL, "-c", "cat; echo oops >&2; exit 7"), new File("/"),
            environment(), client.reader, wire);
        client.writer.close();
        assertEquals(7, exit);
        Decoded decoded = decode(wire.toByteArray());
        assertEquals("hello world", decoded.stdout.toString("UTF-8"));
        assertEquals("oops\n", decoded.stderr.toString("UTF-8"));
        assertEquals(Integer.valueOf(7), decoded.exit);
    }

    @Test public void argumentsAndEnvironmentReachTheCommandLiterally() throws Exception {
        assumeTrue(SHELL != null);
        OpenInput client = new OpenInput();
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        int exit = AdbShellExec.run(sh(SHELL, "-c", "printf '%s|%s|%s' \"$1\" \"$2\" \"$SHELL\"", "x", "$HOME `id`", "a b"),
            new File("/"), environment("SHELL", "/fake/shell"), client.reader, wire);
        client.writer.close();
        assertEquals(0, exit);
        assertEquals("$HOME `id`|a b|/fake/shell", decode(wire.toByteArray()).stdout.toString("UTF-8"));
    }

    @Test public void outputChunksAreBounded() throws Exception {
        assumeTrue(SHELL != null);
        OpenInput client = new OpenInput();
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        int exit = AdbShellExec.run(sh(SHELL, "-c", "i=0; while [ $i -lt 4000 ]; do printf '%0100d' 0; i=$((i+1)); done"),
            new File("/"), environment(), client.reader, wire);
        client.writer.close();
        assertEquals(0, exit);
        assertEquals(400_000, decode(wire.toByteArray()).stdout.size());
    }

    @Test public void clientDisconnectKillsTheCommandWithoutExitFrame() throws Exception {
        assumeTrue(SHELL != null);
        long start = System.nanoTime();
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        // Immediate EOF on the client side simulates a dropped or revoked connection.
        try {
            AdbShellExec.run(sh(SHELL, "-c", "while :; do sleep 1; done"), new File("/"),
                environment(), new ByteArrayInputStream(new byte[0]), wire);
            fail("A killed command must not be reported as a normal exit");
        } catch (IOException expected) {
        }
        assertTrue("Command was not killed", System.nanoTime() - start < 20_000_000_000L);
        assertEquals("No exit frame after a disconnect", 0, decodeExitFrames(wire.toByteArray()));
    }

    @Test public void protocolViolationKillsTheCommandWithoutExitFrame() throws Exception {
        assumeTrue(SHELL != null);
        for (byte[] bad : new byte[][]{
            {'Z', 0, 0, 0, 0},
            {'I', 0, 1, 0, 1},              // 65537 byte chunk
            {'E', 0, 0, 0, 1, 0},           // EOF frame with payload
            {'E', 0, 0, 0, 0, 'I', 0, 0, 0, 0}}) { // input after EOF
            OpenInput client = new OpenInput();
            client.writer.write(bad);
            client.writer.flush();
            long start = System.nanoTime();
            ByteArrayOutputStream wire = new ByteArrayOutputStream();
            try {
                AdbShellExec.run(sh(SHELL, "-c", "while :; do sleep 1; done"), new File("/"),
                    environment(), client.reader, wire);
                fail("A killed command must not be reported as a normal exit");
            } catch (IOException expected) {
            } finally {
                client.writer.close();
            }
            assertTrue("Command was not killed", System.nanoTime() - start < 20_000_000_000L);
            assertEquals(0, decodeExitFrames(wire.toByteArray()));
        }
    }

    @Test public void violationAfterOutputIsCompleteStillFailsInsteadOfReportingExit() throws Exception {
        assumeTrue(SHELL != null);
        // The command ends by itself, but the client already violated the protocol: every pump may be
        // finished by the time the process is reaped, and that must still not look like a success.
        OpenInput client = new OpenInput();
        client.writer.write(new byte[]{'Z', 0, 0, 0, 0});
        client.writer.flush();
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        try {
            AdbShellExec.run(sh(SHELL, "-c", "sleep 1; exit 3"), new File("/"), environment(), client.reader, wire);
            fail("Exit status after a protocol violation must not be reported");
        } catch (IOException expected) {
        } finally {
            client.writer.close();
        }
        assertEquals(0, decodeExitFrames(wire.toByteArray()));
    }

    private static int decodeExitFrames(byte[] bytes) throws IOException {
        int count = 0;
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        while (in.available() > 0) {
            int type = in.readUnsignedByte();
            int length = in.readInt();
            in.readFully(new byte[length]);
            if (type == 'X') count++;
        }
        return count;
    }

    @Test public void failedOutputWriteKillsTheCommand() throws Exception {
        assumeTrue(SHELL != null);
        OpenInput client = new OpenInput();
        OutputStream broken = new OutputStream() {
            @Override public void write(int b) throws IOException { throw new IOException("closed"); }
            @Override public void write(byte[] b, int off, int len) throws IOException { throw new IOException("closed"); }
        };
        long start = System.nanoTime();
        try {
            AdbShellExec.run(sh(SHELL, "-c", "while :; do echo x; done"), new File("/"),
                environment(), client.reader, broken);
            fail("Exit frame cannot be written to a broken connection");
        } catch (IOException expected) {
        } finally {
            client.writer.close();
        }
        assertTrue("Command was not killed", System.nanoTime() - start < 20_000_000_000L);
    }

    @Test public void startFailureIsReportedBeforeAnyStreaming() {
        try {
            AdbShellExec.start(sh("sh"), new File("/nonexistent-directory"), Collections.<String, String>emptyMap());
            fail("Started in a missing directory");
        } catch (IOException expected) {}
    }
}
