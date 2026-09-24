package dev.nofocus.folderplayer;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/** Device layout checks. No playback or file writes; temporary folder/mode preferences are restored. */
public final class CompactUiTest extends Instrumentation {
    private Activity activity;
    private int screens;
    private String screen;
    private boolean landscape;
    private Bundle arguments;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments;
        landscape = arguments != null && "landscape".equals(arguments.getString("orientation"));
        start();
    }

    @Override public void onStart() {
        if (arguments != null && "true".equals(arguments.getString("downloads"))) {
            Bundle result = new Bundle();
            try {
                DownloadRuntimeTest.run(this, arguments);
                result.putString(REPORT_KEY_STREAMRESULT, "\nPASS: Android download runtime, formats, cancellation and file provider.\n");
                finish(Activity.RESULT_OK, result);
            } catch (Throwable error) {
                android.util.Log.e("DownloadRuntimeTest", "Failed", error);
                result.putString(REPORT_KEY_STREAMRESULT, "\nFAIL: " + error + "\n");
                finish(Activity.RESULT_CANCELED, result);
            }
            return;
        }
        Bundle result = new Bundle();
        SharedPreferences prefs = getTargetContext().getSharedPreferences(PlayerService.PREFS, 0);
        SharedPreferences downloadPrefs = getTargetContext().getSharedPreferences(SongDownloadService.PREFS, 0);
        boolean hadFormat = downloadPrefs.contains("format");
        int originalFormat = downloadPrefs.getInt("format", 0);
        boolean originalMode = prefs.getBoolean("ui_music_mode", false);
        String originalFolder = prefs.getString(PlayerService.PREF_TREE_URI, null);
        int resultCode = Activity.RESULT_OK;
        try {
            ActivityMonitor launches = addMonitor(MainActivity.class.getName(), null, false);
            activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            // Some launchers stay in portrait, so a locked landscape display rotates during launch.
            android.os.SystemClock.sleep(700);
            waitForIdleSync();
            if (launches.getLastActivity() != null) activity = launches.getLastActivity();
            removeMonitor(launches);
            if (landscape) {
                // Start with the display already rotated; requesting orientation here would recreate the Activity.
                if (activity.getResources().getConfiguration().orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                    throw new AssertionError("Rotate the device to landscape before this run");
                }
            }
            prefs.edit().remove(PlayerService.PREF_TREE_URI).commit();
            click("My music");
            check("No folder selected");
            requireText("Choose");
            if (originalFolder != null) prefs.edit().putString(PlayerService.PREF_TREE_URI, originalFolder).commit();
            click("PC audio");
            check("PC home");
            click("Setup");
            check("PC setup");
            click("More options");
            check("PC options");
            click("Sound quality");
            check("Sound quality");
            click("Back");
            click("Back");
            click("Back");
            click("My music");
            check("Music home");
            click("Setup");
            check("Music setup");
            click("Add music");
            check("Add music");
            Activity main = activity;
            downloadPrefs.edit().putInt("format", 0).commit();
            activity = startActivitySync(new Intent(getTargetContext(), SongDownloadActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            check("Download song");
            click("MP3 ▾");
            check("Download format choices");
            click("M4A · smaller file");
            requireText("M4A ▾");
            click("M4A ▾");
            click("MP3 · most apps");
            java.lang.reflect.Field downloadState = SongDownloadService.class.getDeclaredField("state");
            downloadState.setAccessible(true);
            Object previousDownload = downloadState.get(null);
            java.lang.reflect.Method showDownload = SongDownloadActivity.class.getDeclaredMethod("showState");
            showDownload.setAccessible(true);
            try {
                for (SongDownloadService.State sample : new SongDownloadService.State[]{
                        new SongDownloadService.State(true, "Downloading 75.4%", ""),
                        new SongDownloadService.State(false, "YouTube needs sign-in. Try another public song.", ""),
                        new SongDownloadService.State(false, "Ready to save", "A very long song title that should never move controls off the screen.mp3")}) {
                    downloadState.set(null, sample);
                    onMain(() -> { try { showDownload.invoke(activity); } catch (Exception error) { throw new AssertionError(error); } });
                    check("Download: " + sample.message);
                }
            } finally { downloadState.set(null, previousDownload); }
            runOnMainSync(() -> activity.finish());
            activity = main;
            click("Get video audio");
            check("Video audio");
            click("Back");
            click("Back");
            click("Back");
            // A harmless URI enables transport labels on fresh installs; no service opens it.
            if (originalFolder == null) prefs.edit().putString(PlayerService.PREF_TREE_URI,
                    "content://com.android.externalstorage.documents/tree/primary%3AMusic").commit();
            Intent playing = new Intent(PlayerService.ACTION_STATE).setPackage(getTargetContext().getPackageName());
            playing.putExtra(PlayerService.EXTRA_IS_PLAYING, true);
            playing.putExtra(PlayerService.EXTRA_TRACK_COUNT, 123);
            playing.putExtra(PlayerService.EXTRA_TRACK_INDEX, 12);
            playing.putExtra(PlayerService.EXTRA_TRACK_NAME,
                    "A very long song title with many words that must never push playback controls off the screen.mp3");
            playing.putExtra(PlayerService.EXTRA_STATE, "Playing");
            broadcast(playing);
            check("Long song title");
            requireText("Pause");
            playing.putExtra(PlayerService.EXTRA_IS_PLAYING, false);
            playing.putExtra(PlayerService.EXTRA_STATE, "Paused");
            broadcast(playing);
            requireText("Play");
            requireText("Paused");
            click("PC audio");
            Intent waiting = new Intent(WifiStreamService.ACTION_STATE).setPackage(getTargetContext().getPackageName());
            waiting.putExtra(WifiStreamService.EXTRA_RUNNING, true);
            waiting.putExtra(WifiStreamService.EXTRA_STATUS, "Waiting for encrypted PC stream on UDP 39821");
            broadcast(waiting);
            requireText("Waiting for PC");
            requireText("Stop");
            check("Waiting for PC");
            waiting.putExtra(WifiStreamService.EXTRA_CONNECTED, true);
            waiting.putExtra(WifiStreamService.EXTRA_SENDER, "A computer with a very long name in the living room");
            broadcast(waiting);
            check("Connected PC");
            requireText("Playing PC audio");
            result.putString(REPORT_KEY_STREAMRESULT, "\nPASS: " + screens + " screens; controls inside safe area, "
                    + "48dp targets, readable button labels, no scrolling, playback state labels.\n");
        } catch (Throwable error) {
            result.putString(REPORT_KEY_STREAMRESULT, "\nFAIL: " + screen + ": " + error + "\n");
            resultCode = Activity.RESULT_CANCELED;
        } finally {
            if (hadFormat) downloadPrefs.edit().putInt("format", originalFormat).commit();
            else downloadPrefs.edit().remove("format").commit();
            SharedPreferences.Editor restore = prefs.edit().putBoolean("ui_music_mode", originalMode);
            if (originalFolder == null) restore.remove(PlayerService.PREF_TREE_URI);
            else restore.putString(PlayerService.PREF_TREE_URI, originalFolder);
            restore.commit();
            if (activity != null) runOnMainSync(() -> activity.finish());
        }
        finish(resultCode, result);
    }

    private void broadcast(Intent intent) {
        getTargetContext().sendBroadcast(intent);
        waitForIdleSync();
        // Broadcast delivery is asynchronous even when the current UI message queue is idle.
        android.os.SystemClock.sleep(150);
        waitForIdleSync();
    }

    private void click(String label) {
        waitForIdleSync();
        onMain(() -> {
            View button = find(activity.getWindow().getDecorView(), label);
            if (button == null || !button.isEnabled()) throw new AssertionError("Missing button: " + label);
            button.performClick();
        });
        waitForIdleSync();
    }

    private void requireText(String label) {
        onMain(() -> {
            if (find(activity.getWindow().getDecorView(), label) == null) throw new AssertionError("Missing: " + label);
        });
    }

    private View find(View view, String label) {
        if (label.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void check(String name) {
        screen = name;
        waitForIdleSync();
        onMain(() -> {
            ViewGroup content = activity.findViewById(android.R.id.content);
            View root = content.getChildAt(0);
            int[] origin = new int[2];
            root.getLocationOnScreen(origin);
            Rect safe = new Rect(origin[0] + root.getPaddingLeft(), origin[1] + root.getPaddingTop(),
                    origin[0] + root.getWidth() - root.getPaddingRight(),
                    origin[1] + root.getHeight() - root.getPaddingBottom());
            inspect(root, safe);
        });
        screens++;
    }

    private void onMain(Runnable check) {
        Throwable[] failure = new Throwable[1];
        runOnMainSync(() -> {
            try { check.run(); } catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }

    private void inspect(View view, Rect safe) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof ScrollView) throw new AssertionError("Scrolling container found");
        boolean control = view instanceof Button || view instanceof ImageButton || view instanceof SeekBar;
        if (control || view instanceof TextView) {
            String label = view instanceof TextView ? ((TextView) view).getText().toString()
                    : String.valueOf(view.getContentDescription());
            int[] position = new int[2];
            view.getLocationOnScreen(position);
            Rect bounds = new Rect(position[0], position[1], position[0] + view.getWidth(), position[1] + view.getHeight());
            if (!safe.contains(bounds)) throw new AssertionError(label + " outside safe area: " + bounds + " / " + safe);
            Rect visible = new Rect();
            if (!view.getGlobalVisibleRect(visible) || !visible.equals(bounds)) {
                throw new AssertionError(label + " clipped by parent: " + bounds + " / " + visible);
            }
            float density = activity.getResources().getDisplayMetrics().density;
            if (control && (view.getHeight() + 1 < 48 * density || view.getWidth() + 1 < 48 * density)) {
                throw new AssertionError(label + " touch target smaller than 48dp");
            }
            if (view instanceof TextView) {
                TextView text = (TextView) view;
                android.text.Layout layout = text.getLayout();
                int lines = Math.min(text.getMaxLines(), layout.getLineCount());
                int available = view.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom();
                if (lines > 0 && layout.getLineBottom(lines - 1) > available) {
                    throw new AssertionError(label + " text clipped vertically: " + view.getWidth() + "x"
                            + view.getHeight() + ", lines=" + lines + ", font=" + text.getTextSize()
                            + ", needed=" + layout.getLineBottom(lines - 1) + ", available=" + available);
                }
                if (control) {
                    for (int line = 0; line < lines; line++) {
                        if (layout.getEllipsisCount(line) > 0) throw new AssertionError(label + " truncated");
                    }
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) inspect(group.getChildAt(i), safe);
        }
    }
}
