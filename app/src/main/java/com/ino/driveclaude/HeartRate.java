package com.ino.driveclaude;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.util.Collections;
import java.util.UUID;

/**
 * Bluetoothの標準「心拍サービス」(0x180D) を出している機器（心拍ベルトや、心拍の送信をオンにしたスマートウォッチ）から心拍を受け取る。
 * 近くの最初の1台につなぐ。
 */
@SuppressLint("MissingPermission")
final class HeartRate {
    interface Listener { void onBpm(int bpm); void onState(String msg); }

    private static final UUID HR_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    private static final UUID HR_CHAR = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    private static final UUID CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context ctx;
    private final Listener cb;
    private final Handler h = new Handler(Looper.getMainLooper());
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private boolean stopped;
    private boolean found;

    HeartRate(Context c, Listener l) {
        ctx = c.getApplicationContext();
        cb = l;
    }

    void start() {
        BluetoothManager bm = ctx.getSystemService(BluetoothManager.class);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) { cb.onState("Bluetoothがオフです。心拍なしで続けます。"); return; }
        scanner = ad.getBluetoothLeScanner();
        if (scanner == null) return;
        ScanFilter f = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(HR_SERVICE)).build();
        scanner.startScan(Collections.singletonList(f),
                new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan);
        h.postDelayed(() -> {
            if (!found && !stopped) {
                stopScan();
                cb.onState("心拍の機器が見つかりませんでした。心拍なしで続けます。");
            }
        }, 20000);
    }

    void stop() {
        stopped = true;
        h.removeCallbacksAndMessages(null);
        stopScan();
        if (gatt != null) { try { gatt.close(); } catch (Exception ignored) { } gatt = null; }
    }

    private void stopScan() {
        if (scanner != null) { try { scanner.stopScan(scan); } catch (Exception ignored) { } }
    }

    private final ScanCallback scan = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult r) {
            if (found || stopped) return;
            found = true;
            stopScan();
            String name = r.getDevice().getName();
            cb.onState("心拍の機器につなぎます" + (name == null ? "" : "：" + name));
            gatt = r.getDevice().connectGatt(ctx, false, gattCb);
        }
    };

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == 2) g.discoverServices();
            else if (!stopped) h.post(() -> cb.onState("心拍の機器との接続が切れました。"));
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService s = g.getService(HR_SERVICE);
            BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(HR_CHAR);
            if (c == null) return;
            g.setCharacteristicNotification(c, true);
            BluetoothGattDescriptor d = c.getDescriptor(CCC);
            if (d != null) {
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(d);
            }
            h.post(() -> cb.onState("心拍を受信できる状態になりました。"));
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            parse(c.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            parse(value);
        }
    };

    private void parse(byte[] v) {
        if (v == null || v.length < 2) return;
        int bpm = (v[0] & 1) == 0 ? (v[1] & 0xFF) : ((v[1] & 0xFF) | ((v[2] & 0xFF) << 8));
        if (bpm > 0 && bpm < 250) h.post(() -> cb.onBpm(bpm));
    }
}
