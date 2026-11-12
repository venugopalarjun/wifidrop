package io.wifidrop;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Small HTTP receiver. Every request must carry the pairing code in the X-Code header. */
public final class DropServer {
    public static final int PORT = 8765;
    private static final long SYNC_MIN = 4L << 20;

    private final Storage store;
    private final String code;
    private final Stats stats;
    private final int port;
    private volatile ServerSocket ss;
    private volatile boolean running;

    public DropServer(Storage store, String code, Stats stats, int port) {
        this.store = store; this.code = code; this.stats = stats; this.port = port;
    }

    public void start() throws IOException {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(port), 50);
        ss = s; running = true;
        Thread t = new Thread(this::acceptLoop, "drop-accept");
        t.setDaemon(true); t.start();
        Thread m = new Thread(() -> {
            while (running) { stats.sample(); try { Thread.sleep(1000); } catch (InterruptedException e) { return; } }
        }, "drop-meter");
        m.setDaemon(true); m.start();
    }

    public void stop() {
        running = false;
        try { if (ss != null) ss.close(); } catch (IOException ignored) {}
    }

    public boolean isRunning() { return running; }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket c = ss.accept();
                Thread t = new Thread(() -> serve(c), "drop-conn");
                t.setDaemon(true); t.start();
            } catch (IOException e) {
                if (running) stats.lastError = "listen: " + e.getMessage();
            }
        }
    }

    private void serve(Socket c) {
        try {
            c.setSoTimeout(60000);
            c.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(c.getInputStream(), 1 << 16);
            OutputStream out = c.getOutputStream();
            String line = readLine(in);
            if (line == null || line.isEmpty()) return;
            String[] rl = line.split(" ");
            if (rl.length < 2) { reply(out, 400, "bad request"); return; }
            Map<String, String> h = new HashMap<>();
            for (int n = 0; ; n++) {
                String l = readLine(in);
                if (l == null || l.isEmpty()) break;
                if (n > 200) { reply(out, 400, "too many headers"); return; }
                int i = l.indexOf(':');
                if (i > 0) h.put(l.substring(0, i).trim().toLowerCase(Locale.ROOT), l.substring(i + 1).trim());
            }
            String target = rl[1], route = target, query = "";
            int qi = target.indexOf('?');
            if (qi >= 0) { route = target.substring(0, qi); query = target.substring(qi + 1); }
            Map<String, String> q = new HashMap<>();
            for (String kv : query.split("&")) {
                if (kv.isEmpty()) continue;
                int i = kv.indexOf('=');
                String k = i < 0 ? kv : kv.substring(0, i), v = i < 0 ? "" : kv.substring(i + 1);
                q.put(k, URLDecoder.decode(v, "UTF-8"));
            }
            if (!code.equals(h.get("x-code"))) { reply(out, 401, "wrong code"); return; }
            try {
                route(rl[0], route, q, h, in, out);
            } catch (IllegalArgumentException e) {
                reply(out, 400, String.valueOf(e.getMessage()));
            }
        } catch (IOException e) {
            // connection dropped; the sender retries
        } finally {
            try { c.close(); } catch (IOException ignored) {}
        }
    }

    private void route(String method, String route, Map<String, String> q, Map<String, String> h,
                       InputStream in, OutputStream out) throws IOException {
        switch (route) {
            case "/ping":
                reply(out, 200, "wifidrop 1");
                return;
            case "/list": {
                Map<String, Long> m;
                try { m = store.list(); } catch (IOException e) { fail(out, "list", e); return; }
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, Long> e : m.entrySet()) sb.append(e.getValue()).append('\t').append(e.getKey()).append('\n');
                reply(out, 200, sb.toString());
                return;
            }
            case "/stat": {
                String p = Names.clean(q.get("path"));
                try { reply(out, 200, String.valueOf(store.size(p))); } catch (IOException e) { fail(out, "stat", e); }
                return;
            }
            case "/hash": {
                String p = Names.clean(q.get("path"));
                try (InputStream f = store.openForRead(p)) {
                    MessageDigest md = sha();
                    byte[] buf = new byte[1 << 20];
                    long total = 0;
                    for (int n; (n = f.read(buf)) > 0; ) { md.update(buf, 0, n); total += n; stats.lastActivity = System.currentTimeMillis(); }
                    reply(out, 200, hex(md.digest()) + " " + total);
                } catch (IOException e) { fail(out, "hash", e); }
                return;
            }
            case "/status":
                reply(out, 200, stats.received.get() + " " + stats.current.get() + " " + stats.speed);
                return;
            case "/plan":
                stats.plan(num(q, "files"), num(q, "bytes"), num(q, "done"));
                stats.log("Mac connected: " + num(q, "files") + " files in total");
                reply(out, 200, "ok");
                return;
            case "/finish":
                stats.finished = "1".equals(q.get("ok"));
                stats.log(stats.finished ? "Mac reports: everything is on the disk" : "Mac reports: some files still missing");
                reply(out, 200, "ok");
                return;
            case "/put":
                if (!"PUT".equals(method)) { reply(out, 405, "use PUT"); return; }
                put(Names.clean(q.get("path")), num(q, "offset"), h, in, out);
                return;
            default:
                reply(out, 404, "not found");
        }
    }

    private void put(String path, long offset, Map<String, String> h, InputStream in, OutputStream out) throws IOException {
        Storage.Writer w;
        try {
            w = store.openForWrite(path, offset);
        } catch (Storage.OffsetMismatch e) {
            reply(out, 409, "size " + e.actual); return;
        } catch (Storage.Permanent e) {
            stats.log("Skipped " + path + ": " + e.getMessage());
            reply(out, 422, String.valueOf(e.getMessage())); return;
        } catch (IOException e) {
            fail(out, "open " + path, e); return;
        }
        long n = 0;
        boolean stored = false;
        try {
            String exp = h.get("expect");
            if (exp != null && exp.toLowerCase(Locale.ROOT).contains("100-continue")) {
                out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
            InputStream body;
            String te = h.get("transfer-encoding");
            if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) body = new Chunked(in);
            else body = new Limited(in, h.containsKey("content-length") ? Long.parseLong(h.get("content-length")) : 0);
            stats.currentPath = path;
            stats.current.set(0);
            byte[] buf = new byte[1 << 20];
            for (int r; (r = body.read(buf, 0, buf.length)) > 0; ) {
                try { w.write(buf, 0, r); } catch (IOException e) { throw new DiskError(e); }
                n += r;
                stats.add(r);
            }
            try { w.finish(n >= SYNC_MIN); } catch (IOException e) { throw new DiskError(e); }
            stored = true;
        } catch (DiskError e) {
            fail(out, "write " + path, (IOException) e.getCause());
        } finally {
            try { w.close(); } catch (IOException ignored) {}
            stats.current.set(0);
        }
        if (stored) {
            stats.files.incrementAndGet();
            reply(out, 200, "ok " + (offset + n));
        }
    }

    private void fail(OutputStream out, String what, IOException e) throws IOException {
        String m = what + ": " + e.getMessage();
        stats.lastError = m;
        stats.log("Problem: " + m);
        reply(out, 500, m);
    }

    private static final class DiskError extends IOException { DiskError(IOException c) { super(c); } }

    private static long num(Map<String, String> q, String k) {
        try { return Long.parseLong(q.get(k)); } catch (RuntimeException e) { throw new IllegalArgumentException("bad " + k); }
    }

    private static MessageDigest sha() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return sb.toString();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(256);
        for (int c; (c = in.read()) >= 0; ) {
            if (c == '\n') return b.toString("ISO-8859-1");
            if (c != '\r') b.write(c);
            if (b.size() > 65536) throw new IOException("line too long");
        }
        return b.size() == 0 ? null : b.toString("ISO-8859-1");
    }

    private static void reply(OutputStream out, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " X\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    private static final class Limited extends InputStream {
        private final InputStream in; private long left;
        Limited(InputStream in, long n) { this.in = in; left = n; }
        @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) <= 0 ? -1 : one[0] & 255; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int r = in.read(b, off, (int) Math.min(len, left));
            if (r < 0) throw new EOFException("connection closed mid-file");
            left -= r;
            return r;
        }
    }

    private static final class Chunked extends InputStream {
        private final InputStream in; private long left; private boolean done;
        Chunked(InputStream in) { this.in = in; }
        @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) <= 0 ? -1 : one[0] & 255; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (done) return -1;
            if (left == 0) {
                String l = readLine(in);
                if (l == null) throw new EOFException("connection closed mid-file");
                int semi = l.indexOf(';');
                try { left = Long.parseLong((semi < 0 ? l : l.substring(0, semi)).trim(), 16); }
                catch (NumberFormatException e) { throw new IOException("bad chunk"); }
                if (left == 0) {
                    for (String t; (t = readLine(in)) != null && !t.isEmpty(); ) { /* trailers */ }
                    done = true;
                    return -1;
                }
            }
            int r = in.read(b, off, (int) Math.min(len, left));
            if (r < 0) throw new EOFException("connection closed mid-file");
            left -= r;
            if (left == 0) readLine(in);
            return r;
        }
    }
}
