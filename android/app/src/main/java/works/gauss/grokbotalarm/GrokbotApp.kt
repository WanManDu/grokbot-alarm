package works.gauss.grokbotalarm

import android.app.Application

class GrokbotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ListenService.createChannels(this)
        val prefs = Prefs(this)
        prefs.migrate()
        ListenState.reload(prefs)
    }
}
