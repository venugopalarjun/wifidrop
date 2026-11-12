import io.wifidrop.Storage;
import java.io.*;
import java.nio.channels.FileChannel;
import java.util.*;

/** Test stand-in for the phone's hard disk. */
public class FileStorage implements Storage {
    final File root;
    FileStorage(File r) { root = r; }
    public Map<String, Long> list() {
        Map<String, Long> m = new TreeMap<>();
        walk(root, "", m); return m;
    }
    void walk(File d, String rel, Map<String, Long> m) {
        File[] fs = d.listFiles(); if (fs == null) return;
        for (File f : fs) { String r = rel.isEmpty() ? f.getName() : rel + "/" + f.getName();
            if (f.isDirectory()) walk(f, r, m); else m.put(r, f.length()); }
    }
    public long size(String p) { File f = new File(root, p); return f.isFile() ? f.length() : -1; }
    public Writer openForWrite(String p, long off) throws IOException {
        File f = new File(root, p);
        if (p.contains("REFUSE")) throw new Permanent("disk refuses this name");
        if (!f.exists() && off > 0) throw new OffsetMismatch(-1);
        f.getParentFile().mkdirs();
        final RandomAccessFile raf = new RandomAccessFile(f, "rw");
        FileChannel ch = raf.getChannel();
        if (ch.size() < off) { long s = ch.size(); raf.close(); throw new OffsetMismatch(s); }
        ch.truncate(off); ch.position(off);
        return new Writer() {
            public void write(byte[] b, int o, int l) throws IOException { raf.write(b, o, l); }
            public void finish(boolean sync) throws IOException { if (sync) raf.getFD().sync(); }
            public void close() throws IOException { raf.close(); }
        };
    }
    public InputStream openForRead(String p) throws IOException { return new FileInputStream(new File(root, p)); }
}
