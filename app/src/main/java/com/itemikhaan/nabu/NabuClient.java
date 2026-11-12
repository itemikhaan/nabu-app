package com.itemikhaan.nabu;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.UUID;

/** Talks to the Razer Nabu Watch. All work happens on the main thread. */
@SuppressLint({"MissingPermission", "deprecation"})
public class NabuClient {
    public interface Listener { void onLog(String line); }

    static final UUID SVC = UUID.fromString("5240263a-f97c-7f90-0e7f-6c6f4e36db1c");
    static final UUID CMD = UUID.fromString("00003a02-0000-1000-8000-00805f9b34fb");
    private static final byte[] FIXED_KEY = {102, (byte) 245, (byte) 196, 89, 21, 4, 9, (byte) 254,
            23, 56, (byte) 255, 52, (byte) 151, (byte) 157, (byte) 183, 72};
    private static final byte[] PIN = {0, 0, 0, 0};

    private static NabuClient inst;
    public static synchronized NabuClient get(Context c) {
        if (inst == null) inst = new NabuClient(c.getApplicationContext());
        return inst;
    }

    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic cmd;
    private boolean ready, busy, connecting;
    private int notifId = 1;
    private String pendingTitle, pendingText;
    public Listener listener;
    public String status = "Not connected";
    public int battery = -1;

    private NabuClient(Context c) {
        ctx = c;
        prefs = c.getSharedPreferences("nabu", Context.MODE_PRIVATE);
    }

    public boolean isReady() { return ready; }
    public String savedAddress() { return prefs.getString("addr", null); }

    private void log(final String s) {
        h.post(() -> { if (listener != null) listener.onLog(s); });
    }

    private byte[] userKey() {
        String s = prefs.getString("key", null);
        if (s != null && s.length() == 32) {
            byte[] k = new byte[16];
            for (int i = 0; i < 16; i++) k[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
            return k;
        }
        byte[] k = new byte[16];
        new SecureRandom().nextBytes(k);
        StringBuilder sb = new StringBuilder();
        for (byte b : k) sb.append(String.format("%02x", b & 255));
        prefs.edit().putString("key", sb.toString()).apply();
        return k;
    }

    public void connectSaved() {
        String a = savedAddress();
        if (a == null || ready || connecting) return;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm.getAdapter();
        if (ad == null || !ad.isEnabled()) { log("Bluetooth is off."); return; }
        connect(ad.getRemoteDevice(a));
    }

    public void connect(BluetoothDevice dev) {
        h.post(() -> {
            closeGatt();
            prefs.edit().putString("addr", dev.getAddress()).apply();
            connecting = true;
            status = "Connecting...";
            log("Connecting to " + dev.getAddress());
            gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE);
        });
    }

    public void disconnect() { h.post(this::closeGatt); }

    private void closeGatt() {
        ready = false; busy = false; connecting = false; cmd = null;
        queue.clear();
        h.removeCallbacks(timeout);
        if (gatt != null) { try { gatt.close(); } catch (Exception ignored) {} gatt = null; }
        status = "Not connected";
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int st, int ns) {
            h.post(() -> {
                if (g != gatt) return;
                if (ns == BluetoothProfile.STATE_CONNECTED) {
                    log("Connected. Looking for services...");
                    g.discoverServices();
                } else {
                    log("Disconnected (code " + st + ").");
                    closeGatt();
                }
            });
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int st) {
            h.post(() -> {
                if (g != gatt) return;
                BluetoothGattService s = g.getService(SVC);
                if (s == null || s.getCharacteristic(CMD) == null) {
                    log("This device is not a Nabu Watch (service not found).");
                    closeGatt();
                    return;
                }
                cmd = s.getCharacteristic(CMD);
                connecting = false;
                ready = true;
                status = "Connected";
                log("Pairing...");
                pair();
                send(new byte[]{(byte) 0xF1, 5, 0, 1, 1}); // battery request
                send(new byte[]{0x45, 6, 0, 1, 1, 1});     // enable notifications on watch
                if (pendingTitle != null) {
                    String t = pendingTitle, m = pendingText;
                    pendingTitle = pendingText = null;
                    sendNotification(t, m);
                }
            });
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int st) {
            h.post(() -> {
                if (!busy) return;
                if (st != BluetoothGatt.GATT_SUCCESS || !g.readCharacteristic(c)) finish();
            });
        }

        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int st) {
            final byte[] v = c.getValue();
            h.post(() -> {
                handleReply(v);
                finish();
            });
        }
    };

    private void handleReply(byte[] v) {
        if (v == null || v.length == 0) return;
        if ((v[0] & 255) == 0x71 && v.length >= 6) {
            battery = v[4] & 255;
            status = "Connected, battery " + battery + "%" + (v[5] != 0 ? " (charging)" : "");
            log("Battery " + battery + "%");
        }
    }

    // ---- command queue: write, then read the reply, then next ----
    private final Runnable pumpR = this::pump;
    private final Runnable timeout = this::finish;

    private void send(byte[] d) { queue.add(d); h.post(pumpR); }

    private void pump() {
        if (busy || queue.isEmpty() || gatt == null || cmd == null) return;
        byte[] d = queue.poll();
        busy = true;
        cmd.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        cmd.setValue(d);
        if (!gatt.writeCharacteristic(cmd)) { finish(); return; }
        h.postDelayed(timeout, 3000);
    }

    private void finish() {
        h.removeCallbacks(timeout);
        busy = false;
        h.postDelayed(pumpR, 120);
    }

    private void pair() {
        byte[] enc = Xxtea.encrypt(userKey(), FIXED_KEY);
        send(new byte[]{0x42, 9, 0, 1, PIN[0], PIN[1], PIN[2], PIN[3], 1});
        send(new byte[]{(byte) 0xc3, 5, 0, 1, 1});
        byte[] p = new byte[20];
        p[0] = 0x46; p[1] = 20; p[2] = 0; p[3] = 1;
        System.arraycopy(enc, 0, p, 4, 16);
        send(p);
        send(new byte[]{(byte) 0xc7, 5, 0, 1, 1});
    }

    private byte[] pad16(byte[] b, int len) {
        byte[] o = new byte[len];
        System.arraycopy(b, 0, o, 0, Math.min(b.length, len));
        return o;
    }

    private byte[] packet(int type, int flag, byte[] plain16) {
        byte[] enc = Xxtea.encrypt(plain16, userKey());
        byte[] p = new byte[4 + enc.length];
        p[0] = (byte) type; p[1] = (byte) p.length; p[2] = (byte) flag; p[3] = 1;
        System.arraycopy(enc, 0, p, 4, enc.length);
        return p;
    }

    /** Shows/buzzes a notification on the watch. Connects first if needed. */
    public void sendNotification(String title, String text) {
        h.post(() -> {
            if (!ready) {
                pendingTitle = title; pendingText = text;
                connectSaved();
                return;
            }
            int id = notifId++;
            byte[] header = {0x21, 0x13, 0, 1,
                    (byte) (id >> 24), (byte) (id >> 16), (byte) (id >> 8), (byte) id,
                    0, 12, 2, 2, 2, 3, 3, 3, (byte) 0xff, (byte) 0xf0, 0};
            send(header);
            send(packet(0x32, 0x40, pad16(title == null ? new byte[0] : title.getBytes(), 16)));
            byte[] m = text == null ? new byte[0] : text.getBytes();
            int len = Math.min(128, Math.max(16, (m.length + 15) / 16 * 16));
            byte[] mp = pad16(m, len);
            int chunks = len / 16;
            for (int i = 0; i < chunks; i++) {
                int flag = 0x40 | (i & 0x0f) | (i < chunks - 1 ? 0x80 : 0);
                send(packet(0x33, flag, Arrays.copyOfRange(mp, i * 16, i * 16 + 16)));
            }
        });
    }

    public void readBattery() { h.post(() -> { if (ready) send(new byte[]{(byte) 0xF1, 5, 0, 1, 1}); }); }
}
