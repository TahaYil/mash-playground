package org.example.auramesh.hardware.BluetoothLE;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import org.example.auramesh.data.models.NeighborDevice;
import org.example.auramesh.utils.AppConstants;
import org.example.auramesh.utils.HardwareUtil;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@SuppressLint("MissingPermission")
public class AuraBleScanner {

    private static final String TAG = "AuraScanner";
    private static final ParcelUuid AURA_MESH_UUID = AppConstants.AURA_MESH_UUID;
    private static final long SCAN_SYNC_INTERVAL = 5_000;
    private final BluetoothLeScanner scanner;
    private final Handler syncHandler = new Handler(Looper.getMainLooper());

    // Geçici Sepet (Tarama sırasında çoklu thread çakışmasını önlemek için ConcurrentHashMap)
    private final Map<String, NeighborDevice> temporaryBuffer = new ConcurrentHashMap<>();

    public AuraBleScanner() {
        this.scanner = BluetoothAdapter.getDefaultAdapter().getBluetoothLeScanner();
    }

    private final Runnable syncRunnable = new Runnable() {
        @Override
        public void run() {
            if (!temporaryBuffer.isEmpty()) {
                // Sepetin bir kopyasını çıkarıp HardwareUtil'e veriyoruz
                // O da Register'ı güncelleyip EventBus ile Router'a sadece 1 zarf fırlatacak
                HardwareUtil.SyncNeighborList(new HashMap<>(temporaryBuffer));

                temporaryBuffer.clear();
            }

            syncHandler.postDelayed(this, SCAN_SYNC_INTERVAL);
        }
    };

    public void start() {
        if (scanner == null) return;

        ScanFilter filter = new ScanFilter.Builder()
                .setServiceData(AURA_MESH_UUID, null)
                .build();

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        Log.d(TAG, "BLE Dinleyici başlatıldı.");

        syncHandler.post(syncRunnable);
    }

    public void stop() {
        if (scanner != null) scanner.stopScan(scanCallback);
        syncHandler.removeCallbacksAndMessages(null);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            //todo: ileride bluetooth kapalıysa kullanıcıya açmasını söyle
            if (result.getScanRecord() == null){
                Log.d(TAG, "ScanRecord null döndü.");
                return;
            }
            byte[] payload = result.getScanRecord().getServiceData(AURA_MESH_UUID);

            if (payload == null || payload.length < 18) return;

            ByteBuffer buffer = ByteBuffer.wrap(payload);

            byte[] nodeBytes = new byte[6];
            buffer.get(nodeBytes);
            String targetNodeId = byteArrayToHexString(nodeBytes);

            long hashLong = buffer.getLong();
            String targetHash = String.format("%016X", hashLong);

            int targetCount = buffer.getInt();

            int rssi = result.getRssi();
            BluetoothDevice physicalDevice = result.getDevice(); // Soket bağlantısı için kapı kolu

            NeighborDevice device = new NeighborDevice(
                    targetNodeId,
                    rssi,
                    targetCount,
                    targetHash,
                    physicalDevice
            );

            if (!temporaryBuffer.containsKey(targetNodeId) || !device.equals(temporaryBuffer.get(targetNodeId))){
                temporaryBuffer.put(targetNodeId, device);
            }
        }
    };


    private String byteArrayToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}