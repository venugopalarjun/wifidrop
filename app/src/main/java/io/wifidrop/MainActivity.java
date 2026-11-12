package io.wifidrop;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int PICK = 1;
    private static final double GB = 1024.0 * 1024 * 1024;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView folderView, commandView, statusView;
    private Button toggle;
    private ProgressBar bar;
    private String message = "";

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        col.addView(text("WifiDrop", 26, true));
        col.addView(text("Receives files from a Mac over Wi-Fi and saves them to the hard disk plugged into this phone.", 15, false));

        col.addView(gap(pad));
        col.addView(text("Step 1", 18, true));
        Button pick = new Button(this);
        pick.setText("Choose a folder on the hard disk");
        pick.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK));
        col.addView(pick);
        folderView = text("", 15, false);
        col.addView(folderView);

        col.addView(gap(pad));
        col.addView(text("Step 2", 18, true));
        toggle = new Button(this);
        toggle.setOnClickListener(v -> onToggle());
        col.addView(toggle);

        col.addView(gap(pad));
        col.addView(text("Step 3: on the Mac, open Terminal and run", 18, true));
        commandView = text("", 15, false);
        commandView.setTypeface(Typeface.MONOSPACE);
        commandView.setTextIsSelectable(true);
        col.addView(commandView);

        col.addView(gap(pad));
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        col.addView(bar);
        statusView = text("", 15, false);
        col.addView(statusView);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(col);
        setContentView(scroll);
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 8, 0, 8);
        return t;
    }

    private View gap(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, h));
        return v;
    }

    @Override protected void onResume() { super.onResume(); handler.post(tick); }
    @Override protected void onPause() { handler.removeCallbacks(tick); super.onPause(); }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            Hub.prefs(this).edit().putString("tree", u.toString()).apply();
            message = "";
            if (Hub.running()) {       // switch to the new folder
                stopService(new Intent(this, DropService.class));
                Hub.stop();
            }
        } catch (Exception e) {
            message = "Could not get access to that folder: " + e.getMessage();
        }
        refresh();
    }

    private void onToggle() {
        Intent svc = new Intent(this, DropService.class);
        if (Hub.running()) {
            stopService(svc);
            Hub.stop();
        } else {
            if (Hub.tree(this) == null) { message = "Choose a folder on the hard disk first (Step 1)."; refresh(); return; }
            message = "";
            if (Build.VERSION.SDK_INT >= 33) {
                try { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2); } catch (Exception ignored) { }
            }
            try {
                startForegroundService(svc);
            } catch (Exception e) {
                String err = Hub.start(getApplicationContext());
                if (err != null) message = err;
            }
        }
        handler.postDelayed(this::refresh, 300);
    }

    private void refresh() {
        boolean running = Hub.running();
        Uri tree = Hub.tree(this);
        if (tree == null) {
            folderView.setText("No folder chosen yet.");
        } else {
            String id = DocumentsContract.getTreeDocumentId(tree);
            int i = id.indexOf(':');
            String vol = i < 0 ? id : id.substring(0, i), path = i < 0 ? "" : id.substring(i + 1);
            String where = (path.isEmpty() ? "top level" : path) + "  (disk " + vol + ")";
            if (vol.equals("primary")) where += "\nWarning: this is the phone's own storage, not the hard disk.";
            folderView.setText("Saving to: " + where);
        }
        toggle.setText(running ? "Stop receiving" : "Start receiving");
        if (running) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String ip = wifiAddress();
        commandView.setText(ip == null ? "Connect this phone to Wi-Fi first."
                : "bash ~/Downloads/wifidrop.sh " + ip + " " + Hub.code(this));

        Stats s = Hub.stats;
        StringBuilder sb = new StringBuilder();
        if (!message.isEmpty()) sb.append(message).append("\n\n");
        if (!running) {
            sb.append("Not receiving.");
            if (!s.lastError.isEmpty()) sb.append("\n").append(s.lastError);
            bar.setProgress(0);
        } else if (s.planBytes <= 0) {
            sb.append("Receiving. Waiting for the Mac to connect.");
            bar.setProgress(0);
        } else {
            long done = Math.min(s.planBytes, s.planDone + s.received.get());
            bar.setProgress((int) (done * 1000 / s.planBytes));
            if (s.finished) {
                sb.append("Finished. Everything is on the hard disk.\nTap Stop receiving, then eject the disk in the phone's settings before unplugging it.\n\n");
            }
            sb.append(String.format(Locale.US, "%.2f of %.2f GB (%d%%)\n", done / GB, s.planBytes / GB, done * 100 / s.planBytes));
            boolean active = System.currentTimeMillis() - s.lastActivity < 5000;
            sb.append(String.format(Locale.US, "Speed: %.1f MB/s\n", active ? s.speed / 1048576.0 : 0.0));
            sb.append("Files received this run: ").append(s.files.get()).append(" (Mac has ").append(s.planFiles).append(" in total)\n");
            if (active && !s.currentPath.isEmpty()) sb.append("Now: ").append(s.currentPath).append("\n");
        }
        String log = s.logText();
        if (running && !log.isEmpty()) sb.append("\n").append(log);
        statusView.setText(sb.toString());
    }

    private static String wifiAddress() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    if (ni.getName().startsWith("wlan")) return a.getHostAddress();
                    if (fallback == null) fallback = a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return fallback;
    }
}
