package dev.nofocus.folderplayer;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Compact listening controls; pairing and technical help stay on separate pages. */
public final class PhoneAudioActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService discovery = Executors.newSingleThreadExecutor();
    private volatile DatagramSocket discoverySocket;
    private EditText address, code;
    private TextView status;
    private Button start, stop, find;
    private boolean finding, pendingConsent;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); main.postDelayed(this, 500); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        PcPage page = new PcPage(this, "Listen on PC");
        SharedPreferences prefs = getSharedPreferences(PhoneAudioService.PREFS, 0);
        // Manual address/code entry is a separate dialog, not everyday playback UI.
        LinearLayout manual = new LinearLayout(this); manual.setOrientation(LinearLayout.VERTICAL); manual.setPadding(dp(16), 0, dp(16), 0);
        text(manual, "PC address", 17); address = input(manual, "192.168.1.100", InputType.TYPE_CLASS_PHONE);
        address.setText(saved == null ? prefs.getString("host", "") : saved.getString("host", ""));
        find = button(manual, "Find PC", v -> findPc()); text(manual, "Audio pairing code", 17);
        code = input(manual, "ABCD-EFGH-JKLM-NPQR", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        code.setText(saved == null ? prefs.getString("code", "") : saved.getString("code", ""));
        if (saved != null) pendingConsent = saved.getBoolean("pending");
        status = page.status;
        start = page.button("Start phone audio", "Start", v -> requestStart());
        stop = page.button("Stop streaming", "Stop", v -> stopService(new Intent(this, PhoneAudioService.class)));
        page.button("Connect PC", "Connect", v -> startActivity(new Intent(this, VisualMusicPairActivity.class)));
        page.button("More options", "Options", v -> new AlertDialog.Builder(this).setItems(prefs.getBoolean("unified", false) ? new String[]{"How it works"} : new String[]{"Manual audio setup", "How it works"}, (d, which) -> {
            if (which == 1 || prefs.getBoolean("unified", false)) { new AlertDialog.Builder(this).setMessage("Open NoFocus on Windows and choose Listen on PC. Android asks for sharing permission each time. Only apps that allow playback capture can be heard. The phone may keep playing locally; test media volume zero. Sharing can stop when the phone locks. Audio is encrypted, uncompressed stereo; no microphone or recording.").setPositiveButton("OK", null).show(); return; }
            if (manual.getParent() != null) ((android.view.ViewGroup) manual.getParent()).removeView(manual);
            ScrollView scroll = new ScrollView(this); scroll.addView(manual);
            new AlertDialog.Builder(this).setTitle("Manual audio setup").setView(scroll).setPositiveButton("Save", (dialog, w) -> {
                prefs.edit().putString("host", address.getText().toString().trim()).putString("code", code.getText().toString().replace("-", "").trim().toUpperCase(Locale.ROOT)).apply();
            }).setNegativeButton("Cancel", (dialog, w) -> { address.setText(prefs.getString("host", "")); code.setText(prefs.getString("code", "")); }).show();
        }).show());
        render();
    }

    static boolean validAddress(String text) {
        if (text == null || !text.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        String[] octets = text.split("\\.");
        for (String octet : octets) if (Integer.parseInt(octet) > 255) return false;
        int first = Integer.parseInt(octets[0]);
        return first > 0 && first < 224 && first != 127;
    }

    private void requestStart() {
        if (Build.VERSION.SDK_INT < 29 || PhoneAudioService.running || pendingConsent) return;
        if (CaptureService.running) { PhoneAudioService.message = "Stop the lyrics capture first."; render(); return; }
        String host = address.getText().toString().trim();
        String pairing = code.getText().toString().replace("-", "").trim().toUpperCase(Locale.ROOT);
        if (!validAddress(host)) { startActivity(new Intent(this, VisualMusicPairActivity.class)); return; }
        if (!pairing.matches("[A-Z0-9]{16}")) { startActivity(new Intent(this, VisualMusicPairActivity.class)); return; }
        getSharedPreferences(PhoneAudioService.PREFS, 0).edit().putString("host", host).putString("code", pairing).apply();
        if (Build.VERSION.SDK_INT >= 36 && checkSelfPermission(networkPermission()) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{networkPermission()}, 73); return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(Build.VERSION.SDK_INT >= 33
                    ? new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}
                    : new String[]{Manifest.permission.RECORD_AUDIO}, 71);
            return;
        }
        pendingConsent = true; render();
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        // Audio-only capture; no virtual display, video frames or screen contents are created.
        Intent consent = Build.VERSION.SDK_INT >= 34
                ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : manager.createScreenCaptureIntent();
        startActivityForResult(consent, 72);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 73 || request == 74) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) { if (request == 73) requestStart(); else findPc(); }
            else { PhoneAudioService.message = "Allow nearby devices in Settings to connect to your PC."; render(); }
        }
        if (request == 71) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) requestStart();
            else { PhoneAudioService.message = "Audio permission is needed for internal playback capture. The microphone is never used."; render(); }
        }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 72) return;
        pendingConsent = false;
        if (result == RESULT_OK && data != null && Build.VERSION.SDK_INT >= 29) {
            SharedPreferences prefs = getSharedPreferences(PhoneAudioService.PREFS, 0);
            try {
                startForegroundService(new Intent(this, PhoneAudioService.class).putExtra("consent", data)
                        .putExtra("host", prefs.getString("host", "")).putExtra("code", prefs.getString("code", "")));
            } catch (RuntimeException error) { PhoneAudioService.message = "Could not start: " + error.getMessage(); }
        } else PhoneAudioService.message = "Sharing canceled. Nothing was streamed.";
        render();
    }

    private void findPc() {
        if (finding) return;
        if (Build.VERSION.SDK_INT >= 36 && checkSelfPermission(networkPermission()) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{networkPermission()}, 74); return;
        }
        finding = true; find.setEnabled(false); status.setText("Looking for a listening PC…");
        discovery.execute(() -> {
            String found = null;
            try (DatagramSocket socket = new DatagramSocket()) {
                discoverySocket = socket; socket.setBroadcast(true); socket.setSoTimeout(250);
                Set<InetAddress> broadcasts = new LinkedHashSet<>();
                broadcasts.add(InetAddress.getByName("255.255.255.255"));
                for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces()))
                    if (network.isUp() && !network.isLoopback()) for (InterfaceAddress item : network.getInterfaceAddresses())
                        if (item.getBroadcast() != null) broadcasts.add(item.getBroadcast());
                byte[] request = WifiAudioProtocol.discoveryRequest(), bytes = new byte[32];
                long end = SystemClock.elapsedRealtime() + 4000, nextProbe = 0;
                while (!socket.isClosed() && SystemClock.elapsedRealtime() < end && found == null) {
                    if (SystemClock.elapsedRealtime() >= nextProbe) {
                        for (InetAddress host : broadcasts) try {
                            socket.send(new DatagramPacket(request, request.length, host, WifiAudioProtocol.PC_RECEIVER_PORT));
                        } catch (IOException ignored) { }
                        nextProbe = SystemClock.elapsedRealtime() + 750;
                    }
                    DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
                    try {
                        socket.receive(packet);
                        if (WifiAudioProtocol.isPcDiscoveryResponse(bytes, packet.getLength())) found = packet.getAddress().getHostAddress();
                    } catch (SocketTimeoutException ignored) { }
                }
            } catch (Exception ignored) { /* Manual entry remains available on isolated networks. */ }
            finally { discoverySocket = null; }
            String host = found;
            main.post(() -> {
                if (isDestroyed()) return;
                finding = false;
                if (host != null) address.setText(host);
                PhoneAudioService.message = host == null ? "PC not found. Start its listener or enter its address manually." : "PC found. Enter its pairing code and start.";
                render();
            });
        });
    }
    private void render() {
        boolean active = PhoneAudioService.running;
        start.setEnabled(Build.VERSION.SDK_INT >= 29 && !active && !pendingConsent);
        start.setVisibility(active ? View.GONE : View.VISIBLE); stop.setVisibility(active ? View.VISIBLE : View.GONE);
        stop.setEnabled(active); address.setEnabled(!active && !pendingConsent);
        code.setEnabled(!active && !pendingConsent); find.setEnabled(!finding && !active && !pendingConsent);
        if (!finding) status.setText(Build.VERSION.SDK_INT < 29 ? "Phone audio sharing requires Android 10 or newer." : PhoneAudioService.message);
    }
    private TextView text(LinearLayout body, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setPadding(0, dp(8), 0, dp(8)); body.addView(view); return view;
    }
    private EditText input(LinearLayout body, String hint, int type) {
        EditText view = new EditText(this); view.setSingleLine(true); view.setHint(hint); view.setInputType(type); view.setTextSize(18);
        view.setMinHeight(dp(56)); body.addView(view); return view;
    }
    private Button button(LinearLayout body, String label, View.OnClickListener action) {
        Button view = new Button(this); view.setText(label); view.setTextSize(17); view.setMinHeight(dp(48));
        view.setAllCaps(false); view.setOnClickListener(action); body.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String networkPermission() { return Build.VERSION.SDK_INT >= 37 ? "android.permission.ACCESS_LOCAL_NETWORK" : "android.permission.NEARBY_WIFI_DEVICES"; }
    @Override protected void onResume() {
        super.onResume(); SharedPreferences prefs = getSharedPreferences(PhoneAudioService.PREFS, 0);
        address.setText(prefs.getString("host", "")); code.setText(prefs.getString("code", ""));
        main.post(refresh);
    }
    @Override protected void onPause() { main.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("host", address.getText().toString()); out.putString("code", code.getText().toString());
        out.putBoolean("pending", pendingConsent); super.onSaveInstanceState(out);
    }
    @Override protected void onDestroy() {
        DatagramSocket socket = discoverySocket; if (socket != null) socket.close();
        discovery.shutdownNow(); main.removeCallbacks(refresh); super.onDestroy();
    }
}
