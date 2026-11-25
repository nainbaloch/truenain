package com.truenain.app;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.util.ArrayList;
import java.util.Locale;

/** Non-destructive multi-range trim workspace. */
public class TrimActivity extends Activity {
    private final ArrayList<TrimExporter.Range> ranges = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private VideoView video;
    private SeekBar seek;
    private TextView time;
    private TextView activeRange;
    private LinearLayout rangesBox;
    private long startMs = -1L;
    private long endMs = -1L;
    private Uri source;
    private boolean previewing;
    private int previewIndex;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (video == null) return;
            int p = video.getCurrentPosition();
            if (!previewing) seek.setProgress(p);
            updateTime(p, video.getDuration());
            ui.postDelayed(this, 150L);
        }
    };

    private final Runnable previewWatcher = new Runnable() {
        @Override public void run() {
            if (!previewing || ranges.isEmpty() || video == null) return;
            if (previewIndex >= ranges.size()) {
                finishPreview();
                return;
            }
            TrimExporter.Range current = ranges.get(previewIndex);
            if (video.getCurrentPosition() >= current.endMs - 20L) {
                previewIndex++;
                if (previewIndex >= ranges.size()) {
                    finishPreview();
                    return;
                }
                TrimExporter.Range next = ranges.get(previewIndex);
                video.seekTo((int) next.startMs);
                video.start();
            }
            ui.postDelayed(this, 40L);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        source = getIntent().getData();
        if (source == null) { finish(); return; }
        setTitle("Trim + Merge");
        build();
        video.setVideoURI(source);
        video.setOnPreparedListener(mp -> {
            seek.setMax(Math.max(1, mp.getDuration()));
            updateTime(0, mp.getDuration());
        });
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.setBackgroundColor(0xFF050A12);

        TextView title = label("TRIM + MULTI-RANGE MERGE", 17, true);
        root.addView(title);
        TextView help = label("Set Start → Set End → + Add Range. Repeat, preview, reorder/remove, then MERGE.", 11, false);
        help.setTextColor(0xFF90A4AE);
        root.addView(help);

        video = new VideoView(this);
        root.addView(video, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        time = label("00:00 / 00:00", 12, true);
        time.setGravity(Gravity.CENTER);
        root.addView(time);

        seek = new SeekBar(this);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) {
                    stopPreviewOnly();
                    video.seekTo(p);
                    updateTime(p, video.getDuration());
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { stopPreviewOnly(); }
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(seek);

        activeRange = label("Start: —   End: —", 11, true);
        activeRange.setTextColor(0xFF64B5F6);
        activeRange.setGravity(Gravity.CENTER);
        root.addView(activeRange);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button play = btn("▶ / ⏸", v -> {
            stopPreviewOnly();
            if (video.isPlaying()) video.pause(); else video.start();
        });
        Button st = btn("Set Start", v -> {
            startMs = video.getCurrentPosition();
            if (endMs >= 0 && endMs <= startMs) endMs = -1L;
            refreshActiveRange();
        });
        Button en = btn("Set End", v -> {
            endMs = video.getCurrentPosition();
            refreshActiveRange();
        });
        Button add = btn("+ Add Range", v -> addRange());
        row.addView(play, weight());
        row.addView(st, weight());
        row.addView(en, weight());
        row.addView(add, weight());
        root.addView(row);

        ScrollView scroll = new ScrollView(this);
        rangesBox = new LinearLayout(this);
        rangesBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rangesBox, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
        slp.topMargin = dp(6);
        root.addView(scroll, slp);
        refreshRanges();

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        Button preview = btn("Preview All", v -> previewAll());
        Button merge = btn("MERGE", v -> export());
        bottom.addView(preview, weight());
        bottom.addView(merge, weight());
        root.addView(bottom);

        setContentView(root);
    }

    private void addRange() {
        if (startMs < 0 || endMs <= startMs) {
            toast("Set a valid Start and End first");
            return;
        }
        ranges.add(new TrimExporter.Range(startMs, endMs));
        startMs = endMs = -1L;
        refreshActiveRange();
        refreshRanges();
    }

    private void refreshActiveRange() {
        activeRange.setText("Start: " + (startMs < 0 ? "—" : fmt(startMs))
                + "   End: " + (endMs < 0 ? "—" : fmt(endMs)));
    }

    private void refreshRanges() {
        rangesBox.removeAllViews();
        if (ranges.isEmpty()) {
            TextView empty = label("No ranges yet", 12, false);
            empty.setTextColor(0xFF90A4AE);
            rangesBox.addView(empty);
            return;
        }
        for (int i = 0; i < ranges.size(); i++) {
            final int index = i;
            TrimExporter.Range r = ranges.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(3), dp(4), dp(3));

            TextView text = label("#" + (i + 1) + "  " + fmt(r.startMs) + " → " + fmt(r.endMs), 11, true);
            row.addView(text, new LinearLayout.LayoutParams(0, dp(38), 1f));

            Button up = mini("↑", v -> moveRange(index, -1));
            Button down = mini("↓", v -> moveRange(index, 1));
            Button remove = mini("×", v -> {
                ranges.remove(index);
                stopPreviewOnly();
                refreshRanges();
            });
            row.addView(up);
            row.addView(down);
            row.addView(remove);
            rangesBox.addView(row);
        }
    }

    private void moveRange(int index, int delta) {
        int target = index + delta;
        if (target < 0 || target >= ranges.size()) return;
        TrimExporter.Range item = ranges.remove(index);
        ranges.add(target, item);
        stopPreviewOnly();
        refreshRanges();
    }

    private void previewAll() {
        if (ranges.isEmpty()) { toast("Add at least one range"); return; }
        previewing = true;
        previewIndex = 0;
        TrimExporter.Range first = ranges.get(0);
        video.seekTo((int) first.startMs);
        video.start();
        ui.removeCallbacks(previewWatcher);
        ui.post(previewWatcher);
    }

    private void stopPreviewOnly() {
        if (!previewing) return;
        previewing = false;
        ui.removeCallbacks(previewWatcher);
    }

    private void finishPreview() {
        previewing = false;
        ui.removeCallbacks(previewWatcher);
        video.pause();
    }

    private void export() {
        if (ranges.isEmpty()) { toast("Add at least one range"); return; }
        stopPreviewOnly();
        toast("MERGE started…");
        TrimExporter.exportMerged(this, source, new ArrayList<>(ranges), new TrimExporter.Callback() {
            @Override public void onDone(Uri uri, boolean alignedToKeyframes) {
                runOnUiThread(() -> toast("Saved → Movies/TRUE NAIN"));
            }
            @Override public void onError(Exception e) {
                runOnUiThread(() -> toast("Merge failed: " + e.getMessage()));
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    @Override protected void onPause() {
        ui.removeCallbacks(ticker);
        ui.removeCallbacks(previewWatcher);
        previewing = false;
        if (video != null && video.isPlaying()) video.pause();
        super.onPause();
    }

    private TextView label(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private Button btn(String s, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(11);
        b.setBackgroundColor(0xFF132033);
        b.setOnClickListener(l);
        return b;
    }

    private Button mini(String s, android.view.View.OnClickListener l) {
        Button b = btn(s, l);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(36)));
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        return lp;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private void updateTime(long p, long d) { time.setText(fmt(p) + " / " + fmt(d)); }

    private static String fmt(long ms) {
        long x = Math.max(0, ms) / 1000L;
        if (x >= 3600) return String.format(Locale.US, "%d:%02d:%02d", x / 3600, (x / 60) % 60, x % 60);
        return String.format(Locale.US, "%02d:%02d", x / 60, x % 60);
    }
}
