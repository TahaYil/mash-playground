package org.example.auramesh.utils;

import org.example.auramesh.Register.NeighborRegister;
import org.example.auramesh.data.models.NeighborDevice;
import org.example.auramesh.events.HardwareToRouterEvents.NeighborUpdatedEvent;
import org.greenrobot.eventbus.EventBus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HardwareUtil {
    private static final Map<String, Long> lastSeenMap = new ConcurrentHashMap<>();

    //todo: ileride aha uzun  veya kısa süre beklenmesini gerektiren uygulama modları arasında (örn: ulra güç tasarrufu vb) değiştirilir diye appconst a eklenip mod değişikliğinde orası değiştirilebilir bilmiyorum emin değilim üstüne düşünmedim
    private static final long timeout = 30_000;

    //taha sana yardımcı olması için yazdım blescannerda buffer ile registerdakileri bu method yardımıyla karşılaştıracaksın
    public static void SyncNeighborList(Map<String, NeighborDevice> newNeighborMap) {
        NeighborRegister neighborRegister = NeighborRegister.getInstance();
        Map<String, NeighborDevice> oldNeighborMap = neighborRegister.getNeighborMap();

        boolean changed = false;

        long currentTime = System.currentTimeMillis();

        for (Map.Entry<String, NeighborDevice> entry : newNeighborMap.entrySet()) {
            String macAddress = entry.getKey();
            NeighborDevice newDevice = entry.getValue();

            lastSeenMap.put(macAddress, currentTime);

            NeighborDevice oldDevice = oldNeighborMap.get(macAddress);
            if (oldDevice == null) {

                neighborRegister.addOrUpdateNeighbor(macAddress, newDevice);
                changed = true;
            } else if (!oldDevice.messageHash.equals(newDevice.messageHash) || oldDevice.messageCount != newDevice.messageCount) {

                neighborRegister.addOrUpdateNeighbor(macAddress, newDevice);
                changed = true;
            }
        }

        for (String macAddress : oldNeighborMap.keySet()) {
            Long lastSeen = lastSeenMap.get(macAddress);

            if (lastSeen == null || (currentTime - lastSeen > timeout)) {
                neighborRegister.deleteNeighbor(macAddress);
                lastSeenMap.remove(macAddress);
                changed = true;
            }
        }

        if (changed) {
            EventBus.getDefault().post(new NeighborUpdatedEvent());
        }
    }
}