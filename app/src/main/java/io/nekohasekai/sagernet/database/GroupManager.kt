package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.ktx.applyDefaultValues

object GroupManager {

    interface Listener {
        suspend fun groupAdd(group: ProxyGroup)
        suspend fun groupUpdated(group: ProxyGroup)

        suspend fun groupRemoved(groupId: Long)
        suspend fun groupUpdated(groupId: Long)

        suspend fun subscriptionAdd(entity: SubscriptionEntity) {}
        suspend fun subscriptionUpdated(entity: SubscriptionEntity) {}
        suspend fun subscriptionRemoved(entity: SubscriptionEntity) {}
    }

    interface Interface {
        suspend fun confirm(message: String): Boolean
        suspend fun alert(message: String)
        suspend fun onUpdateSuccess(
            group: ProxyGroup,
            changed: Int,
            added: List<String>,
            updated: Map<String, String>,
            deleted: List<String>,
            duplicate: List<String>,
            byUser: Boolean
        )

        suspend fun onUpdateFailure(group: ProxyGroup, message: String)
    }

    private val listeners = ArrayList<Listener>()
    var userInterface: Interface? = null

    suspend fun iterator(what: suspend Listener.() -> Unit) {
        synchronized(listeners) {
            listeners.toList()
        }.forEach { listener ->
            what(listener)
        }
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    suspend fun clearGroup(groupId: Long) {
        DataStore.selectedProxy = 0L
        SagerDatabase.proxyDao.deleteAll(groupId)
        iterator { groupUpdated(groupId) }
    }

    fun rearrange(groupId: Long) {
        val entities = SagerDatabase.proxyDao.getByGroup(groupId)
        for (index in entities.indices) {
            entities[index].userOrder = (index + 1).toLong()
        }
        SagerDatabase.proxyDao.updateProxy(entities)
    }

    suspend fun postUpdate(group: ProxyGroup) {
        iterator { groupUpdated(group) }
    }

    suspend fun postUpdate(groupId: Long) {
        postUpdate(SagerDatabase.groupDao.getById(groupId) ?: return)
    }

    suspend fun postReload(groupId: Long) {
        iterator { groupUpdated(groupId) }
    }

    suspend fun createGroup(group: ProxyGroup): ProxyGroup {
        group.userOrder = SagerDatabase.groupDao.nextOrder() ?: 1
        group.id = SagerDatabase.groupDao.createGroup(group.applyDefaultValues())
        iterator { groupAdd(group) }
        if (group.type == GroupType.SUBSCRIPTION) {
            SubscriptionUpdater.reconfigureUpdater()
        }
        return group
    }

    suspend fun updateGroup(group: ProxyGroup) {
        SagerDatabase.groupDao.updateGroup(group)
        iterator { groupUpdated(group) }
        if (group.type == GroupType.SUBSCRIPTION) {
            SubscriptionUpdater.reconfigureUpdater()
        }
    }

    suspend fun deleteGroup(groupId: Long) {
        SagerDatabase.subscriptionDao.deleteByGroup(groupId)
        SagerDatabase.groupDao.deleteById(groupId)
        SagerDatabase.proxyDao.deleteByGroup(groupId)
        iterator { groupRemoved(groupId) }
        SubscriptionUpdater.reconfigureUpdater()
    }

    suspend fun deleteGroup(group: List<ProxyGroup>) {
        SagerDatabase.subscriptionDao.deleteByGroup(group.map { it.id }.toLongArray())
        SagerDatabase.groupDao.deleteGroup(group)
        SagerDatabase.proxyDao.deleteByGroup(group.map { it.id }.toLongArray())
        for (proxyGroup in group) iterator { groupRemoved(proxyGroup.id) }
        SubscriptionUpdater.reconfigureUpdater()
    }

    suspend fun createSubscription(entity: SubscriptionEntity): SubscriptionEntity {
        entity.userOrder = SagerDatabase.subscriptionDao.nextOrder() ?: 1
        entity.bean?.applyDefaultValues()
        entity.id = SagerDatabase.subscriptionDao.create(entity)
        iterator { subscriptionAdd(entity) }
        SubscriptionUpdater.reconfigureUpdater()
        return entity
    }

    suspend fun updateSubscription(entity: SubscriptionEntity) {
        entity.bean?.applyDefaultValues()
        SagerDatabase.subscriptionDao.update(entity)
        iterator { subscriptionUpdated(entity) }
        SubscriptionUpdater.reconfigureUpdater()
    }

    suspend fun deleteSubscription(entity: SubscriptionEntity) {
        SagerDatabase.subscriptionDao.delete(entity)
        SagerDatabase.proxyDao.deleteBySubscription(entity.id)
        iterator { subscriptionRemoved(entity) }
        SubscriptionUpdater.reconfigureUpdater()
    }

    /**
     * 把 source 分组合并进 target 分组：
     * 1. source 上挂的订阅实体全部改指向 target，其节点随之移动；
     * 2. source 自己名下的节点（subscriptionId==0）：
     *    - 若 source 是旧式订阅分组，则为其创建订阅实体，节点挂到该实体下（保留后续可更新）；
     *    - 否则作为手动节点直接移入 target；
     * 3. 删除 source 分组。
     */
    suspend fun mergeGroup(sourceId: Long, targetId: Long) {
        if (sourceId == targetId) return
        val source = SagerDatabase.groupDao.getById(sourceId) ?: return

        for (entity in SagerDatabase.subscriptionDao.getByGroup(sourceId)) {
            val nodes = SagerDatabase.proxyDao.getByGroupAndSubscription(sourceId, entity.id)
            entity.groupId = targetId
            SagerDatabase.subscriptionDao.update(entity)
            for (node in nodes) {
                node.groupId = targetId
                node.userOrder = SagerDatabase.proxyDao.nextOrder(targetId) ?: 1
                SagerDatabase.proxyDao.updateProxy(node)
            }
        }

        val owned = SagerDatabase.proxyDao.getByGroup(sourceId).filter { it.subscriptionId == 0L }
        if (owned.isNotEmpty()) {
            var ownerSubscriptionId = 0L
            if (source.type == GroupType.SUBSCRIPTION && source.subscription != null) {
                ownerSubscriptionId = createSubscription(
                    SubscriptionEntity(name = source.name, groupId = targetId, bean = source.subscription)
                ).id
            }
            for (node in owned) {
                node.groupId = targetId
                node.subscriptionId = ownerSubscriptionId
                node.userOrder = SagerDatabase.proxyDao.nextOrder(targetId) ?: 1
                SagerDatabase.proxyDao.updateProxy(node)
            }
        }

        deleteGroup(sourceId)
        postUpdate(targetId)
    }

}