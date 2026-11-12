package io.wifidrop;

/** Turns a sender path into one that FAT/exFAT/NTFS disks accept. The Mac script applies the same rule. */
public final class Names {
    private Names() {}

    public static String clean(String path) {
        if (path == null) throw new IllegalArgumentException("missing path");
        String[] parts = path.split("/", -1);
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty() || p.equals(".") || p.equals("..")) throw new IllegalArgumentException("bad path");
            if (out.length() > 0) out.append('/');
            out.append(component(p));
        }
        if (out.length() == 0) throw new IllegalArgumentException("bad path");
        return out.toString();
    }

    static String component(String c) {
        char[] a = c.toCharArray();
        for (int i = 0; i < a.length; i++) {
            char ch = a[i];
            if (ch < 0x20 || ch == 0x7f || "\\:*?\"<>|".indexOf(ch) >= 0) a[i] = '_';
        }
        for (int i = a.length - 1; i >= 0 && (a[i] == '.' || a[i] == ' '); i--) a[i] = '_';
        return new String(a);
    }
}
