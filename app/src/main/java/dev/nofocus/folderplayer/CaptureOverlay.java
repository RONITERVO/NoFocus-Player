package dev.nofocus.folderplayer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.*;
import android.widget.*;

/** One small, movable, non-focusable window. Only its own bounds receive touches. */
@android.annotation.TargetApi(29)
final class CaptureOverlay {
    private final Context context;
    private final WindowManager windows;
    private final LinearLayout root;
    private final TextView label;
    private final Button action, close;
    private final WindowManager.LayoutParams position;
    private boolean attached;
    private float touchX, touchY;
    private int initialX, initialY;

    @SuppressLint("ClickableViewAccessibility") // Label is a drag handle; buttons expose ordinary click actions.
    CaptureOverlay(Context context, Runnable onAction, Runnable onClose) {
        this.context = context;
        windows = context.getSystemService(WindowManager.class);
        root = new LinearLayout(context); root.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable background = new GradientDrawable(); background.setColor(0xee173b38); background.setCornerRadius(dp(16));
        root.setBackground(background); root.setElevation(dp(8));
        label = new TextView(context); label.setTextColor(Color.WHITE); label.setTextSize(12);
        label.setGravity(Gravity.CENTER); label.setMaxLines(2); label.setPadding(dp(6), 0, dp(6), 0);
        label.setContentDescription("Recording status. Drag to move floating control.");
        root.addView(label, new LinearLayout.LayoutParams(dp(88), dp(48)));
        action = button("▶", "Start recording", onAction);
        close = button("×", "Cancel capture", onClose);
        position = new WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        position.gravity = Gravity.TOP | Gravity.LEFT;
        position.x = context.getSharedPreferences(CaptureService.PREFS, 0).getInt("overlay_x", dp(8));
        position.y = context.getSharedPreferences(CaptureService.PREFS, 0).getInt("overlay_y", dp(80));
        position.setTitle("NoFocus capture control");
        label.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                touchX = event.getRawX(); touchY = event.getRawY(); initialX = position.x; initialY = position.y; return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                position.x = initialX + Math.round(event.getRawX() - touchX);
                position.y = initialY + Math.round(event.getRawY() - touchY);
                clamp();
                if (attached) windows.updateViewLayout(root, position);
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                context.getSharedPreferences(CaptureService.PREFS, 0).edit().putInt("overlay_x", position.x).putInt("overlay_y", position.y).apply();
                return true;
            }
            return false;
        });
    }

    private Button button(String text, String description, Runnable callback) {
        Button button = new Button(context); button.setText(text); button.setTextSize(22); button.setTextColor(Color.WHITE);
        button.setAllCaps(false); button.setPadding(0, 0, 0, 0); button.setBackgroundColor(Color.TRANSPARENT);
        button.setMinWidth(0); button.setMinimumWidth(0); button.setMinHeight(0); button.setMinimumHeight(0);
        button.setContentDescription(description); button.setOnClickListener(view -> callback.run());
        root.addView(button, new LinearLayout.LayoutParams(dp(48), dp(48))); return button;
    }

    void show() { clamp(); windows.addView(root, position); attached = true; }
    void render(String text, boolean ready, boolean canStop, boolean canCancel) {
        label.setText(text);
        action.setText(ready ? "▶" : "■");
        action.setContentDescription(ready ? "Start recording" : "Stop and save");
        action.setEnabled(ready || canStop); action.setAlpha(action.isEnabled() ? 1f : .35f);
        close.setVisibility(canCancel ? View.VISIBLE : View.GONE);
        clamp();
        if (attached) windows.updateViewLayout(root, position);
    }
    void close() { if (attached) { attached = false; windows.removeView(root); } }

    private void clamp() {
        android.graphics.Rect bounds = Build.VERSION.SDK_INT >= 30 ? windows.getCurrentWindowMetrics().getBounds()
                : new android.graphics.Rect(0, 0, context.getResources().getDisplayMetrics().widthPixels, context.getResources().getDisplayMetrics().heightPixels);
        int width = dp(close.getVisibility() == View.VISIBLE ? 184 : 136);
        position.x = Math.max(0, Math.min(position.x, Math.max(0, bounds.width() - width)));
        position.y = Math.max(0, Math.min(position.y, Math.max(0, bounds.height() - dp(96))));
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
