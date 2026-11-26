package de.foxcenter.app;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.os.Environment;
import android.provider.Settings;
import android.graphics.Color;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Fox Center: Mediathek vom Handy, Vorschaubilder, Vollbild-Player, Netzwerk (HTTP, WebDAV, DLNA)
@CapacitorPlugin(name = "Center")
public class CenterPlugin extends Plugin {

    private final Handler main = new Handler(Looper.getMainLooper());

    private static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    // ---------------- Berechtigung ----------------
    private String[] mediaPerms() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{"android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO", "android.permission.READ_MEDIA_IMAGES"};
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    private boolean hasPerm(String p) {
        return ContextCompat.checkSelfPermission(getContext(), p) == PackageManager.PERMISSION_GRANTED;
    }

    @PluginMethod
    public void perm(PluginCall call) {
        JSObject o = new JSObject();
        if (Build.VERSION.SDK_INT >= 33) {
            o.put("video", hasPerm("android.permission.READ_MEDIA_VIDEO"));
            o.put("audio", hasPerm("android.permission.READ_MEDIA_AUDIO"));
            o.put("image", hasPerm("android.permission.READ_MEDIA_IMAGES"));
        } else {
            boolean g = hasPerm(Manifest.permission.READ_EXTERNAL_STORAGE);
            o.put("video", g);
            o.put("audio", g);
            o.put("image", g);
        }
        call.resolve(o);
    }

    @PluginMethod
    public void askPerm(PluginCall call) {
        Activity a = getActivity();
        if (a == null) {
            call.reject("Keine Ansicht");
            return;
        }
        ActivityCompat.requestPermissions(a, mediaPerms(), 4711);
        call.resolve();
    }

    // ---------------- Mediathek ----------------
    private static String col(Cursor c, String name) {
        int i = c.getColumnIndex(name);
        if (i < 0 || c.isNull(i)) {
            return "";
        }
        return c.getString(i);
    }

    private static long coll(Cursor c, String name) {
        int i = c.getColumnIndex(name);
        if (i < 0 || c.isNull(i)) {
            return 0;
        }
        return c.getLong(i);
    }

    @PluginMethod
    public void scan(final PluginCall call) {
        final String kind = call.getString("kind", "video");
        new Thread(() -> {
            try {
                ContentResolver cr = getContext().getContentResolver();
                Uri base;
                if ("audio".equals(kind)) {
                    base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                } else if ("image".equals(kind)) {
                    base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                } else {
                    base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                }
                String sel = "audio".equals(kind) ? MediaStore.Audio.Media.IS_MUSIC + "!=0" : null;
                Cursor c = cr.query(base, null, sel, null, MediaStore.MediaColumns.DATE_ADDED + " DESC");
                JSArray arr = new JSArray();
                if (c != null) {
                    while (c.moveToNext()) {
                        long id = coll(c, MediaStore.MediaColumns._ID);
                        JSObject o = new JSObject();
                        o.put("id", id);
                        o.put("uri", ContentUris.withAppendedId(base, id).toString());
                        o.put("name", col(c, MediaStore.MediaColumns.DISPLAY_NAME));
                        o.put("size", coll(c, MediaStore.MediaColumns.SIZE));
                        o.put("date", coll(c, MediaStore.MediaColumns.DATE_ADDED));
                        o.put("mime", col(c, MediaStore.MediaColumns.MIME_TYPE));
                        String folder = col(c, "relative_path");
                        if (folder.isEmpty()) {
                            String data = col(c, MediaStore.MediaColumns.DATA);
                            int k = data.lastIndexOf('/');
                            folder = k > 0 ? data.substring(0, k) : "";
                        }
                        o.put("folder", folder);
                        o.put("bucket", col(c, "bucket_display_name"));
                        o.put("path", col(c, MediaStore.MediaColumns.DATA));
                        if ("audio".equals(kind)) {
                            o.put("title", col(c, MediaStore.Audio.Media.TITLE));
                            o.put("artist", col(c, MediaStore.Audio.Media.ARTIST));
                            o.put("album", col(c, MediaStore.Audio.Media.ALBUM));
                            o.put("albumId", coll(c, MediaStore.Audio.Media.ALBUM_ID));
                            o.put("track", coll(c, MediaStore.Audio.Media.TRACK));
                            o.put("dur", coll(c, "duration"));
                        } else if ("video".equals(kind)) {
                            o.put("title", col(c, MediaStore.MediaColumns.TITLE));
                            o.put("dur", coll(c, "duration"));
                            o.put("w", coll(c, MediaStore.MediaColumns.WIDTH));
                            o.put("h", coll(c, MediaStore.MediaColumns.HEIGHT));
                        } else {
                            o.put("w", coll(c, MediaStore.MediaColumns.WIDTH));
                            o.put("h", coll(c, MediaStore.MediaColumns.HEIGHT));
                            o.put("taken", coll(c, "datetaken"));
                        }
                        arr.put(o);
                    }
                    c.close();
                }
                JSObject r = new JSObject();
                r.put("items", arr);
                call.resolve(r);
            } catch (Exception e) {
                call.reject("Mediathek: " + e.getMessage());
            }
        }).start();
    }

    // ---------------- Vorschaubilder ----------------
    private static String md5(String s) {
        try {
            MessageDigest m = MessageDigest.getInstance("MD5");
            byte[] b = m.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    @PluginMethod
    public void thumb(final PluginCall call) {
        final String uri = call.getString("uri", "");
        final int w = call.getInt("w", 320);
        final int h = call.getInt("h", 320);
        new Thread(() -> {
            try {
                File dir = new File(getContext().getCacheDir(), "thumbs");
                dir.mkdirs();
                File f = new File(dir, md5(uri + "|" + w + "x" + h) + ".jpg");
                if (!f.exists() || f.length() == 0) {
                    Bitmap bm = null;
                    Uri u = Uri.parse(uri);
                    if (uri.startsWith("file://") || uri.startsWith("/")) {
                        bm = fileThumb(new File(uri.startsWith("file://") ? u.getPath() : uri), w, h);
                    } else if (Build.VERSION.SDK_INT >= 29) {
                        bm = getContext().getContentResolver().loadThumbnail(u, new Size(w, h), null);
                    } else {
                        long id = ContentUris.parseId(u);
                        if (uri.contains("/video/")) {
                            bm = MediaStore.Video.Thumbnails.getThumbnail(getContext().getContentResolver(), id, MediaStore.Video.Thumbnails.MINI_KIND, null);
                        } else if (uri.contains("/images/")) {
                            bm = MediaStore.Images.Thumbnails.getThumbnail(getContext().getContentResolver(), id, MediaStore.Images.Thumbnails.MINI_KIND, null);
                        }
                    }
                    if (bm == null) {
                        call.reject("kein Bild");
                        return;
                    }
                    FileOutputStream fo = new FileOutputStream(f);
                    bm.compress(Bitmap.CompressFormat.JPEG, 82, fo);
                    fo.close();
                }
                JSObject r = new JSObject();
                r.put("path", f.getAbsolutePath());
                call.resolve(r);
            } catch (Exception e) {
                call.reject("kein Bild");
            }
        }).start();
    }

    private static final java.util.regex.Pattern VID = java.util.regex.Pattern.compile("(?i).*\\.(mp4|m4v|mkv|avi|divx|xvid|mov|qt|webm|ts|m2ts|mts|wmv|asf|flv|f4v|mpe?g|mpe|m2v|m1v|3gp|3g2|vob|ogv|ogm|rm|rmvb|dv|mxf|amv)$");
    private static final java.util.regex.Pattern AUD = java.util.regex.Pattern.compile("(?i).*\\.(mp3|flac|m4a|aac|ogg|oga|opus|wav|wma|aiff?|ape|mka|ac3|dts|wv|mpc|tta|amr|alac)$");
    private static final java.util.regex.Pattern IMG = java.util.regex.Pattern.compile("(?i).*\\.(jpe?g|png|gif|webp|heic|bmp)$");

    private Bitmap fileThumb(File f, int w, int h) {
        String n = f.getName();
        try {
            if (VID.matcher(n).matches()) {
                if (Build.VERSION.SDK_INT >= 29) {
                    return ThumbnailUtils.createVideoThumbnail(f, new Size(w, h), null);
                }
                return ThumbnailUtils.createVideoThumbnail(f.getAbsolutePath(), MediaStore.Video.Thumbnails.MINI_KIND);
            }
            if (IMG.matcher(n).matches()) {
                if (Build.VERSION.SDK_INT >= 29) {
                    return ThumbnailUtils.createImageThumbnail(f, new Size(w, h), null);
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                int s = 1;
                while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) {
                    s *= 2;
                }
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = s;
                return BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
            }
            if (AUD.matcher(n).matches()) {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                r.setDataSource(f.getAbsolutePath());
                byte[] pic = r.getEmbeddedPicture();
                r.release();
                if (pic != null) {
                    return BitmapFactory.decodeByteArray(pic, 0, pic.length);
                }
            }
        } catch (Exception ignored) {
            // kein Bild
        }
        return null;
    }

    // ---------------- Ganzes Geraet durchsuchen ----------------
    private boolean allFilesOk() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return hasPerm(Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    @PluginMethod
    public void allFiles(PluginCall call) {
        JSObject o = new JSObject();
        o.put("granted", allFilesOk());
        call.resolve(o);
    }

    @PluginMethod
    public void askAllFiles(PluginCall call) {
        try {
            Activity a = getActivity();
            if (Build.VERSION.SDK_INT >= 30) {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getContext().getPackageName()));
                try {
                    a.startActivity(i);
                } catch (Exception e) {
                    a.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            } else {
                ActivityCompat.requestPermissions(a, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 4712);
            }
            call.resolve();
        } catch (Exception e) {
            call.reject("Einstellung lässt sich nicht öffnen");
        }
    }

    private int walked;

    private void walkDir(File dir, File root, int depth, JSArray out, Set<String> seen) {
        if (depth > 14 || walked > 25000) {
            return;
        }
        File[] list = dir.listFiles();
        if (list == null) {
            return;
        }
        if (depth > 0 && new File(dir, ".nomedia").exists() && depth > 3) {
            return;
        }
        for (File f : list) {
            String n = f.getName();
            if (n.startsWith(".")) {
                continue;
            }
            if (f.isDirectory()) {
                String rel = f.getAbsolutePath().substring(root.getAbsolutePath().length());
                if (rel.equalsIgnoreCase("/Android/data") || rel.equalsIgnoreCase("/Android/obb")) {
                    continue;
                }
                walkDir(f, root, depth + 1, out, seen);
                continue;
            }
            walked++;
            String kind = VID.matcher(n).matches() ? "video" : AUD.matcher(n).matches() ? "audio" : IMG.matcher(n).matches() ? "image" : null;
            if (kind == null) {
                continue;
            }
            long size = f.length();
            if (("video".equals(kind) && size < 1000000) || ("image".equals(kind) && size < 30000) || ("audio".equals(kind) && size < 200000)) {
                continue;
            }
            String path = f.getAbsolutePath();
            if (!seen.add(path)) {
                continue;
            }
            JSObject o = new JSObject();
            o.put("kind", kind);
            o.put("path", path);
            o.put("uri", Uri.fromFile(f).toString());
            o.put("name", n);
            o.put("size", size);
            o.put("date", f.lastModified() / 1000);
            String rel = dir.getAbsolutePath().substring(root.getAbsolutePath().length());
            o.put("folder", (rel.startsWith("/") ? rel.substring(1) : rel) + "/");
            o.put("bucket", dir.getName());
            o.put("root", root.getAbsolutePath());
            if ("video".equals(kind) && size > 80000000) {
                try {
                    MediaMetadataRetriever r = new MediaMetadataRetriever();
                    r.setDataSource(path);
                    String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    String hh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                    r.release();
                    o.put("dur", d == null ? 0 : Long.parseLong(d));
                    o.put("h", hh == null ? 0 : Long.parseLong(hh));
                } catch (Exception ignored) {
                    o.put("dur", 0);
                }
            }
            out.put(o);
        }
    }

    @PluginMethod
    public void walk(final PluginCall call) {
        new Thread(() -> {
            try {
                walked = 0;
                JSArray out = new JSArray();
                Set<String> seen = new HashSet<>();
                List<File> roots = new ArrayList<>();
                roots.add(Environment.getExternalStorageDirectory());
                File[] ext = getContext().getExternalFilesDirs(null);
                if (ext != null) {
                    for (File e : ext) {
                        if (e == null) {
                            continue;
                        }
                        String p = e.getAbsolutePath();
                        int k = p.indexOf("/Android/data");
                        if (k > 0) {
                            File r = new File(p.substring(0, k));
                            boolean dup = false;
                            for (File x : roots) {
                                if (x.getAbsolutePath().equals(r.getAbsolutePath())) {
                                    dup = true;
                                }
                            }
                            if (!dup) {
                                roots.add(r);
                            }
                        }
                    }
                }
                for (File r : roots) {
                    walkDir(r, r, 0, out, seen);
                }
                JSObject res = new JSObject();
                res.put("items", out);
                res.put("checked", walked);
                res.put("roots", roots.size());
                call.resolve(res);
            } catch (Exception e) {
                call.reject("Suche auf dem Gerät: " + e.getMessage());
            }
        }).start();
    }

    // ---------------- Netzwerk ----------------
    @PluginMethod
    public void req(final PluginCall call) {
        final String url = call.getString("url", "");
        final String method = call.getString("method", "GET");
        final String body = call.getString("body", null);
        final JSObject hdr = call.getObject("headers", new JSObject());
        final int timeout = call.getInt("timeout", 20000);
        final int max = call.getInt("max", 40000000);
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                URL u = new URL(url);
                int hops = 0;
                while (true) {
                    c = (HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(timeout);
                    c.setReadTimeout(timeout);
                    c.setInstanceFollowRedirects(false);
                    if ("PROPFIND".equals(method) || "SUBSCRIBE".equals(method)) {
                        // HttpURLConnection kennt PROPFIND nicht direkt
                        try {
                            java.lang.reflect.Field f = HttpURLConnection.class.getDeclaredField("method");
                            f.setAccessible(true);
                            f.set(c, method);
                        } catch (Exception ex) {
                            c.setRequestMethod("POST");
                            c.setRequestProperty("X-HTTP-Method-Override", method);
                        }
                    } else {
                        c.setRequestMethod(method);
                    }
                    if (!hdr.has("User-Agent")) {
                        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) FoxCenter/1.0");
                    }
                    Iterator<String> it = hdr.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        c.setRequestProperty(k, hdr.getString(k));
                    }
                    if (body != null) {
                        c.setDoOutput(true);
                        OutputStream os = c.getOutputStream();
                        os.write(body.getBytes("UTF-8"));
                        os.close();
                    }
                    int st = c.getResponseCode();
                    if ((st == 301 || st == 302 || st == 303 || st == 307 || st == 308) && hops < 6) {
                        String loc = c.getHeaderField("Location");
                        if (loc != null) {
                            u = new URL(u, loc);
                            c.disconnect();
                            hops++;
                            continue;
                        }
                    }
                    InputStream in = st >= 400 ? c.getErrorStream() : c.getInputStream();
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    if (in != null) {
                        byte[] buf = new byte[32768];
                        int n;
                        while ((n = in.read(buf)) > 0 && bo.size() < max) {
                            bo.write(buf, 0, n);
                        }
                        in.close();
                    }
                    String cs = "UTF-8";
                    String ct = c.getContentType();
                    if (ct != null && ct.toLowerCase().contains("charset=")) {
                        cs = ct.substring(ct.toLowerCase().indexOf("charset=") + 8).replace("\"", "").split(";")[0].trim();
                    }
                    JSObject r = new JSObject();
                    r.put("status", st);
                    r.put("url", u.toString());
                    r.put("type", ct == null ? "" : ct);
                    try {
                        r.put("text", bo.toString(cs));
                    } catch (Exception ex) {
                        r.put("text", bo.toString("UTF-8"));
                    }
                    call.resolve(r);
                    return;
                }
            } catch (Exception e) {
                call.reject(e instanceof SocketTimeoutException ? "Zeitüberschreitung" : String.valueOf(e.getMessage()));
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        }).start();
    }

    // DLNA/UPnP-Mediaserver im WLAN suchen (z. B. FRITZ!Box, NAS, Plex, Jellyfin)
    @PluginMethod
    public void ssdp(final PluginCall call) {
        final int wait = call.getInt("timeout", 3500);
        new Thread(() -> {
            WifiManager.MulticastLock lock = null;
            MulticastSocket s = null;
            try {
                WifiManager wm = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    lock = wm.createMulticastLock("foxcenter");
                    lock.setReferenceCounted(false);
                    lock.acquire();
                }
                s = new MulticastSocket(null);
                s.setReuseAddress(true);
                s.bind(new java.net.InetSocketAddress(0));
                s.setSoTimeout(500);
                InetAddress grp = InetAddress.getByName("239.255.255.250");
                String[] targets = {"urn:schemas-upnp-org:device:MediaServer:1", "ssdp:all"};
                for (String st : targets) {
                    String msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + st + "\r\n\r\n";
                    byte[] b = msg.getBytes("UTF-8");
                    s.send(new DatagramPacket(b, b.length, grp, 1900));
                    s.send(new DatagramPacket(b, b.length, grp, 1900));
                }
                Map<String, JSObject> found = new HashMap<>();
                long end = System.currentTimeMillis() + wait;
                byte[] buf = new byte[4096];
                while (System.currentTimeMillis() < end) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        s.receive(p);
                    } catch (SocketTimeoutException te) {
                        continue;
                    }
                    String txt = new String(p.getData(), 0, p.getLength(), "UTF-8");
                    String loc = null;
                    String server = "";
                    String stv = "";
                    for (String line : txt.split("\r\n")) {
                        int k = line.indexOf(':');
                        if (k < 0) {
                            continue;
                        }
                        String key = line.substring(0, k).trim().toLowerCase();
                        String val = line.substring(k + 1).trim();
                        if (key.equals("location")) {
                            loc = val;
                        } else if (key.equals("server")) {
                            server = val;
                        } else if (key.equals("st") || key.equals("nt")) {
                            stv = val;
                        }
                    }
                    if (loc != null && !found.containsKey(loc)) {
                        JSObject o = new JSObject();
                        o.put("location", loc);
                        o.put("server", server);
                        o.put("st", stv);
                        found.put(loc, o);
                    }
                }
                JSArray arr = new JSArray();
                for (JSObject o : found.values()) {
                    arr.put(o);
                }
                JSObject r = new JSObject();
                r.put("devices", arr);
                call.resolve(r);
            } catch (Exception e) {
                call.reject("Suche im WLAN: " + e.getMessage());
            } finally {
                if (s != null) {
                    s.close();
                }
                if (lock != null) {
                    try {
                        lock.release();
                    } catch (Exception ignored) {
                        // egal
                    }
                }
            }
        }).start();
    }

    // Mit anderer App oeffnen (z. B. VLC)
    @PluginMethod
    public void openWith(PluginCall call) {
        try {
            String url = call.getString("url", "");
            String mime = call.getString("mime", "video/*");
            Intent i = new Intent(Intent.ACTION_VIEW);
            Uri target = Uri.parse(url);
            if (url.startsWith("file://")) {
                target = androidx.core.content.FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".foxupdate", new File(target.getPath()));
            }
            i.setDataAndType(target, mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            getActivity().startActivity(Intent.createChooser(i, "Öffnen mit").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            call.resolve();
        } catch (Exception e) {
            call.reject("Keine passende App gefunden");
        }
    }

    // ---------------- Vollbild-Player (Media3 + VLC) ----------------
    @PluginMethod
    public void play(final PluginCall call) {
        final Activity act = getActivity();
        final JSArray items = call.getArray("items", new JSArray());
        final int start = call.getInt("index", 0);
        final JSObject hdrObj = call.getObject("headers", new JSObject());
        final boolean live = Boolean.TRUE.equals(call.getBoolean("live", false));
        final String engine = call.getString("engine", "auto");
        if (items.length() == 0 || act == null) {
            call.reject("Nichts zum Abspielen");
            return;
        }
        main.post(() -> {
            try {
                new FoxPlayer(act, items, start, hdrObj, live, engine, call::resolve).show();
            } catch (Throwable e) {
                call.reject("Player: " + e.getMessage());
            }
        });
    }
}
