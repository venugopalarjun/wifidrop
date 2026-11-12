package io.wifidrop;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;

/** Stores files in the folder the user picked on the attached disk, through Android's document picker access. */
public final class SafStorage implements Storage {
    private static final String[] PROJ = {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE};

    private final ContentResolver cr;
    private final Uri tree;
    private final HashMap<String, String> dirs = new HashMap<>();   // relative path -> document id
    private final HashMap<String, String> files = new HashMap<>();
    private final HashSet<String> loaded = new HashSet<>();         // folders whose children are cached

    public SafStorage(ContentResolver cr, Uri tree) {
        this.cr = cr;
        this.tree = tree;
        dirs.put("", DocumentsContract.getTreeDocumentId(tree));
    }

    @Override public synchronized Map<String, Long> list() throws IOException {
        String root = dirs.get("");
        dirs.clear(); files.clear(); loaded.clear();
        dirs.put("", root);
        TreeMap<String, Long> out = new TreeMap<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add("");
        while (!queue.isEmpty()) loadDir(queue.poll(), out, queue);
        return out;
    }

    private void loadDir(String rel, Map<String, Long> sizes, ArrayDeque<String> queue) throws IOException {
        Cursor c;
        try {
            c = cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirs.get(rel)), PROJ, null, null, null);
        } catch (RuntimeException e) {
            throw new IOException("cannot read the disk (" + e.getMessage() + ")");
        }
        if (c == null) throw new IOException("cannot read the disk");
        try {
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                if (id == null || name == null) continue;
                String p = rel.isEmpty() ? name : rel + "/" + name;
                if (Document.MIME_TYPE_DIR.equals(mime)) {
                    dirs.put(p, id);
                    if (queue != null) queue.add(p);
                } else {
                    files.put(p, id);
                    if (sizes != null) sizes.put(p, c.isNull(3) ? 0L : c.getLong(3));
                }
            }
        } finally {
            c.close();
        }
        loaded.add(rel);
    }

    private static String parentOf(String p) { int i = p.lastIndexOf('/'); return i < 0 ? "" : p.substring(0, i); }
    private static String nameOf(String p) { return p.substring(p.lastIndexOf('/') + 1); }

    private String dir(String rel) throws IOException {
        String id = dirs.get(rel);
        if (id != null) return id;
        String parent = parentOf(rel);
        String pid = dir(parent);
        if (!loaded.contains(parent)) {
            loadDir(parent, null, null);
            id = dirs.get(rel);
            if (id != null) return id;
        }
        if (files.containsKey(rel)) throw new Permanent("a file already has this folder's name");
        id = create(pid, Document.MIME_TYPE_DIR, nameOf(rel));
        dirs.put(rel, id);
        loaded.add(rel);
        return id;
    }

    private String create(String parentId, String mime, String name) throws IOException {
        Uri made;
        try {
            made = DocumentsContract.createDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, parentId), mime, name);
        } catch (Exception e) {
            throw new IOException("cannot create " + name + " (" + e.getMessage() + ")");
        }
        if (made == null) throw new IOException("cannot create " + name);
        String actual = displayName(made);
        if (actual != null && !same(actual, name)) {
            try {
                Uri r = DocumentsContract.renameDocument(cr, made, name);
                if (r != null) made = r;
                actual = displayName(made);
            } catch (Exception ignored) { }
            if (actual == null || !same(actual, name)) {
                try { DocumentsContract.deleteDocument(cr, made); } catch (Exception ignored) { }
                throw new Permanent("the disk does not accept this name");
            }
        }
        return DocumentsContract.getDocumentId(made);
    }

    private static boolean same(String a, String b) {
        return a.equals(b) || Normalizer.normalize(a, Normalizer.Form.NFC).equals(Normalizer.normalize(b, Normalizer.Form.NFC));
    }

    private String displayName(Uri doc) {
        try (Cursor c = cr.query(doc, new String[]{Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            return c != null && c.moveToFirst() ? c.getString(0) : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override public long size(String path) throws IOException {
        String id;
        synchronized (this) {
            String parent = parentOf(path);
            if (dirs.containsKey(parent) && !loaded.contains(parent)) loadDir(parent, null, null);
            id = files.get(path);
        }
        if (id == null) return -1;
        try (Cursor c = cr.query(DocumentsContract.buildDocumentUriUsingTree(tree, id),
                new String[]{Document.COLUMN_SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.isNull(0) ? 0 : c.getLong(0);
        } catch (Exception e) {
            // fall through: treat as missing
        }
        forget(path);
        return -1;
    }

    private synchronized void forget(String path) {
        files.remove(path);
        loaded.remove(parentOf(path));
    }

    @Override public Writer openForWrite(String path, long offset) throws IOException {
        String id;
        synchronized (this) {
            String parent = parentOf(path);
            String pid = dir(parent);
            if (!loaded.contains(parent)) loadDir(parent, null, null);
            id = files.get(path);
            if (id == null) {
                if (dirs.containsKey(path)) throw new Permanent("a folder already has this file's name");
                if (offset > 0) throw new OffsetMismatch(-1);
                id = create(pid, "application/octet-stream", nameOf(path));
                files.put(path, id);
            }
        }
        final ParcelFileDescriptor pfd;
        try {
            pfd = cr.openFileDescriptor(DocumentsContract.buildDocumentUriUsingTree(tree, id), "rw");
        } catch (Exception e) {
            forget(path);
            throw new IOException("cannot open " + path + " (" + e.getMessage() + ")");
        }
        if (pfd == null) { forget(path); throw new IOException("cannot open " + path); }
        final FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor());
        try {
            FileChannel ch = fos.getChannel();
            long cur = ch.size();
            if (cur < offset) throw new OffsetMismatch(cur);
            if (cur > offset) ch.truncate(offset);
            ch.position(offset);
        } catch (IOException e) {
            try { fos.close(); } catch (IOException ignored) { }
            try { pfd.close(); } catch (IOException ignored) { }
            throw e;
        }
        return new Writer() {
            @Override public void write(byte[] b, int off, int len) throws IOException { fos.write(b, off, len); }
            @Override public void finish(boolean sync) throws IOException {
                fos.flush();
                if (sync) { try { fos.getFD().sync(); } catch (IOException ignored) { } }
            }
            @Override public void close() throws IOException {
                try { fos.close(); } finally { pfd.close(); }
            }
        };
    }

    @Override public InputStream openForRead(String path) throws IOException {
        String id;
        synchronized (this) {
            String parent = parentOf(path);
            if (dirs.containsKey(parent) && !loaded.contains(parent)) loadDir(parent, null, null);
            id = files.get(path);
        }
        if (id == null) throw new IOException("not on the disk: " + path);
        InputStream in;
        try {
            in = cr.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id));
        } catch (Exception e) {
            forget(path);
            throw new IOException("cannot read " + path + " (" + e.getMessage() + ")");
        }
        if (in == null) throw new IOException("cannot read " + path);
        return in;
    }
}
