package com.example.carriex.notifications

import android.Manifest
import android.app.*
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.carriex.localization.AppLanguage
import com.example.carriex.localization.LocalizedFormat
import com.example.carriex.R
import com.example.carriex.app.MainActivity
import com.example.carriex.auth.AndroidDeviceIdentity
import com.example.carriex.auth.DeviceAuthentication
import com.example.carriex.servers.PairedServer
import com.example.carriex.servers.ServerListStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object NotificationPolling {
    const val EXTRA_SERVER = "notification_server"
    const val EXTRA_REQUESTS = "notification_message_requests"
    const val ACTION_STOP = "com.example.carriex.STOP_NOTIFICATION_CHECKS"
    const val JOB_ID = 0x434152
    const val SERVICE_ID = 100
    const val MESSAGES = "pending_messages"
    const val POLLING = "count_polling"
    @Volatile var appVisible = false
    fun preferences(context: Context) = context.getSharedPreferences("chat_settings", Context.MODE_PRIVATE)
    fun state(context: Context) = context.getSharedPreferences("notification_state", Context.MODE_PRIVATE)
    fun enabled(context: Context) = preferences(context).getBoolean("notifications_enabled", false)
    fun interval(context: Context) = preferences(context).getLong("notification_interval", PollInterval.DEFAULT).coerceAtLeast(1000)
    fun permitted(context: Context) = (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    fun servers(context: Context): List<PairedServer> {
        val prefs=preferences(context)
        return ServerListStore({prefs.getString("paired_servers_v1",null)},{false}).list()
            .filter { prefs.getBoolean("notify_${it.fingerprint}",true) }.distinctBy { it.fingerprint }
    }
    fun channels(baseContext: Context) {
        val context = AppLanguage.context(baseContext)
        if(Build.VERSION.SDK_INT >= 26) context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel(MESSAGES,context.getString(R.string.ui_pending_messages),NotificationManager.IMPORTANCE_DEFAULT).apply {
                description=context.getString(R.string.ui_counts_only_messages_are_downloaded_when_carriex_is_opened)
                lockscreenVisibility=Notification.VISIBILITY_PRIVATE
            },
            NotificationChannel(POLLING,context.getString(R.string.ui_custom_polling_status),NotificationManager.IMPORTANCE_LOW).apply {
                description=context.getString(R.string.ui_shown_while_a_custom_interval_below_15_minutes_is_running)
                setShowBadge(false)
            },
            NotificationChannel(MessageRequestNotifications.CHANNEL,context.getString(R.string.ui_requests_and_invitations),NotificationManager.IMPORTANCE_HIGH).apply {
                description=context.getString(R.string.ui_message_requests_and_group_invitations_including_while_carriex_is_open)
                lockscreenVisibility=Notification.VISIBILITY_PRIVATE
            }))
    }
    fun openIntent(context: Context, server: PairedServer? = null, messageRequests: Boolean = false): PendingIntent = PendingIntent.getActivity(context,0,
        Intent(context,MainActivity::class.java).apply {
            flags=Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data=Uri.parse("carriex://notifications/${server?.fingerprint ?: "settings"}${if(messageRequests) "/requests" else ""}")
            server?.let { putExtra(EXTRA_SERVER,it.url) }
            if(messageRequests) putExtra(EXTRA_REQUESTS,true)
        },PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Called from visible UI/boot. Do not reschedule an unchanged periodic job on every launch. */
    fun schedule(context: Context) {
        try {
            scheduleWork(context)
            state(context).edit().remove("scheduler_error").apply()
        } catch(e: Exception) {
            state(context).edit().putString("scheduler_error",e.message?.take(160) ?: context.getString(R.string.ui_open_carriex_to_resume_checks)).apply()
            throw e
        }
    }
    private fun scheduleWork(context: Context) {
        channels(context)
        val scheduler=context.getSystemService(JobScheduler::class.java)
        val allowed=enabled(context) && permitted(context) && runCatching { servers(context).isNotEmpty() }.getOrDefault(false)
        val period=interval(context)
        if(!allowed) {
            scheduler.cancel(JOB_ID);context.stopService(Intent(context,FastCountService::class.java))
            NotificationManagerCompat.from(context).cancel(SERVICE_ID)
            clearAlerts(context)
        } else if(period >= JobInfo.getMinPeriodMillis()) {
            context.stopService(Intent(context,FastCountService::class.java))
            val existing=scheduler.getPendingJob(JOB_ID)
            if(existing == null || existing.intervalMillis != period) {
                val job=JobInfo.Builder(JOB_ID,ComponentName(context,CountJobService::class.java))
                    .setPeriodic(period,maxOf(JobInfo.getMinFlexMillis(),period/20))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                    .setBackoffCriteria(60_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
                check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) { context.getString(R.string.ui_android_could_not_schedule_notification_checks) }
            }
        } else {
            scheduler.cancel(JOB_ID)
            ContextCompat.startForegroundService(context,Intent(context,FastCountService::class.java))
        }
    }
    fun clearAlerts(context: Context) {
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .filter { it.id == 1 }.forEach { NotificationManagerCompat.from(context).cancel(it.tag,1) }
    }
}

/** One shared worker, reusable TLS connections, persisted count-only auth and outage backoff. */
object CountPoller {
    private val busy=AtomicBoolean(false)
    private data class Connection(val client: okhttp3.OkHttpClient,val transport: CountTransport)
    private val connections=mutableMapOf<String,Connection>()
    @Synchronized fun cancel() { connections.values.forEach { it.client.dispatcher.cancelAll() } }
    @Synchronized private fun connection(context: Context,server: PairedServer): Connection = connections.getOrPut("${server.fingerprint}:${server.url}") {
        val identity=AndroidDeviceIdentity.load()
        val client=server.client().newBuilder().pingInterval(0,TimeUnit.SECONDS)
            .connectTimeout(8,TimeUnit.SECONDS).readTimeout(8,TimeUnit.SECONDS).callTimeout(12,TimeUnit.SECONDS).build()
        val credentials=CountCredentialStore(context,"${server.fingerprint}:${identity.userId}")
        Connection(client,CountTransport(server.url,DeviceAuthentication(server.url,identity,client),client,credentials::read,credentials::write))
    }
    fun poll(baseContext: Context, force: Boolean = false): Boolean {
        val context = AppLanguage.context(baseContext)
        if(!NotificationPolling.enabled(context) || !NotificationPolling.permitted(context) ||
            (!force && NotificationPolling.appVisible) || !busy.compareAndSet(false,true)) return false
        try {
            if(context.getSystemService(ConnectivityManager::class.java).activeNetwork == null) return true
            val prefs=NotificationPolling.state(context)
            val period=NotificationPolling.interval(context)
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(50)
            val servers=NotificationPolling.servers(context)
            val start=if(servers.isEmpty()) 0 else prefs.getInt("cursor",0).mod(servers.size)
            val ordered=servers.drop(start)+servers.take(start)
            ordered.forEachIndexed { offset,server ->
                if(Thread.currentThread().isInterrupted || System.nanoTime() >= deadline || !NotificationPolling.enabled(context) ||
                    (!force && NotificationPolling.appVisible)) return@forEachIndexed
                val scope=server.fingerprint
                if(!force && System.currentTimeMillis() < prefs.getLong("next_$scope",0)) return@forEachIndexed
                prefs.edit().putInt("cursor",(start+offset+1).mod(servers.size)).apply()
                try {
                    val count=connection(context.applicationContext,server).transport.fetch()
                    if(!NotificationPolling.enabled(context)) return@forEachIndexed
                    val previous=prefs.getLong("revision_$scope",-1)
                    val manager=NotificationManagerCompat.from(context)
                    if(count.count == 0 || NotificationPolling.appVisible) manager.cancel(scope,1)
                    else {
                        val fresh=count.revision > previous
                        val active=context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.tag == scope && it.id == 1 }
                        if(fresh || (active && prefs.getInt("count_$scope",0) != count.count)) {
                            val notification=NotificationCompat.Builder(context,NotificationPolling.MESSAGES)
                                .setSmallIcon(R.drawable.ic_stat_message).setContentTitle(server.name)
                                .setContentText(context.resources.getQuantityString(R.plurals.pending_message_notification, count.count, count.count))
                                .setNumber(count.count).setAutoCancel(true).setOnlyAlertOnce(!fresh)
                                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                                .setContentIntent(NotificationPolling.openIntent(context,server)).build()
                            try {
                                if(NotificationPolling.permitted(context)) manager.notify(scope,1,notification)
                            } catch (_: SecurityException) { return@forEachIndexed }
                        }
                    }
                    prefs.edit().putLong("revision_$scope",maxOf(previous,count.revision)).putInt("count_$scope",count.count)
                        .putLong("checked_$scope",System.currentTimeMillis()).putLong("next_$scope",0)
                        .putInt("failures_$scope",0).remove("error_$scope").commit()
                } catch(e:Exception) {
                    if(Thread.currentThread().isInterrupted || !NotificationPolling.enabled(context) ||
                        (!force && NotificationPolling.appVisible)) return@forEachIndexed
                    val failures=(prefs.getInt("failures_$scope",0)+1).coerceAtMost(10)
                    val delay=maxOf(period,minOf(3_600_000L,period.coerceAtMost(3_600_000L) * (1L shl failures)))
                    val next=System.currentTimeMillis().let { if(Long.MAX_VALUE-it < delay) Long.MAX_VALUE else it+delay }
                    prefs.edit().putInt("failures_$scope",failures).putLong("next_$scope",next)
                        .putString("error_$scope",e.message?.take(160) ?: context.getString(R.string.ui_count_check_failed)).commit()
                }
            }
            return false
        } finally { busy.set(false) }
    }
}

class CountJobService : JobService() {
    private val executor=Executors.newSingleThreadExecutor()
    private var task: java.util.concurrent.Future<*>?=null
    private val generation=java.util.concurrent.atomic.AtomicInteger()
    override fun onStartJob(params: JobParameters): Boolean {
        val attempt=generation.incrementAndGet()
        task=executor.submit {
            val retry=runCatching { CountPoller.poll(applicationContext) }.getOrDefault(true)
            android.os.Handler(mainLooper).post { if(generation.get()==attempt) jobFinished(params,retry) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { generation.incrementAndGet();task?.cancel(true);CountPoller.cancel();return NotificationPolling.enabled(this) }
    override fun onDestroy() { generation.incrementAndGet();task?.cancel(true);executor.shutdownNow();super.onDestroy() }
}

class FastCountService : Service() {
    private val executor=Executors.newSingleThreadScheduledExecutor()
    private var task: java.util.concurrent.ScheduledFuture<*>?=null
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        val context = AppLanguage.context(this)
        NotificationPolling.channels(context)
        val status=NotificationCompat.Builder(this,NotificationPolling.POLLING).setSmallIcon(R.drawable.ic_stat_message)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentTitle(context.getString(R.string.ui_carriex_notification_checks))
            .setContentText(context.getString(R.string.ui_every_counts_only, LocalizedFormat.interval(context, NotificationPolling.interval(this))))
            .setOngoing(true).setContentIntent(NotificationPolling.openIntent(this))
            .addAction(0,context.getString(R.string.ui_stop_checks),PendingIntent.getBroadcast(this,0,
                Intent(this,NotificationBootReceiver::class.java).setAction(NotificationPolling.ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
        if(Build.VERSION.SDK_INT>=34) startForeground(NotificationPolling.SERVICE_ID,status,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NotificationPolling.SERVICE_ID,status)
        if(!NotificationPolling.enabled(this) || NotificationPolling.interval(this)>=JobInfo.getMinPeriodMillis() || !NotificationPolling.permitted(this)) {
            stopSelf();return START_NOT_STICKY
        }
        task?.cancel(false)
        task=executor.scheduleWithFixedDelay({ runCatching { CountPoller.poll(applicationContext) } },0,NotificationPolling.interval(this),TimeUnit.MILLISECONDS)
        return START_STICKY
    }
    override fun onDestroy() { task?.cancel(true);CountPoller.cancel();executor.shutdownNow();super.onDestroy() }
}

class NotificationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action == NotificationPolling.ACTION_STOP) {
            NotificationPolling.preferences(context).edit().putBoolean("notifications_enabled",false).apply()
            NotificationPolling.schedule(context)
            return
        }
        if(intent.action in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED)) {
            runCatching { NotificationPolling.schedule(context) }.onFailure {
                NotificationPolling.state(context).edit().putString("scheduler_error",context.getString(R.string.ui_open_carriex_to_resume_notification_checks)).apply()
            }
        }
    }
}
