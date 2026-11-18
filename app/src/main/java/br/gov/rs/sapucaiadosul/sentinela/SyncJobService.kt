package br.gov.rs.sapucaiadosul.sentinela

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper

/** Tarefas Android: executa sincronizacao na rede, sem bloquear a interface. */
class SyncJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            var retry=false
            try {
                val outcome=HubSyncEngine(this).run()
                getSharedPreferences("sync_settings",Context.MODE_PRIVATE).edit()
                    .putString("hub_last_status", "OK: " + outcome.sent + " enviados, " +
                        outcome.received + " recebidos, " + outcome.attachments +
                        " anexos, " + outcome.conflicts + " conflitos")
                    .putLong("hub_last_success",System.currentTimeMillis()).apply()
            } catch (e: Exception) {
                retry=true
                getSharedPreferences("sync_settings",Context.MODE_PRIVATE).edit()
                    .putString("hub_last_status", "Falha: " + (e.message ?: "conexao indisponivel").take(180)).apply()
            } finally { jobFinished(params,retry) }
        }.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val IMMEDIATE = 7101
        private const val PERIODIC = 7102
        fun schedule(ctx: Context) {
            val scheduler=ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            scheduler.schedule(JobInfo.Builder(IMMEDIATE, ComponentName(ctx,SyncJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(1000L)
                .setBackoffCriteria(60_000L,JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build())
        }
        fun periodic(ctx: Context) {
            val scheduler=ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            scheduler.schedule(JobInfo.Builder(PERIODIC,ComponentName(ctx,SyncJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(15L*60*1000)
                .build())
        }
        fun now(ctx: Context, done: (String)->Unit) {
            Thread {
                val result=try {
                    val r=HubSyncEngine(ctx.applicationContext).run()
                    "Sincronizacao: " + r.sent + " enviados, " + r.received +
                        " recebidos, " + r.attachments + " anexos; conflitos: " + r.conflicts
                } catch (e:Exception) { "Falha na sincronizacao: " + (e.message ?: "sem conexao").take(160) }
                Handler(Looper.getMainLooper()).post { done(result) }
            }.start()
        }
    }
}
