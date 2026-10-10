package dev.nofocus.folderplayer;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.net.*;
import java.util.concurrent.atomic.*;

/** Uses the real Android sharing prompt and synthetic audio. Nothing is saved. */
final class PhoneAudioRuntimeTest {
    static void runRemote(Instrumentation test, String host, String code) throws Exception {
        Context context = test.getTargetContext();
        android.content.SharedPreferences prefs = context.getSharedPreferences(PhoneAudioService.PREFS, 0);
        boolean hadHost = prefs.contains("host"), hadCode = prefs.contains("code");
        String oldHost = prefs.getString("host", ""), oldCode = prefs.getString("code", "");
        Activity page = null;
        try {
            prefs.edit().putString("host", host).putString("code", code).commit();
            test.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.RECORD_AUDIO);
            page = test.startActivitySync(new Intent(context, PhoneAudioActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Activity screen = page;
            test.runOnMainSync(() -> findButton(screen.getWindow().getDecorView(), "Start phone audio").performClick());
            long until = SystemClock.elapsedRealtime() + 60_000;
            while (!PhoneAudioService.running && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100);
            if (!PhoneAudioService.running) throw new AssertionError("Sharing was not approved.");
            context.startActivity(new Intent().setClassName("dev.nofocus.folderplayer.test", CaptureToneActivity.class.getName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(12000);
            if (!PhoneAudioService.running || !PhoneAudioService.message.startsWith("Streaming"))
                throw new AssertionError(PhoneAudioService.message);
        } finally {
            context.stopService(new Intent(context, PhoneAudioService.class));
            stopTone(context);
            if (page != null) { Activity screen = page; test.runOnMainSync(screen::finish); }
            android.content.SharedPreferences.Editor edit = prefs.edit();
            if (hadHost) edit.putString("host", oldHost); else edit.remove("host");
            if (hadCode) edit.putString("code", oldCode); else edit.remove("code");
            edit.commit();
        }
    }
    static void run(Instrumentation test, boolean timeout) throws Exception {
        Context context = test.getTargetContext();
        String code = "ABCDEFGHJKLMNPQR";
        byte[] key = WifiAudioProtocol.keyFromPairingCode(code);
        android.content.SharedPreferences prefs = context.getSharedPreferences(PhoneAudioService.PREFS, 0);
        boolean hadHost = prefs.contains("host"), hadCode = prefs.contains("code");
        String oldHost = prefs.getString("host", ""), oldCode = prefs.getString("code", "");
        AtomicBoolean receiving = new AtomicBoolean(true), acknowledge = new AtomicBoolean(true);
        AtomicLong frames = new AtomicLong(), sounding = new AtomicLong();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Activity activity = null;
        try (DatagramSocket socket = new DatagramSocket(WifiAudioProtocol.PC_RECEIVER_PORT)) {
            socket.setSoTimeout(250);
            Thread receiver = new Thread(() -> {
                long activeSession = 0, lastSequence = -1;
                byte[] bytes = new byte[4096];
                while (receiving.get()) {
                    DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
                    try {
                        socket.receive(packet);
                        if (packet.getLength() == 64) {
                            activeSession = WifiAudioProtocol.parseHello(bytes, packet.getLength(), key).sessionId;
                            if (acknowledge.get()) {
                                byte[] ack = WifiAudioProtocol.helloAcknowledgement(activeSession, key);
                                socket.send(new DatagramPacket(ack, ack.length, packet.getAddress(), packet.getPort()));
                            }
                        } else {
                            WifiAudioProtocol.AudioPacket audio = WifiAudioProtocol.decryptAudio(bytes, packet.getLength(), key);
                            if (audio.sessionId != activeSession || audio.sequence <= lastSequence) continue;
                            lastSequence = audio.sequence; frames.incrementAndGet();
                            for (byte sample : audio.pcm) if (sample != 0) { sounding.incrementAndGet(); break; }
                        }
                    } catch (SocketTimeoutException ignored) { }
                    catch (Throwable error) { if (receiving.get()) failure.set(error); }
                }
            }, "Phone-audio-test-receiver");
            receiver.start();
            try {
                prefs.edit().putString("host", NetworkAddress.localIpv4()).putString("code", code).commit();
                test.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.RECORD_AUDIO);
                activity = test.startActivitySync(new Intent(context, PhoneAudioActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                test.waitForIdleSync();
                Activity page = activity;
                test.runOnMainSync(() -> findButton(page.getWindow().getDecorView(), "Start phone audio").performClick());
                // Approve the actual Android sharing dialog on the device under test.
                long until = SystemClock.elapsedRealtime() + 60_000;
                while (!PhoneAudioService.running && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100);
                if (!PhoneAudioService.running) throw new AssertionError("Approve the Android sharing prompt within 60 seconds.");
                Intent tone = new Intent().setClassName("dev.nofocus.folderplayer.test", CaptureToneActivity.class.getName())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(tone);
                until = SystemClock.elapsedRealtime() + 15_000;
                while (sounding.get() < 200 && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100);
                if (failure.get() != null) throw new AssertionError(failure.get());
                if (sounding.get() < 200) throw new AssertionError("No captured tone: " + PhoneAudioService.message + "; packets=" + frames.get());
                test.runOnMainSync(page::finish);
                activity = null; // Leaving the setup page must not end the foreground session.
                if (timeout) {
                    acknowledge.set(false);
                    until = SystemClock.elapsedRealtime() + 8000;
                    while (PhoneAudioService.running && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100);
                    if (PhoneAudioService.running) throw new AssertionError("Missing PC acknowledgements did not end capture.");
                    if (!PhoneAudioService.message.contains("connection lost")) throw new AssertionError(PhoneAudioService.message);
                } else {
                    long before = frames.get();
                    SystemClock.sleep(500);
                    if (frames.get() <= before) throw new AssertionError("Closing setup ended the stream.");
                    context.stopService(new Intent(context, PhoneAudioService.class));
                    until = SystemClock.elapsedRealtime() + 4000;
                    while (PhoneAudioService.running && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50);
                    if (PhoneAudioService.running) throw new AssertionError("Stop did not release capture.");
                }
                for (android.service.notification.StatusBarNotification notification :
                        context.getSystemService(NotificationManager.class).getActiveNotifications())
                    if (notification.getId() == 41) throw new AssertionError("Streaming notification remained after stop.");
            } finally {
                stopTone(context);
                receiving.set(false); receiver.join(1000);
                context.stopService(new Intent(context, PhoneAudioService.class));
                if (activity != null) { Activity page = activity; test.runOnMainSync(page::finish); }
                android.content.SharedPreferences.Editor edit = prefs.edit();
                if (hadHost) edit.putString("host", oldHost); else edit.remove("host");
                if (hadCode) edit.putString("code", oldCode); else edit.remove("code");
                edit.commit();
            }
        }
    }
    private static Button findButton(View view, String label) {
        if (view instanceof Button && label.contentEquals(((Button)view).getText())) return (Button)view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            Button child = findButton(((ViewGroup)view).getChildAt(i), label);
            if (child != null) return child;
        }
        return null;
    }
    private static void stopTone(Context context) {
        context.startActivity(new Intent().setClassName("dev.nofocus.folderplayer.test", CaptureToneActivity.class.getName())
                .putExtra("stop", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }
}
