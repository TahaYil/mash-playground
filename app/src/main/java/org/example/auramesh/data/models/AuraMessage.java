package org.example.auramesh.data.models;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "auraMessages")
public class AuraMessage {
    public static final String TARGET_PUBLIC = "BROADCAST_ALL";
    @PrimaryKey
    @NonNull
    public String messageId;
    public String senderUuid;
    public String targetUuid;
    public String payload;
    public long timestamp;
    public long ttl;

    public AuraMessage(@NonNull String messageId, String senderUuid, String targetUuid, String payload, long timestamp, long ttl) {
        this.messageId = messageId;
        this.senderUuid = senderUuid;
        this.targetUuid = targetUuid;
        this.payload = payload;
        this.timestamp = timestamp;
        this.ttl = ttl;
    }
}