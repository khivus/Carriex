package com.example.carriex.notifications

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.carriex.localization.AppLanguage
import com.example.carriex.localization.LocalizedFormat
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

    fun invitationsEnabled(context: Context) = NotificationPolling.preferences(context).getBoolean("group_invitation_alerts", true)

    fun show(baseContext: Context, server: PairedServer, count: Int) {
        val context = AppLanguage.context(baseContext)
        if (count == 0) { clear(context, server.fingerprint); return }
        if (!enabled(context) || !NotificationPolling.permitted(context)) return
        NotificationPolling.channels(context)
        val text = context.resources.getQuantityString(R.plurals.pending_request_notification, count, count, server.name)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(context.getString(R.string.ui_someone_is_trying_to_contact_you))
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setNumber(count).setAutoCancel(true)
            .setContentIntent(NotificationPolling.openIntent(context, server, messageRequests = true)).build()
        try { NotificationManagerCompat.from(context).notify(tag(server.fingerprint), ID, notification) }
        catch (_: SecurityException) { /* Permission can be revoked between the check and posting. */ }
    }

    fun clear(context: Context, scope: String) = NotificationManagerCompat.from(context).cancel(tag(scope), ID)
    fun groupInvitation(baseContext: Context, server: PairedServer, group: GroupState, ownId: String) {
        val context = AppLanguage.context(baseContext)
        val state = NotificationPolling.state(context)
        val marker = "group_invite_seen_${server.fingerprint}_${group.id}"
        val tag = "group-invite:${server.fingerprint}:${group.id}"
        if(ownId !in group.invited) {
            if(state.contains(marker)) {
                NotificationManagerCompat.from(context).cancel(tag, GROUP_INVITE_ID)
                state.edit().remove(marker).apply()
            }
            return
        }
        if((state.all[marker] as? Long) == group.revision.toLong() || !invitationsEnabled(context) || !NotificationPolling.permitted(context)) return
        NotificationPolling.channels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_group)
            .setContentTitle(context.getString(R.string.ui_you_have_a_group_invitation))
            .setContentText(context.getString(R.string.ui_on_tap_to_review_and_join, group.name, server.name))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true).setContentIntent(NotificationPolling.openIntent(context, server, messageRequests = true)).build()
        try {
            NotificationManagerCompat.from(context).notify(tag, GROUP_INVITE_ID, notification)
            state.edit().putLong(marker, group.revision.toLong()).apply()
        } catch (_: SecurityException) { }
    }
    fun clearInvitations(context: Context, scope: String) {
        context.getSystemService(android.app.NotificationManager::class.java).activeNotifications
            .filter { it.id == GROUP_INVITE_ID && it.tag?.startsWith("group-invite:$scope:") == true }
            .forEach { NotificationManagerCompat.from(context).cancel(it.tag, GROUP_INVITE_ID) }
        val state = NotificationPolling.state(context)
        state.edit().also { edit -> state.all.keys.filter { it.startsWith("group_invite_seen_${scope}_") }.forEach(edit::remove) }.apply()
    }
    fun dismissInvitations(context: Context, scope: String) {
        context.getSystemService(android.app.NotificationManager::class.java).activeNotifications
            .filter { it.id == GROUP_INVITE_ID && it.tag?.startsWith("group-invite:$scope:") == true }
            .forEach { NotificationManagerCompat.from(context).cancel(it.tag, it.id) }
    }
    fun clearMessages(context: Context) {
        context.getSystemService(android.app.NotificationManager::class.java).activeNotifications
            .filter { it.id == ID && it.tag?.startsWith("requests:") == true }
            .forEach { NotificationManagerCompat.from(context).cancel(it.tag, it.id) }
    }
    fun clearGroupAlerts(context: Context) {
        context.getSystemService(android.app.NotificationManager::class.java).activeNotifications
            .filter { it.id == GROUP_INVITE_ID && it.tag?.startsWith("group-invite:") == true }
            .forEach { NotificationManagerCompat.from(context).cancel(it.tag, it.id) }
        val state = NotificationPolling.state(context)
        state.edit().also { edit -> state.all.keys.filter { it.startsWith("group_invite_seen_") }.forEach(edit::remove) }.apply()
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
