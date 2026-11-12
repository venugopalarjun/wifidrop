package io.wifidrop;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** Where received files are kept. Paths are relative, '/'-separated and already cleaned by Names. */
public interface Storage {
    /** Re-reads the destination and returns every file with its size. */
    Map<String, Long> list() throws IOException;

    /** Current size on disk, or -1 if the file does not exist. */
    long size(String path) throws IOException;

    /** Opens a file so that writing starts at offset; anything after offset is discarded. */
    Writer openForWrite(String path, long offset) throws IOException;

    InputStream openForRead(String path) throws IOException;

    interface Writer extends Closeable {
        void write(byte[] b, int off, int len) throws IOException;
        void finish(boolean sync) throws IOException;
    }

    /** The file on disk is shorter than the offset the sender wants to continue from. */
    class OffsetMismatch extends IOException {
        public final long actual;
        public OffsetMismatch(long actual) { super("size " + actual); this.actual = actual; }
    }

    /** Retrying will not help (for example the disk refuses this file name). */
    class Permanent extends IOException {
        public Permanent(String m) { super(m); }
    }
}
