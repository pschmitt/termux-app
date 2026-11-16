package com.termux.terminal;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** A raw byte-stream PTY subprocess for non-rendering terminal clients. */
public final class PtyProcess implements AutoCloseable {

    private final int fileDescriptor;
    private final int processId;
    private final ParcelFileDescriptor parcelFileDescriptor;
    private final FileInputStream inputStream;
    private final FileOutputStream outputStream;

    private PtyProcess(int fileDescriptor, int processId) throws IOException {
        this.fileDescriptor = fileDescriptor;
        this.processId = processId;
        parcelFileDescriptor = ParcelFileDescriptor.adoptFd(fileDescriptor);
        FileDescriptor descriptor = parcelFileDescriptor.getFileDescriptor();
        inputStream = new FileInputStream(descriptor);
        outputStream = new FileOutputStream(descriptor);
    }

    public static PtyProcess start(String command, String cwd, String[] args, String[] environment,
                                   int rows, int columns) throws IOException {
        int[] pid = new int[1];
        int fd = JNI.createSubprocess(command, cwd, args, environment, pid, rows, columns, 0, 0);
        if (fd < 0 || pid[0] <= 0) {
            throw new IOException("Unable to start PTY subprocess");
        }
        return new PtyProcess(fd, pid[0]);
    }

    public FileInputStream getInputStream() {
        return inputStream;
    }

    public FileOutputStream getOutputStream() {
        return outputStream;
    }

    public int getProcessId() {
        return processId;
    }

    public void resize(int rows, int columns) {
        JNI.setPtyWindowSize(fileDescriptor, rows, columns, 0, 0);
    }

    public int waitFor() {
        return JNI.waitFor(processId);
    }

    public void terminate() throws IOException {
        try {
            Os.kill(processId, OsConstants.SIGTERM);
        } catch (ErrnoException e) {
            throw new IOException("Unable to terminate PTY subprocess", e);
        }
    }

    @Override
    public void close() throws IOException {
        parcelFileDescriptor.close();
    }
}
