package io.nekohasekai.sagernet.database

import androidx.room.*

/**
 * 额外订阅（合并到某个分组里的订阅）。
 * 每个订阅拥有独立的 id，节点通过 ProxyEntity.subscriptionId 关联到该订阅，
 * 这样更新单个订阅时只替换它自己的节点，不影响同组其他订阅/手动节点。
 */
@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) var id: Long = 0L,
    var userOrder: Long = 0L,
    var name: String? = null,
    @ColumnInfo(defaultValue = "0") var groupId: Long = 0L,
    var bean: SubscriptionBean? = null
) {

    fun displayName(): String {
        return name?.takeIf { it.isNotBlank() }
            ?: bean?.link?.takeIf { it.isNotBlank() }
            ?: "Subscription #$id"
    }

    @androidx.room.Dao
    interface Dao {

        @Query("SELECT * FROM subscriptions ORDER BY userOrder")
        fun all(): List<SubscriptionEntity>

        @Query("SELECT * FROM subscriptions WHERE groupId = :groupId ORDER BY userOrder")
        fun getByGroup(groupId: Long): List<SubscriptionEntity>

        @Query("SELECT * FROM subscriptions WHERE id = :id")
        fun getById(id: Long): SubscriptionEntity?

        @Query("SELECT COUNT(*) FROM subscriptions WHERE groupId = :groupId")
        fun countByGroup(groupId: Long): Long

        @Query("SELECT MAX(userOrder) + 1 FROM subscriptions")
        fun nextOrder(): Long?

        @Insert
        fun create(entity: SubscriptionEntity): Long

        @Update
        fun update(entity: SubscriptionEntity)

        @Delete
        fun delete(entity: SubscriptionEntity)

        @Query("DELETE FROM subscriptions WHERE groupId = :groupId")
        fun deleteByGroup(groupId: Long)

        @Query("DELETE FROM subscriptions WHERE groupId IN (:groupIds)")
        fun deleteByGroup(groupIds: LongArray)
    }
}
