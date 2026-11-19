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
        /**
         * Sincronizacao sob demanda ou em primeiro plano.
         * Executa fora da UI e sempre informa o resultado na thread principal.
         * O HubSyncEngine serializa as chamadas simultaneas com o JobScheduler.
         */
        fun nowResult(ctx: Context, done: (HubSyncEngine.Result?, String?) -> Unit) {
            Thread {
                val result = try {
                    val r = HubSyncEngine(ctx.applicationContext).run()
                    ctx.getSharedPreferences("sync_settings",Context.MODE_PRIVATE).edit()
                        .putString("hub_last_status", "OK: " + r.sent + " enviados, " +
                            r.received + " recebidos, " + r.attachments +
                            " anexos, " + r.conflicts + " conflitos")
                        .putLong("hub_last_success", System.currentTimeMillis()).apply()
                    Pair(r, null)
                } catch (e: Exception) {
                    val error = (e.message ?: "conexao indisponivel").take(180)
                    ctx.getSharedPreferences("sync_settings",Context.MODE_PRIVATE).edit()
                        .putString("hub_last_status", "Falha: " + error).apply()
                    Pair(null, error)
                }
                Handler(Looper.getMainLooper()).post { done(result.first, result.second) }
            }.start()
        }

        fun now(ctx: Context, done: (String)->Unit) {
            nowResult(ctx) { result, error ->
                if (result != null) done("Sincronizacao: " + result.sent +
                    " enviados, " + result.received + " recebidos, " +
                    result.attachments + " anexos; conflitos: " + result.conflicts)
                else done("Falha na sincronizacao: " + (error ?: "sem conexao"))
            }
        }
    }
}
