package works.gauss.grokbotalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        if (!validSecret(prefs.secret)) return
        if (!Permissions.notificationsGranted(context)) return
        try {
            ListenService.start(context)
        } catch (e: Exception) {
            Log.w("GrokbotAlarm", "boot start", e)
        }
    }
}
