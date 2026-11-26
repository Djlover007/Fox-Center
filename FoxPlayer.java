package de.foxcenter.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;

import org.json.JSONObject;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Vollbild-Player von Fox Center.
 * Standard-Engine: Media3 (ExoPlayer). Fuer Formate, die Android selbst nicht kann
 * (AVI mit DivX/Xvid, MPG/MPEG-2, WMV, RMVB, AC3/DTS-Ton ...), springt automatisch die VLC-Engine ein.
 */
public class FoxPlayer {

    public interface Done {
        void done(JSObject result);
    }

    // Diese Endungen gehen direkt an VLC
    static final Pattern VLC_EXT = Pattern.compile("(?i).*\\.(avi|divx|xvid|mpg|mpeg|mpe|m2v|m1v|vob|wmv|asf|rm|rmvb|flv|f4v|3gp|3g2|ogm|mts|m2ts|dv|mxf|nut|amv|mjpg|mjpeg|qt|wma|ape|mka|ac3|dts|wv|mpc|tta|amr|aiff?|ra)(\\?.*)?$");

    private final Activity act;
    private final JSArray items;
    private final int n;
    private final long[] pos;
    private final long[] dur;
    private final boolean[] ended;
    private final boolean live;
    private final String pref;
    private final Done done;
    private final Map<String, String> hdr = new HashMap<>();
    private String ua = "Mozilla/5.0 (Linux; Android) FoxCenter/1.0";
    private final Handler main = new Handler(Looper.getMainLooper());

    private int idx;
    private Dialog d;
    private FrameLayout root;
    private FrameLayout stage;
    private TextView title;
    private TextView info;
    private boolean finished;
    private boolean triedVlc;

    // Media3
    private ExoPlayer exo;
    private PlayerView pv;

    // VLC
    private LibVLC lib;
    private MediaPlayer vmp;
    private VLCVideoLayout vlay;
    private LinearLayout vbar;
    private SeekBar vseek;
    private TextView vtime;
    private Button vplay;
    private ParcelFileDescriptor pfd;
    private boolean vlcMode;
    private boolean seeking;
    private int scale;

    private final Runnable hideBar = new Runnable() {
        @Override
        public void run() {
            if (vbar != null && vmp != null && vmp.isPlaying()) {
                vbar.setVisibility(View.GONE);
                title.setVisibility(View.GONE);
            }
        }
    };

    private final Runnable keep = new Runnable() {
        @Override
        public void run() {
            if (finished) {
                return;
            }
            if (exo != null) {
                pos[idx] = exo.getCurrentPosition();
                long du = exo.getDuration();
                if (du > 0) {
                    dur[idx] = du;
                }
            }
            main.postDelayed(this, 1000);
        }
    };

    public FoxPlayer(Activity act, JSArray items, int index, JSObject headers, boolean live, String pref, Done done) {
        this.act = act;
        this.items = items;
        this.n = items.length();
        this.pos = new long[n];
        this.dur = new long[n];
        this.ended = new boolean[n];
        this.idx = Math.max(0, Math.min(index, n - 1));
        this.live = live;
        this.pref = pref == null ? "auto" : pref;
        this.done = done;
        Iterator<String> it = headers.keys();
        while (it.hasNext()) {
            String k = it.next();
            String v = headers.optString(k, "");
            if (k.equalsIgnoreCase("User-Agent")) {
                ua = v;
            } else {
                hdr.put(k, v);
            }
        }
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, act.getResources().getDisplayMetrics());
    }

    private JSONObject item(int i) {
        try {
            return items.getJSONObject(i);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private boolean wantsVlc(int i) {
        if ("vlc".equals(pref)) {
            return true;
        }
        if ("exo".equals(pref)) {
            return false;
        }
        JSONObject o = item(i);
        if ("vlc".equals(o.optString("engine", ""))) {
            return true;
        }
        String u = o.optString("url", "");
        String name = o.optString("name", "");
        return VLC_EXT.matcher(u).matches() || VLC_EXT.matcher(name).matches();
    }

    // ---------------- Fenster ----------------
    public void show() {
        root = new FrameLayout(act);
        root.setBackgroundColor(Color.BLACK);
        stage = new FrameLayout(act);
        root.addView(stage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        title = new TextView(act);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setShadowLayer(6, 0, 1, Color.BLACK);
        title.setPadding(dp(20), dp(16), dp(20), dp(10));
        title.setBackgroundColor(Color.argb(110, 0, 0, 0));
        root.addView(title, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        info = new TextView(act);
        info.setTextColor(Color.rgb(255, 190, 160));
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        info.setGravity(Gravity.CENTER);
        info.setPadding(dp(24), dp(12), dp(24), dp(12));
        info.setBackgroundColor(Color.argb(170, 0, 0, 0));
        info.setVisibility(View.GONE);
        root.addView(info, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        d = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent ev) {
                if (ev.getAction() == KeyEvent.ACTION_DOWN && onKey(ev.getKeyCode())) {
                    return true;
                }
                return super.dispatchKeyEvent(ev);
            }

            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                if (vlcMode && ev.getAction() == MotionEvent.ACTION_DOWN && vbar != null && vbar.getVisibility() != View.VISIBLE) {
                    showBar();
                    return true;
                }
                if (vlcMode) {
                    showBar();
                }
                return super.dispatchTouchEvent(ev);
            }
        };
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
        d.setOnDismissListener(di -> finish());
        d.show();
        startAt(idx, item(idx).optLong("start", 0), wantsVlc(idx));
        main.postDelayed(keep, 1000);
    }

    private void updTitle() {
        JSONObject o = item(idx);
        String t = o.optString("title", "");
        String s2 = o.optString("subtitle", "");
        title.setText(s2.isEmpty() ? t : t + "\n" + s2);
    }

    private void say(String s, long ms) {
        info.setText(s);
        info.setVisibility(View.VISIBLE);
        if (ms > 0) {
            main.postDelayed(() -> info.setVisibility(View.GONE), ms);
        }
    }

    private void startAt(int i, long start, boolean vlc) {
        releaseEngines();
        idx = i;
        info.setVisibility(View.GONE);
        title.setVisibility(View.VISIBLE);
        updTitle();
        if (vlc) {
            startVlc(start);
        } else {
            triedVlc = false;
            startExo(start);
        }
    }

    private void go(int i) {
        if (i < 0 || i >= n) {
            return;
        }
        startAt(i, 0, wantsVlc(i));
    }

    private void next(boolean auto) {
        if (idx + 1 < n) {
            go(idx + 1);
        } else if (auto) {
            d.dismiss();
        }
    }

    private void releaseEngines() {
        main.removeCallbacks(hideBar);
        if (exo != null) {
            if (!ended[idx]) {
                pos[idx] = exo.getCurrentPosition();
                long du = exo.getDuration();
                if (du > 0) {
                    dur[idx] = du;
                }
            }
            try {
                pv.setPlayer(null);
                exo.release();
            } catch (Exception ignored) {
                // egal
            }
            stage.removeView(pv);
            exo = null;
            pv = null;
        }
        if (vmp != null) {
            try {
                if (!ended[idx]) {
                    long t = vmp.getTime();
                    if (t > 0) {
                        pos[idx] = t;
                    }
                    long l = vmp.getLength();
                    if (l > 0) {
                        dur[idx] = l;
                    }
                }
                vmp.setEventListener(null);
                vmp.stop();
                vmp.detachViews();
                vmp.release();
            } catch (Exception ignored) {
                // egal
            }
            vmp = null;
            stage.removeView(vlay);
            if (vbar != null) {
                root.removeView(vbar);
            }
            vlay = null;
            vbar = null;
        }
        if (pfd != null) {
            try {
                pfd.close();
            } catch (Exception ignored) {
                // egal
            }
            pfd = null;
        }
        vlcMode = false;
    }

    private void finish() {
        if (finished) {
            return;
        }
        releaseEngines();
        finished = true;
        main.removeCallbacksAndMessages(null);
        if (lib != null) {
            try {
                lib.release();
            } catch (Exception ignored) {
                // egal
            }
            lib = null;
        }
        JSArray res = new JSArray();
        for (int k = 0; k < n; k++) {
            JSObject o = new JSObject();
            o.put("pos", pos[k]);
            o.put("dur", dur[k]);
            o.put("ended", ended[k]);
            res.put(o);
        }
        JSObject r = new JSObject();
        r.put("index", idx);
        r.put("items", res);
        done.done(r);
    }

    // ---------------- Media3 ----------------
    private void startExo(long start) {
        DefaultHttpDataSource.Factory hf = new DefaultHttpDataSource.Factory()
                .setUserAgent(ua)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(20000)
                .setReadTimeoutMs(30000)
                .setDefaultRequestProperties(hdr);
        DefaultDataSource.Factory df = new DefaultDataSource.Factory(act, hf);
        DefaultRenderersFactory rf = new DefaultRenderersFactory(act).setEnableDecoderFallback(true);
        exo = new ExoPlayer.Builder(act, rf)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(df))
                .setSeekBackIncrementMs(10000)
                .setSeekForwardIncrementMs(30000)
                .build();

        JSONObject o = item(idx);
        String url = o.optString("url", "");
        MediaItem.Builder b = new MediaItem.Builder().setUri(url);
        String lu = url.toLowerCase();
        String type = o.optString("type", "");
        if (type.equals("hls") || lu.contains(".m3u8")) {
            b.setMimeType(MimeTypes.APPLICATION_M3U8);
        } else if (type.equals("dash") || lu.contains(".mpd")) {
            b.setMimeType(MimeTypes.APPLICATION_MPD);
        }
        String sub = o.optString("sub", "");
        if (sub.startsWith("http") || sub.startsWith("content") || sub.startsWith("file")) {
            String sm = sub.toLowerCase().endsWith(".vtt") ? MimeTypes.TEXT_VTT : MimeTypes.APPLICATION_SUBRIP;
            List<MediaItem.SubtitleConfiguration> subs = new ArrayList<>();
            subs.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub))
                    .setMimeType(sm).setLanguage("de").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build());
            b.setSubtitleConfigurations(subs);
        }
        exo.setMediaItem(b.build(), live ? C.TIME_UNSET : start);
        exo.prepare();
        exo.setPlayWhenReady(true);

        final ExoPlayer ex = exo;
        Player fp = new ForwardingPlayer(ex) {
            @Override
            public boolean isCommandAvailable(int c) {
                if (c == Player.COMMAND_SEEK_TO_NEXT || c == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) {
                    return idx + 1 < n;
                }
                if (c == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) {
                    return idx > 0;
                }
                return super.isCommandAvailable(c);
            }

            @Override
            public Player.Commands getAvailableCommands() {
                Player.Commands.Builder cb = super.getAvailableCommands().buildUpon();
                if (idx + 1 < n) {
                    cb.addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM);
                }
                if (idx > 0) {
                    cb.addAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM);
                }
                return cb.build();
            }

            @Override
            public void seekToNext() {
                main.post(() -> go(idx + 1));
            }

            @Override
            public void seekToNextMediaItem() {
                main.post(() -> go(idx + 1));
            }

            @Override
            public void seekToPrevious() {
                if (ex.getCurrentPosition() > 5000 || idx == 0) {
                    ex.seekTo(0);
                } else {
                    main.post(() -> go(idx - 1));
                }
            }

            @Override
            public void seekToPreviousMediaItem() {
                main.post(() -> go(idx - 1));
            }
        };

        pv = new PlayerView(act);
        pv.setPlayer(fp);
        pv.setKeepScreenOn(true);
        pv.setShowSubtitleButton(true);
        pv.setControllerShowTimeoutMs(3500);
        pv.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) v -> title.setVisibility(v));
        stage.addView(pv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ex.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException e) {
                if (ex != exo) {
                    return;
                }
                if (!"exo".equals(pref) && !triedVlc) {
                    toVlc("Wechsle zur VLC-Engine …");
                    return;
                }
                say("Das lässt sich hier nicht abspielen (" + e.getErrorCodeName() + ").\n"
                        + (n > 1 ? "Mit ⏭ weiter zum nächsten. " : "") + "Zurück-Taste schließt.", 0);
                pv.showController();
            }

            @Override
            public void onTracksChanged(Tracks t) {
                if (ex != exo || "exo".equals(pref) || triedVlc) {
                    return;
                }
                boolean badVideo = t.containsType(C.TRACK_TYPE_VIDEO) && !t.isTypeSupported(C.TRACK_TYPE_VIDEO);
                boolean badAudio = t.containsType(C.TRACK_TYPE_AUDIO) && !t.isTypeSupported(C.TRACK_TYPE_AUDIO);
                if (badVideo || badAudio) {
                    main.post(() -> toVlc(badVideo ? "Bildformat wird nicht unterstützt – wechsle zu VLC …" : "Tonformat wird nicht unterstützt – wechsle zu VLC …"));
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (ex != exo) {
                    return;
                }
                if (state == Player.STATE_READY) {
                    info.setVisibility(View.GONE);
                }
                if (state == Player.STATE_ENDED) {
                    ended[idx] = true;
                    main.post(() -> next(true));
                }
            }
        });
    }

    private void toVlc(String msg) {
        if (exo == null) {
            return;
        }
        long p = live ? 0 : exo.getCurrentPosition();
        triedVlc = true;
        int i = idx;
        startAt(i, p, true);
        triedVlc = true;
        say(msg, 2500);
    }

    // ---------------- VLC ----------------
    private Button vbtn(String label, View.OnClickListener l) {
        Button b = new Button(act);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setMinWidth(dp(48));
        b.setPadding(dp(12), 0, dp(12), 0);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(22));
        g.setColor(Color.argb(60, 255, 255, 255));
        b.setBackground(g);
        b.setOnFocusChangeListener((v, has) -> {
            GradientDrawable gg = new GradientDrawable();
            gg.setCornerRadius(dp(22));
            gg.setColor(has ? Color.rgb(245, 124, 31) : Color.argb(60, 255, 255, 255));
            v.setBackground(gg);
            if (has) {
                showBar();
            }
        });
        b.setOnClickListener(v -> {
            l.onClick(v);
            showBar();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        lp.setMargins(dp(4), 0, dp(4), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private static String hms(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long x = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, x) : String.format("%d:%02d", m, x);
    }

    private void buildBar() {
        vbar = new LinearLayout(act);
        vbar.setOrientation(LinearLayout.VERTICAL);
        vbar.setPadding(dp(16), dp(10), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{Color.argb(220, 0, 0, 0), Color.argb(0, 0, 0, 0)});
        vbar.setBackground(bg);

        LinearLayout row1 = new LinearLayout(act);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        vseek = new SeekBar(act);
        vseek.setMax(1000);
        vseek.setFocusable(!live);
        vseek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user && vmp != null && vmp.getLength() > 0) {
                    vmp.setTime(vmp.getLength() * p / 1000);
                    showBar();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                seeking = false;
            }
        });
        row1.addView(vseek, new LinearLayout.LayoutParams(0, dp(36), 1));
        vtime = new TextView(act);
        vtime.setTextColor(Color.WHITE);
        vtime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        vtime.setPadding(dp(8), 0, 0, 0);
        row1.addView(vtime);
        vbar.addView(row1);

        LinearLayout row2 = new LinearLayout(act);
        row2.setGravity(Gravity.CENTER);
        row2.setPadding(0, dp(6), 0, 0);
        if (n > 1) {
            row2.addView(vbtn("⏮", v -> go(idx - 1)));
        }
        if (!live) {
            row2.addView(vbtn("−10", v -> skip(-10000)));
        }
        vplay = vbtn("⏸", v -> togglePause());
        row2.addView(vplay);
        if (!live) {
            row2.addView(vbtn("+30", v -> skip(30000)));
        }
        if (n > 1) {
            row2.addView(vbtn("⏭", v -> go(idx + 1)));
        }
        row2.addView(vbtn("Ton", v -> cycleAudio()));
        row2.addView(vbtn("Untertitel", v -> cycleSpu()));
        row2.addView(vbtn("Format", v -> cycleScale()));
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(act);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row2);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        vbar.addView(hs, hl);

        root.addView(vbar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
    }

    private void showBar() {
        if (vbar == null) {
            return;
        }
        vbar.setVisibility(View.VISIBLE);
        title.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideBar);
        main.postDelayed(hideBar, 4000);
    }

    private void togglePause() {
        if (vmp == null) {
            return;
        }
        if (vmp.isPlaying()) {
            vmp.pause();
        } else {
            vmp.play();
        }
        showBar();
    }

    private void skip(long ms) {
        if (vmp == null || live) {
            return;
        }
        long t = Math.max(0, vmp.getTime() + ms);
        long l = vmp.getLength();
        if (l > 0 && t > l - 1000) {
            t = l - 1000;
        }
        vmp.setTime(t);
        say((ms > 0 ? "+" : "−") + Math.abs(ms / 1000) + " s  ·  " + hms(t), 900);
        showBar();
    }

    private void cycleAudio() {
        if (vmp == null) {
            return;
        }
        MediaPlayer.TrackDescription[] t = vmp.getAudioTracks();
        if (t == null || t.length < 2) {
            say("Nur eine Tonspur", 1500);
            return;
        }
        int cur = vmp.getAudioTrack();
        int k = 0;
        for (int i = 0; i < t.length; i++) {
            if (t[i].id == cur) {
                k = i;
            }
        }
        k = (k + 1) % t.length;
        if (t[k].id < 0 && t.length > 1) {
            k = (k + 1) % t.length; // "Aus" ueberspringen
        }
        vmp.setAudioTrack(t[k].id);
        say("Ton: " + t[k].name, 1800);
    }

    private void cycleSpu() {
        if (vmp == null) {
            return;
        }
        MediaPlayer.TrackDescription[] t = vmp.getSpuTracks();
        if (t == null || t.length == 0) {
            say("Keine Untertitel", 1500);
            return;
        }
        int cur = vmp.getSpuTrack();
        int k = -1;
        for (int i = 0; i < t.length; i++) {
            if (t[i].id == cur) {
                k = i;
            }
        }
        k = (k + 1) % t.length;
        vmp.setSpuTrack(t[k].id);
        say("Untertitel: " + (t[k].id < 0 ? "Aus" : t[k].name), 1800);
    }

    private void cycleScale() {
        if (vmp == null) {
            return;
        }
        MediaPlayer.ScaleType[] s = {MediaPlayer.ScaleType.SURFACE_BEST_FIT, MediaPlayer.ScaleType.SURFACE_FILL,
                MediaPlayer.ScaleType.SURFACE_16_9, MediaPlayer.ScaleType.SURFACE_4_3, MediaPlayer.ScaleType.SURFACE_ORIGINAL};
        String[] names = {"Einpassen", "Bildschirm füllen", "16:9", "4:3", "Original"};
        scale = (scale + 1) % s.length;
        vmp.setVideoScale(s[scale]);
        say("Format: " + names[scale], 1500);
    }

    private void startVlc(long start) {
        vlcMode = true;
        try {
            if (lib == null) {
                ArrayList<String> opts = new ArrayList<>();
                opts.add("--http-reconnect");
                opts.add("--network-caching=1500");
                opts.add("--audio-time-stretch");
                opts.add("--subsdec-encoding=Windows-1252");
                lib = new LibVLC(act, opts);
            }
            vmp = new MediaPlayer(lib);
            vlay = new VLCVideoLayout(act);
            stage.addView(vlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            vmp.attachViews(vlay, null, true, false);
            scale = 0;

            JSONObject o = item(idx);
            String url = o.optString("url", "");
            Media m;
            if (url.startsWith("content://")) {
                pfd = act.getContentResolver().openFileDescriptor(Uri.parse(url), "r");
                m = new Media(lib, pfd.getFileDescriptor());
            } else {
                m = new Media(lib, Uri.parse(url));
            }
            m.setHWDecoderEnabled(true, false);
            if (start > 0 && !live) {
                m.addOption(":start-time=" + (start / 1000.0));
            }
            m.addOption(":http-user-agent=" + ua);
            for (Map.Entry<String, String> e : hdr.entrySet()) {
                String k = e.getKey();
                if (k.equalsIgnoreCase("Referer")) {
                    m.addOption(":http-referrer=" + e.getValue());
                } else if (k.equalsIgnoreCase("Authorization") && e.getValue().startsWith("Basic ")) {
                    try {
                        String up = new String(Base64.decode(e.getValue().substring(6), Base64.DEFAULT), "UTF-8");
                        int c = up.indexOf(':');
                        if (c > 0) {
                            m.addOption(":http-user=" + up.substring(0, c));
                            m.addOption(":http-pwd=" + up.substring(c + 1));
                        }
                    } catch (Exception ignored) {
                        // egal
                    }
                }
            }
            vmp.setMedia(m);
            m.release();

            buildBar();
            final MediaPlayer mp = vmp;
            vmp.setEventListener(ev -> {
                if (mp != vmp) {
                    return;
                }
                switch (ev.type) {
                    case MediaPlayer.Event.TimeChanged:
                        long t = ev.getTimeChanged();
                        pos[idx] = t;
                        long l = mp.getLength();
                        if (l > 0) {
                            dur[idx] = l;
                            if (!seeking) {
                                vseek.setProgress((int) (t * 1000 / l));
                            }
                        }
                        vtime.setText(live ? hms(t) : hms(t) + " / " + hms(l));
                        break;
                    case MediaPlayer.Event.LengthChanged:
                        dur[idx] = ev.getLengthChanged();
                        break;
                    case MediaPlayer.Event.Playing:
                        vplay.setText("⏸");
                        info.setVisibility(View.GONE);
                        showBar();
                        break;
                    case MediaPlayer.Event.Paused:
                        vplay.setText("▶");
                        showBar();
                        break;
                    case MediaPlayer.Event.Buffering:
                        if (ev.getBuffering() < 100f && !mp.isPlaying()) {
                            say("Lädt … " + (int) ev.getBuffering() + " %", 0);
                        } else {
                            info.setVisibility(View.GONE);
                        }
                        break;
                    case MediaPlayer.Event.EndReached:
                        ended[idx] = true;
                        main.post(() -> next(true));
                        break;
                    case MediaPlayer.Event.EncounteredError:
                        say("Das lässt sich leider auch mit VLC nicht abspielen.\n"
                                + (n > 1 ? "Mit ⏭ weiter zum nächsten. " : "") + "Zurück-Taste schließt.", 0);
                        showBar();
                        break;
                    default:
                        break;
                }
            });
            String sub = o.optString("sub", "");
            vmp.play();
            if (!sub.isEmpty()) {
                try {
                    vmp.addSlave(0, Uri.parse(sub), true);
                } catch (Exception ignored) {
                    // egal
                }
            }
            showBar();
            main.postDelayed(() -> {
                if (vplay != null && vbar != null && vbar.getVisibility() == View.VISIBLE) {
                    vplay.requestFocus();
                }
            }, 300);
        } catch (Throwable e) {
            say("VLC-Engine konnte nicht starten: " + e.getMessage(), 0);
        }
    }

    // ---------------- Tasten (Fernbedienung) ----------------
    private boolean onKey(int k) {
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
            if (vlcMode && vbar != null && vbar.getVisibility() == View.VISIBLE && vmp != null && vmp.isPlaying()) {
                vbar.setVisibility(View.GONE);
                title.setVisibility(View.GONE);
                return true;
            }
            return false;
        }
        if (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_SPACE || k == KeyEvent.KEYCODE_MEDIA_PLAY || k == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            if (vlcMode) {
                togglePause();
            } else if (exo != null) {
                exo.setPlayWhenReady(!exo.getPlayWhenReady());
                pv.showController();
            }
            return true;
        }
        if (k == KeyEvent.KEYCODE_CHANNEL_UP || k == KeyEvent.KEYCODE_PAGE_UP || k == KeyEvent.KEYCODE_MEDIA_NEXT) {
            go(idx + 1);
            return true;
        }
        if (k == KeyEvent.KEYCODE_CHANNEL_DOWN || k == KeyEvent.KEYCODE_PAGE_DOWN || k == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            go(idx - 1);
            return true;
        }
        if (k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            if (vlcMode) {
                skip(30000);
            } else if (exo != null) {
                exo.seekForward();
            }
            return true;
        }
        if (k == KeyEvent.KEYCODE_MEDIA_REWIND) {
            if (vlcMode) {
                skip(-10000);
            } else if (exo != null) {
                exo.seekBack();
            }
            return true;
        }
        boolean dpad = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_DPAD_LEFT
                || k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN;
        if (!dpad) {
            return false;
        }
        boolean barHidden = vlcMode ? (vbar == null || vbar.getVisibility() != View.VISIBLE) : (pv == null || !pv.isControllerFullyVisible());
        if (!barHidden) {
            if (vlcMode) {
                showBar();
            }
            return false; // normale Fokus-Bewegung in den Bedienknoepfen
        }
        if (live && (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN)) {
            go(k == KeyEvent.KEYCODE_DPAD_UP ? idx - 1 : idx + 1);
            return true;
        }
        if (!live && k == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (vlcMode) {
                skip(-10000);
            } else if (exo != null) {
                exo.seekBack();
                pv.showController();
            }
            return true;
        }
        if (!live && k == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (vlcMode) {
                skip(30000);
            } else if (exo != null) {
                exo.seekForward();
                pv.showController();
            }
            return true;
        }
        if (vlcMode) {
            showBar();
            if (vplay != null) {
                vplay.requestFocus();
            }
        } else if (pv != null) {
            pv.showController();
        }
        return true;
    }
}
