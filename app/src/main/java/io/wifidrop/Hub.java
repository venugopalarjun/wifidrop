package io.wifidrop;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;

import java.io.IOException;
import java.security.SecureRandom;

/** Shared state between the screen and the background service. */
final class Hub {
    static final Stats stats = new Stats();
    private static DropServer server;

    private Hub() {}

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("wifidrop", Context.MODE_PRIVATE); }

    static String code(Context c) {
        String code = prefs(c).getString("code", null);
        if (code == null) {
            code = String.format("%06d", new SecureRandom().nextInt(1000000));
            prefs(c).edit().putString("code", code).apply();
        }
        return code;
    }

    /** The chosen destination folder, or null if none is chosen or access to it was lost. */
    static Uri tree(Context c) {
        String s = prefs(c).getString("tree", null);
        if (s == null) return null;
        Uri u = Uri.parse(s);
        for (UriPermission p : c.getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(u) && p.isWritePermission()) return u;
        }
        return null;
    }

    static synchronized boolean running() { return server != null && server.isRunning(); }

    /** Returns null on success, otherwise a message for the user. */
    static synchronized String start(Context app) {
        if (running()) return null;
        Uri tree = tree(app);
        if (tree == null) return "Choose a folder on the hard disk first.";
        DropServer s = new DropServer(new SafStorage(app.getContentResolver(), tree), code(app), stats, DropServer.PORT);
        try {
            s.start();
        } catch (IOException e) {
            return "Could not start receiving: " + e.getMessage();
        }
        server = s;
        stats.plan(0, 0, 0);
        stats.log("Ready. Waiting for the Mac.");
        return null;
    }

    static synchronized void stop() {
        if (server != null) server.stop();
        server = null;
    }
}
