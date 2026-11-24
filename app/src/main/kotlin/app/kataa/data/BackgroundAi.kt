package app.kataa.data

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import app.kataa.KataaApplication
import app.kataa.ui.Look

/**
 * Background AI work that nobody waits for: the day summaries of the journal. Runs only while the phone is
 * charging and idle (Android decides when), at most every few hours, and only if Smart mode is on.
 */
class BackgroundAi : JobService() {
    @Volatile private var stopped = false

    override fun onStartJob(params: JobParameters?): Boolean {
        val app = application as KataaApplication
        if (!Look.smartMode(this)) return false
        stopped = false
        Thread {
            runCatching { app.ensureMind() }
            // Wait for the AI to load (it may have been unloaded to save memory), up to a minute, off the core thread.
            var waited = 0
            while (!stopped && app.mind.status == MindProvider.Status.LOADING && waited < 60_000) { Thread.sleep(1000); waited += 1000 }
            if (stopped || app.mind.status != MindProvider.Status.READY) { jobFinished(params, false); return@Thread }
            app.cores.worker.execute {
                runCatching { if (!stopped) app.cores.core.assistant.summarizeJournals() }
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean { stopped = true; return true }

    companion object {
        private const val JOB_ID = 4201

        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, BackgroundAi::class.java))
                    .setRequiresCharging(true)
                    .setRequiresDeviceIdle(true)
                    .setPeriodic(6 * 60 * 60 * 1000L)
                    .setPersisted(true)
                    .build(),
            )
        }
    }
}
