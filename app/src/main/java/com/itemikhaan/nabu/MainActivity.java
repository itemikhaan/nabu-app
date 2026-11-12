package com.itemikhaan.nabu;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

@SuppressLint("MissingPermission")
public class MainActivity extends Activity {
    private TextView logView;
    private ScrollView scroll;
    private final Handler h = new Handler(Looper.getMainLooper());
    private BluetoothLeScanner scanner;
    private ScanCallback scanCb;
    private final java.util.HashSet<String> seen = new java.util.HashSet<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(p, p * 2, p, p);
        TextView title = new TextView(this);
        title.setText("Nabu Watch");
        title.setTextSize(24);
        root.addView(title);
        root.addView(btn("1. Allow Bluetooth", v -> askPerms()));
        root.addView(btn("2. Allow notification access", v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));
        root.addView(btn("3. Find and connect watch", v -> scan()));
        root.addView(btn("Test buzz", v -> {
            log("Sending test notification...");
            NabuClient.get(this).sendNotification("Hello", "Test message");
        }));
        root.addView(btn("Battery", v -> NabuClient.get(this).readBattery()));
        root.addView(btn("Disconnect", v -> NabuClient.get(this).disconnect()));
        scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextIsSelectable(true);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        log("Put the watch on the charger first. Follow steps 1, 2, 3.");
    }

    private Button btn(String t, View.OnClickListener l) {
        Button x = new Button(this);
        x.setText(t);
        x.setOnClickListener(l);
        return x;
    }

    @Override protected void onResume() {
        super.onResume();
        NabuClient.get(this).listener = this::log;
    }

    @Override protected void onPause() {
        super.onPause();
        NabuClient.get(this).listener = null;
    }

    private void log(String s) {
        logView.append(s + "\n");
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private boolean hasPerms() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void askPerms() {
        if (hasPerms()) { log("Bluetooth already allowed."); return; }
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, 1);
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        log(hasPerms() ? "Bluetooth allowed." : "Bluetooth NOT allowed. Press step 1 again.");
    }

    private void scan() {
        if (!hasPerms()) { log("Do step 1 first."); return; }
        BluetoothAdapter ad = ((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
        if (ad == null || !ad.isEnabled()) { log("Turn Bluetooth on."); return; }
        scanner = ad.getBluetoothLeScanner();
        log("Searching for Nabu (15 seconds)...");
        seen.clear();
        for (BluetoothDevice bd : ad.getBondedDevices()) log("Paired in phone: " + bd.getName() + " " + bd.getAddress());
        scanCb = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult r) {
                BluetoothDevice d = r.getDevice();
                String n = d.getName();
                if (n == null && r.getScanRecord() != null) n = r.getScanRecord().getDeviceName();
                if (seen.add(d.getAddress())) log("Seen: " + n + " " + d.getAddress());
                boolean match = d.getAddress().equalsIgnoreCase("FF:C0:8A:3F:45:1E")
                        || (n != null && n.toLowerCase().startsWith("nabu"));
                if (match) {
                    stopScan();
                    log("Found " + n + " " + d.getAddress());
                    NabuClient.get(MainActivity.this).connect(d);
                }
            }
        };
        scanner.startScan(scanCb);
        h.postDelayed(() -> { if (scanCb != null) { stopScan(); log("Not found. Make sure the watch is on the charger and not connected to a PC."); } }, 15000);
    }

    private void stopScan() {
        if (scanner != null && scanCb != null) { try { scanner.stopScan(scanCb); } catch (Exception ignored) {} }
        scanCb = null;
    }
}
