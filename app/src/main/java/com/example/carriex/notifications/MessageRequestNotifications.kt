package com.example.carriex.notifications

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.carriex.R
import com.example.carriex.servers.PairedServer
import com.example.carriex.crypto.groups.GroupState

/** Alerts for locally saved contact requests, independent of background count polling. */
object MessageRequestNotifications {
    const val CHANNEL = "message_requests"
    const val ID = 2
    const val GROUP_INVITE_ID = 3
    private fun tag(scope: String) = "requests:$scope"
    fun enabled(context: Context) = NotificationPolling.preferences(context).getBoolean("message_request_alerts", true)

    fun show(context: Context, server: PairedServer, count: Int) {
        if (count == 0) { clear(context, server.fingerprint); return }
        if (!enabled(context) || !NotificationPolling.permitted(context)) return
        NotificationPolling.channels(context)
        val text = "$count pending ${if (count == 1) "message request" else "message requests"} on ${server.name}. Tap to review."
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle("Someone is trying to contact you")
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setNumber(count).setAutoCancel(true)
            .setContentIntent(NotificationPolling.openIntent(context, server, messageRequests = true)).build()
        try { NotificationManagerCompat.from(context).notify(tag(server.fingerprint), ID, notification) }
        catch (_: SecurityException) { /* Permission can be revoked between the check and posting. */ }
    }

    fun clear(context: Context, scope: String) = NotificationManagerCompat.from(context).cancel(tag(scope), ID)
    fun groupInvitation(context: Context, server: PairedServer, group: GroupState, ownId: String) {
        val state = NotificationPolling.state(context)
        val marker = "group_invite_seen_${server.fingerprint}_${group.id}"
        val tag = "group-invite:${server.fingerprint}:${group.id}"
        if(ownId !in group.invited) {
            if(state.getBoolean(marker, false)) {
                NotificationManagerCompat.from(context).cancel(tag, GROUP_INVITE_ID)
                state.edit().remove(marker).apply()
            }
            return
        }
        if(state.getBoolean(marker, false) || !enabled(context) || !NotificationPolling.permitted(context)) return
        NotificationPolling.channels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_group)
            .setContentTitle("You have a group invitation")
            .setContentText("${group.name} on ${server.name}. Tap to review and join.")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true).setContentIntent(NotificationPolling.openIntent(context, server)).build()
        try {
            NotificationManagerCompat.from(context).notify(tag, GROUP_INVITE_ID, notification)
            state.edit().putBoolean(marker, true).apply()
        } catch (_: SecurityException) { }
    }
    fun clearInvitations(context: Context, scope: String) {
        context.getSystemService(android.app.NotificationManager::class.java).activeNotifications
            .filter { it.id == GROUP_INVITE_ID && it.tag?.startsWith("group-invite:$scope:") == true }
            .forEach { NotificationManagerCompat.from(context).cancel(it.tag, GROUP_INVITE_ID) }
        val state = NotificationPolling.state(context)
        state.edit().also { edit -> state.all.keys.filter { it.startsWith("group_invite_seen_${scope}_") }.forEach(edit::remove) }.apply()
    }
    fun clearAll(context: Context) {
        context.getSystemService(android.app.NotificationManager::class.java).activeNotifications
            .filter { (it.id == ID && it.tag?.startsWith("requests:") == true) ||
                (it.id == GROUP_INVITE_ID && it.tag?.startsWith("group-invite:") == true) }
            .forEach { NotificationManagerCompat.from(context).cancel(it.tag, it.id) }
        val state = NotificationPolling.state(context)
        state.edit().also { edit -> state.all.keys.filter { it.startsWith("group_invite_seen_") }.forEach(edit::remove) }.apply()
    }
}
