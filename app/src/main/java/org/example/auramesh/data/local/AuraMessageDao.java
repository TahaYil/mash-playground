package org.example.auramesh.data.local;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import org.example.auramesh.data.models.AuraMessage;

import java.util.List;

@Dao
public interface AuraMessageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insert(AuraMessage message);

    @Query("SELECT * FROM auraMessages ORDER BY timestamp ASC")
    List<AuraMessage> getAllMessages();

    @Query("SELECT messageId FROM auraMessages")
    List<String> getInventory();

    @Query("SELECT * FROM auraMessages WHERE messageId NOT IN (:remoteMessageIds)")
    List<AuraMessage> getMissingMessages(List<String> remoteMessageIds);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertAll(List<AuraMessage> messages);

    @Query("SELECT * FROM auraMessages WHERE (timestamp + ttl) < :currentTimeInMillis AND targetUuid != 'BROADCAST_ALL'")
    List<AuraMessage> getExpiredMessages(long currentTimeInMillis);

    @Query("DELETE FROM auraMessages WHERE messageId IN (:messageIds)")
    void deleteMessagesByIds(List<String> messageIds);

    @Query("SELECT COUNT(*) FROM auraMessages WHERE messageId = :msgId")
    int checkMessageExists(String msgId);

    @Query("DELETE FROM auraMessages WHERE timestamp < :thresholdTime")
    void deleteOldMessages(long thresholdTime);
}