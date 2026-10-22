package com.example.tachoansage.iss

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Wird vom Wecker (AlarmManager) kurz vor einem Überflug ausgelöst und zeigt die Benachrichtigung. */
class IssAlarmReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_ID = "id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!IssPrefs.enabled(context)) return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
        IssScheduler.showNotification(context, intent.getIntExtra(EXTRA_ID, 1000), title, text)
    }
}

/**
 * Berechnet die Überflüge neu: regelmäßig, nach einem Neustart des Handys
 * und nach einem App-Update (dabei gehen geplante Wecker verloren).
 */
class IssRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != IssScheduler.ACTION_REFRESH &&
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        if (!IssPrefs.enabled(context)) return

        val appContext = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                IssScheduler.refresh(appContext, allowNetwork = true)
                if (action != IssScheduler.ACTION_REFRESH) IssScheduler.schedulePeriodicRefresh(appContext)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
