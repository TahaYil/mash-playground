package org.example.auramesh.routing;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.example.auramesh.Register.BleAdvertiseRegister;
import org.example.auramesh.Register.NeighborRegister;
import org.example.auramesh.data.local.AuraDatabase;
import org.example.auramesh.data.local.AuraMessageDao;
import org.example.auramesh.data.models.AuraMessage;
import org.example.auramesh.data.models.NeighborDevice;
import org.example.auramesh.events.HardwareToRegisterEvents.NewMessagesSavedToDatabaseEvent;
import org.example.auramesh.events.HardwareToRouterEvents.*;
import org.example.auramesh.events.HardwareToRouterEvents.Client.BluetoothConnectedAsClientEvent;
import org.example.auramesh.events.HardwareToRouterEvents.Client.BluetoothConnectingAsClientEvent;
import org.example.auramesh.events.HardwareToRouterEvents.Client.InventoryTransmittedAsClientEvent;
import org.example.auramesh.events.HardwareToRouterEvents.Client.MessagesReceivedAsClientEvent;
import org.example.auramesh.events.HardwareToRouterEvents.Client.MessagesTransmittedAsClientEvent;
import org.example.auramesh.events.HardwareToRouterEvents.Client.RemoteInventoryReceivedAsClientEvent;
import org.example.auramesh.events.HardwareToRouterEvents.Server.BluetoothConnectedAsServerEvent;
import org.example.auramesh.events.HardwareToRouterEvents.Server.IncomingBluetoothConnectionEvent;
import org.example.auramesh.events.HardwareToRouterEvents.Server.InventoryTransmittedAsServerEvent;
import org.example.auramesh.events.HardwareToRouterEvents.Server.MessagesReceivedAsServerEvent;
import org.example.auramesh.events.HardwareToRouterEvents.Server.MessagesTransmittedAsServerEvent;
import org.example.auramesh.events.HardwareToRouterEvents.Server.RemoteInventoryReceivedAsServerEvent;
import org.example.auramesh.events.RegisterToRouterEvents.MyNodeIdUpdatedEvent;
import org.example.auramesh.events.RouterToHardwareEvents.*;
import org.example.auramesh.events.UiToRouterEvents.UserSendMessageEvent;
import org.example.auramesh.utils.RouterUtil;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;
import java.util.Map;
import java.util.Random;

public class GossipRouter {

    private static final String TAG = "GossipRouter";
    private final AuraMessageDao messageDao;

    private enum RouterState {
        IDLE,
        CONNECTING,
        CONNECTED_AS_CLIENT,
        CONNECTED_AS_SERVER,
        BACKOFF,
        WAITING_JITTER,
        WAITING_NODE_ID,
    }

    private volatile RouterState currentState;
    private int backoffMultiplier = 1;
    private final Handler timeHandler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private boolean isLocalInvSent = false;
    private boolean isRemoteInvReceived = false;
    private boolean isLocalMsgsSent = false;
    private boolean isRemoteMsgsReceived = false;
    private List<String> currentRemoteInventory = null;

    public GossipRouter(Context context) {
        this.messageDao = AuraDatabase.getDatabase(context).auraMessageDao();
        EventBus.getDefault().register(this);

        if (BleAdvertiseRegister.getInstance().getMyNodeId() == null || BleAdvertiseRegister.getInstance().getMyNodeId().equals("000000000000")) {
            this.currentState = RouterState.WAITING_NODE_ID;
        } else {
            dropToIdleAndListen();
        }
    }

    //todo: buradaki currentRemoteInventory i sürekli nullarsak bir sorun olur mu diye araştır
    private void resetSyncFlags() {
        isLocalInvSent = false;
        isRemoteInvReceived = false;
        isLocalMsgsSent = false;
        isRemoteMsgsReceived = false;
        currentRemoteInventory = null;
    }

    private void dropToIdleAndListen() {
        currentState = RouterState.IDLE;
        resetSyncFlags();
        timeHandler.removeCallbacksAndMessages(null);

        int cooldownMs = random.nextInt(2000) + 1500;
        timeHandler.postDelayed(() -> {
            if (currentState == RouterState.IDLE) {
                checkPendingNeighborsAndAct();
            }
        }, cooldownMs);
    }


    //SERVER OLARAK BAĞLANDIĞIMDA KULLANACAĞIMIZ METOTLAR
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onIncomingBluetoothConnectionEvent(IncomingBluetoothConnectionEvent event) {
        if (currentState == RouterState.IDLE || currentState == RouterState.BACKOFF || currentState == RouterState.WAITING_JITTER) {

            currentState = RouterState.CONNECTING;
            //todo: burası şu an sadece bekleme işlemlerini yaptığımız için her şeyi iptal ediyor başka bir şey için bekleme yapmamız gerekirse ve burada iptal edilmemesi gerekirkse burada adrese teslim iptaller yapılmalı
            timeHandler.removeCallbacksAndMessages(null);


            //todo: herhangi bir durumda gelen bağlantı isteğini reddetmemiz gerekirse burada karar mekanizması yazaılacak
            boolean kabulEdilebilirMi = true; // Karar mekanizması

            if (kabulEdilebilirMi) {
            } else {
                EventBus.getDefault().post(new RejectConnectionRequestCommandEvent(event.bluetoothDevice));
                dropToIdleAndListen();
            }
        } else {
            EventBus.getDefault().post(new RejectConnectionRequestCommandEvent(event.bluetoothDevice));
        }
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onBluetoothConnectedAsServerEvent(BluetoothConnectedAsServerEvent event) {
        currentState = RouterState.CONNECTED_AS_SERVER;
        backoffMultiplier = 1;
        resetSyncFlags();
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onRemoteInventoryReceivedAsServerEvent(RemoteInventoryReceivedAsServerEvent event) {
        isRemoteInvReceived = true;
        currentRemoteInventory = event.remoteInventory;

        List<String> myInventory = messageDao.getInventory();
        EventBus.getDefault().post(new TransmitInventoryCommandEvent(myInventory));
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onInventoryTransmittedAsServerEvent(InventoryTransmittedAsServerEvent event) {
        isLocalInvSent = true;
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onMessagesReceivedAsServerEvent(MessagesReceivedAsServerEvent event) {
        isRemoteMsgsReceived = true;

        List<AuraMessage> messagesToSend = messageDao.getMissingMessages(currentRemoteInventory);
        EventBus.getDefault().post(new TransmitMessagesCommandEvent(messagesToSend));

        checkFinalizeSync();
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onMessagesTransmittedAsServerEvent(MessagesTransmittedAsServerEvent event) {
        isLocalMsgsSent = true;
        checkFinalizeSync();
    }


    //CLIENT OLARAK BAĞLANDIĞIMUZDA KULLANACAĞIMIZ METOTLAR

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onBluetoothConnectingAsClientEvent(BluetoothConnectingAsClientEvent event) {
        currentState = RouterState.CONNECTING;
        timeHandler.removeCallbacksAndMessages(null);
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onBluetoothConnectedAsClientEvent(BluetoothConnectedAsClientEvent event) {
        currentState = RouterState.CONNECTED_AS_CLIENT;
        backoffMultiplier = 1;
        resetSyncFlags();

        List<String> myInventory = messageDao.getInventory();
        EventBus.getDefault().post(new TransmitInventoryCommandEvent(myInventory));
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onInventoryTransmittedAsClientEvent(InventoryTransmittedAsClientEvent event) {
        isLocalInvSent = true;
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onRemoteInventoryReceivedAsClientEvent(RemoteInventoryReceivedAsClientEvent event) {
        isRemoteInvReceived = true;
        currentRemoteInventory = event.remoteInventory;

        if (isLocalInvSent) {
            List<AuraMessage> messagesToSend = messageDao.getMissingMessages(currentRemoteInventory);
            EventBus.getDefault().post(new TransmitMessagesCommandEvent(messagesToSend));
        }
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onMessagesTransmittedAsClientEvent(MessagesTransmittedAsClientEvent event) {
        isLocalMsgsSent = true;
        checkFinalizeSync();
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onRemoteMessagesReceivedAsClientEvent(MessagesReceivedAsClientEvent event) {
        isRemoteMsgsReceived = true;
        checkFinalizeSync();
    }

    // YARDIMICI METHODLAR
    private void writeReceivedMessagesToDatabase(List<AuraMessage> receivedMessages) {
        if (!receivedMessages.isEmpty()) {
            messageDao.insertAll(receivedMessages);
            EventBus.getDefault().post(new NewMessagesSavedToDatabaseEvent(receivedMessages));
        }
    }

    private void checkFinalizeSync() {
        if (isLocalMsgsSent && isRemoteMsgsReceived && isLocalInvSent && isRemoteInvReceived) {
            EventBus.getDefault().post(new DisconnectGracefulCommandEvent());
        }
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onDisconnectedGraceful(BluetoothDisconnectedGracefulEvent event) {
        writeReceivedMessagesToDatabase(event.remoteMessages);
        resetSyncFlags();
        dropToIdleAndListen();
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onDisconnectedUngraceful(BluetoothDisconnectedUngracefulEvent event) {
        currentState = RouterState.IDLE;
        resetSyncFlags();
        dropToIdleAndListen();
    }

    //todo: ileride partial sync olacak
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onConnectionFailed(BluetoothConnectionFailedEvent event) {
        currentState = RouterState.BACKOFF;
        int baseDelayMs = 50;
        int backoffTime = baseDelayMs * backoffMultiplier;
        backoffMultiplier = Math.min(backoffMultiplier * 2, 8);

        timeHandler.postDelayed(this::dropToIdleAndListen, backoffTime);
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onUserSendMessageEvent(UserSendMessageEvent event) {
        messageDao.insert(event.auraMessage);
        EventBus.getDefault().post(new NewMessagesSavedToDatabaseEvent(List.of(event.auraMessage)));
        if (currentState == RouterState.IDLE) checkPendingNeighborsAndAct();
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onNeighborUpdated(NeighborUpdatedEvent event) {
        if (currentState == RouterState.IDLE) checkPendingNeighborsAndAct();
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onMyNodeIdUpdated(MyNodeIdUpdatedEvent event) {
        if (event.myNodeId != null && !event.myNodeId.equals("000000000000")){
            dropToIdleAndListen();
        }
    }

    private synchronized void checkPendingNeighborsAndAct() {
        if (currentState != RouterState.IDLE) return;

        String myNodeId = BleAdvertiseRegister.getInstance().getMyNodeId();
        String myHash = BleAdvertiseRegister.getInstance().getMessageHash();
        int myMessageCount = BleAdvertiseRegister.getInstance().getMessageCount();

        if (myNodeId == null || myNodeId.equals("000000000000")) return;

        Map<String, NeighborDevice> currentNeighbors = NeighborRegister.getInstance().getNeighborMap();
        if (currentNeighbors.isEmpty()) return;

        //todo: rastgele bir şekilde kontrol et sırayla değil

        for (Map.Entry<String, NeighborDevice> entry : currentNeighbors.entrySet()) {
            String targetNodeId = entry.getKey();
            String targetHash = entry.getValue().messageHash;
            int targetMessageCount = entry.getValue().messageCount;

            if (!myHash.equals(targetHash) || myMessageCount != targetMessageCount) {
                if (RouterUtil.amITheInitiator(myNodeId, targetNodeId)) {
                    initiateConnectionWithJitter(entry.getValue());
                    return;
                }
            }
        }
    }

    private void initiateConnectionWithJitter(NeighborDevice targetDevice) {
        currentState = RouterState.WAITING_JITTER;

        //iki cihaz aynı anda aynı cihaza bağlanmaya çalışırsa diye jitter ekledim
        int jitterMs = random.nextInt(100) + 100;

        timeHandler.postDelayed(() -> {
            if (currentState == RouterState.WAITING_JITTER) {
                currentState = RouterState.CONNECTING;
                EventBus.getDefault().post(new ConnectWithBluetoothCommandEvent(targetDevice));
            }
        }, jitterMs);
    }

    public void onDestroy() {
        EventBus.getDefault().unregister(this);
        timeHandler.removeCallbacksAndMessages(null);
    }
}