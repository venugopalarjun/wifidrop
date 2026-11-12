package io.wifidrop;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;

/** Live numbers shown on the phone and reported to the Mac. */
public final class Stats {
    public final AtomicLong received = new AtomicLong();   // bytes since the plan was announced
    public final AtomicLong current = new AtomicLong();    // bytes of the file in flight
    public final AtomicLong files = new AtomicLong();      // files completed since the plan
    public volatile String currentPath = "";
    public volatile long planFiles, planBytes, planDone;   // totals announced by the Mac
    public volatile boolean finished;
    public volatile String lastError = "";
    public volatile long speed;                            // bytes per second
    public volatile long lastActivity;

    private long sampleBytes, sampleTime;
    private final AtomicLong lifetime = new AtomicLong();
    private final ArrayDeque<String> log = new ArrayDeque<>();

    public void add(long n) {
        received.addAndGet(n); current.addAndGet(n); lifetime.addAndGet(n);
        lastActivity = System.currentTimeMillis();
    }

    public synchronized void sample() {
        long now = System.currentTimeMillis(), b = lifetime.get();
        if (sampleTime != 0 && now > sampleTime) speed = (b - sampleBytes) * 1000 / (now - sampleTime);
        sampleBytes = b; sampleTime = now;
    }

    public void plan(long f, long b, long done) {
        planFiles = f; planBytes = b; planDone = done;
        received.set(0); files.set(0); finished = false; lastError = "";
    }

    public synchronized void log(String s) {
        log.addLast(s);
        while (log.size() > 6) log.removeFirst();
    }

    public synchronized String logText() {
        StringBuilder sb = new StringBuilder();
        for (String s : log) sb.append(s).append('\n');
        return sb.toString();
    }
}
