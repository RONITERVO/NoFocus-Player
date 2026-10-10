package dev.nofocus.folderplayer;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;

/** One explicit pairing approval covers audio and exports; scanning never starts capture. */
public final class VisualMusicPairActivity extends Activity {
    private VisualMusicPc client;
    private TextView status;
    private Button pair, paste;
    private String code = "";
    private boolean busy;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (Build.VERSION.SDK_INT < 29) { new AlertDialog.Builder(this).setMessage("Unified pairing requires Android 10 or newer. Manual audio setup is still available.").setPositiveButton("Close", (d,w) -> finish()).show(); return; }
        client = new VisualMusicPc(new VisualMusicLibrary(this));
        code = getIntent().getDataString(); if (code == null) code = saved == null ? "" : saved.getString("code", "");
        PcPage page = new PcPage(this, "Connect PC"); status = page.status;
        pair = page.button("Pair PC","Pair",v -> pair());
        paste = page.button("Paste code","Paste",v -> {
            ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            if(clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount()>0) code=clipboard.getPrimaryClip().getItemAt(0).coerceToText(this).toString();
            showCode();
        });
        page.button("Help","Help",v -> new AlertDialog.Builder(this).setMessage("Open NoFocus on Windows and choose Pair phone. Scan its QR code with your phone camera, or paste its pairing code here. Both devices need the same home network. One pairing connects audio in both directions and video exports. Only pair your own PC.").setPositiveButton("OK",null).show());
        showCode();
    }
    private void showCode() {
        pair.setEnabled(false);
        if(code.isEmpty()) { status.setText("On Windows: Pair phone\nScan the QR code or paste its code here.");return; }
        try { JSONObject data=VisualMusicPc.parsePairing(code);status.setText("Connect to " + data.optString("name","your PC") + "?\nAudio + video exports");pair.setEnabled(!busy); }
        catch(Exception e) {status.setText("That code is not a NoFocus PC pairing code. Copy it from Pair phone on Windows.");}
    }
    private void pair() {
        if(busy)return;
        if (Build.VERSION.SDK_INT >= 36 && checkSelfPermission(networkPermission()) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{networkPermission()}, 81); return;
        }
        busy=true;pair.setEnabled(false);paste.setEnabled(false);status.setText("Connecting…");
        final String approvedCode = code;
        new Thread(() -> {
            try {client.pair(approvedCode);runOnUiThread(() -> {if(!isFinishing() && !isDestroyed()) {Toast.makeText(this,"PC paired",Toast.LENGTH_LONG).show();startActivity(new Intent(this,MainActivity.class).putExtra("pcSetup",true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));finish();}});}
            catch(Exception e) {runOnUiThread(() -> {if(!isFinishing() && !isDestroyed()) {busy=false;status.setText(e.getMessage());pair.setEnabled(true);paste.setEnabled(true);}});}
        },"pair-pc").start();
    }
    private String networkPermission() { return Build.VERSION.SDK_INT >= 37 ? "android.permission.ACCESS_LOCAL_NETWORK" : "android.permission.NEARBY_WIFI_DEVICES"; }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==81) {if(grants.length>0 && grants[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)pair();else status.setText("Allow nearby devices in Settings to connect to your PC.");}
    }
    @Override protected void onSaveInstanceState(Bundle out){out.putString("code",code);super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){if(client!=null)client.cancelIo();super.onDestroy();}
}
