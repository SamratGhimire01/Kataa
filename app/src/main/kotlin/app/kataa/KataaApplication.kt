package app.kataa

import android.app.Application
import app.kataa.data.CoreProvider

class KataaApplication : Application() {
    lateinit var cores: CoreProvider
        private set

    override fun onCreate() {
        super.onCreate()
        cores = CoreProvider(this)
        // Warm up (open database, load model) in the background so the first question is instant.
        cores.worker.execute { runCatching { cores.core.processPending() } }
    }
}
