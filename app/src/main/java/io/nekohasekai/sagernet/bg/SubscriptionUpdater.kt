package io.nekohasekai.sagernet.bg

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import java.util.concurrent.TimeUnit

object SubscriptionUpdater {

    private const val WORK_NAME = "SubscriptionUpdater"

    suspend fun reconfigureUpdater() {
        RemoteWorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)

        val legacySubscriptions = SagerDatabase.groupDao.subscriptions()
            .filter { it.subscription!!.autoUpdate }
        val entitySubscriptions = SagerDatabase.subscriptionDao.all()
            .filter { it.bean?.autoUpdate == true }
        if (legacySubscriptions.isEmpty() && entitySubscriptions.isEmpty()) return

        // PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS
        var minDelay = legacySubscriptions.minOfOrNull { it.subscription!!.autoUpdateDelay.toLong() }
            ?: entitySubscriptions.minOfOrNull { it.bean!!.autoUpdateDelay.toLong() }
            ?: 1440L
        if (minDelay < 15) minDelay = 15
        val now = System.currentTimeMillis() / 1000L
        var minInitDelay = legacySubscriptions.minOfOrNull {
            now - it.subscription!!.lastUpdated - (minDelay * 60)
        } ?: Long.MAX_VALUE
        entitySubscriptions.minOfOrNull {
            now - it.bean!!.lastUpdated - (minDelay * 60)
        }?.let { minInitDelay = minOf(minInitDelay, it) }
        if (minInitDelay == Long.MAX_VALUE) minInitDelay = 0
        if (minInitDelay < 0) minInitDelay = 0
        if (minInitDelay > 60) minInitDelay = 60

        // main process
        RemoteWorkManager.getInstance(app).enqueueUniquePeriodicWork(
            WORK_NAME,
            UPDATE,
            PeriodicWorkRequest.Builder(UpdateTask::class.java, minDelay, TimeUnit.MINUTES)
                .apply {
                    if (minInitDelay > 0) setInitialDelay(minInitDelay, TimeUnit.SECONDS)
                }
                .build()
        )
    }

    class UpdateTask(
        appContext: Context, params: WorkerParameters
    ) : CoroutineWorker(appContext, params) {

        val nm = NotificationManagerCompat.from(applicationContext)

        val notification = NotificationCompat.Builder(applicationContext, "service-subscription")
            .setWhen(0)
            .setTicker(applicationContext.getString(R.string.forward_success))
            .setContentTitle(applicationContext.getString(R.string.subscription_update))
            .setSmallIcon(R.drawable.ic_service_active)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        override suspend fun doWork(): Result {
            var legacySubscriptions =
                SagerDatabase.groupDao.subscriptions().filter { it.subscription!!.autoUpdate }
            var entitySubscriptions =
                SagerDatabase.subscriptionDao.all().filter { it.bean?.autoUpdate == true }
            if (!DataStore.serviceState.connected) {
                Logs.d("work: not connected")
                legacySubscriptions = legacySubscriptions.filter { !it.subscription!!.updateWhenConnectedOnly }
                entitySubscriptions = entitySubscriptions.filter { !it.bean!!.updateWhenConnectedOnly }
            }

            if (legacySubscriptions.isNotEmpty()) for (profile in legacySubscriptions) {
                val subscription = profile.subscription!!

                if (((System.currentTimeMillis() / 1000).toInt() - subscription.lastUpdated) < subscription.autoUpdateDelay * 60) {
                    Logs.d("work: not updating " + profile.displayName())
                    continue
                }
                Logs.d("work: updating " + profile.displayName())

                notification.setContentText(
                    applicationContext.getString(
                        R.string.subscription_update_message, profile.displayName()
                    )
                )
                nm.notify(2, notification.build())

                GroupUpdater.executeUpdate(profile, false)
            }

            if (entitySubscriptions.isNotEmpty()) for (entity in entitySubscriptions) {
                val bean = entity.bean!!

                if (((System.currentTimeMillis() / 1000).toInt() - bean.lastUpdated) < bean.autoUpdateDelay * 60) {
                    Logs.d("work: not updating " + entity.displayName())
                    continue
                }
                Logs.d("work: updating " + entity.displayName())

                notification.setContentText(
                    applicationContext.getString(
                        R.string.subscription_update_message, entity.displayName()
                    )
                )
                nm.notify(2, notification.build())

                GroupUpdater.executeUpdate(entity, false)
            }

            nm.cancel(2)

            return Result.success()
        }
    }

}