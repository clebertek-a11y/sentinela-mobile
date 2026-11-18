package br.gov.rs.sapucaiadosul.sentinela

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Sincroniza SOMENTE atendimentos concluídos, por HTTPS.
 * Não considera envio confirmado sem resposta HTTP 200 + ACK verificável.
 * Servidor independente opcional descrito em backend/sentinela_gateway.py.
 */
class SyncJobService : JobService() {
    private val running = AtomicBoolean(false)
    override fun onStartJob(params: JobParameters): Boolean {
        if (!running.compareAndSet(false,true)) return false
        Thread {
            var retry = false
            try {
                val prefs = getSharedPreferences("sync_settings",Context.MODE_PRIVATE)
                val endpoint = prefs.getString("url", "")?.trim().orEmpty()
                val token = SecureSecrets.read(this).trim()
                if (!endpoint.startsWith("https://") || token.length < 16) {
                    // Sem configuração: armazenado somente no aparelho. Não sinalizar êxito.
                    return@Thread
                }
                val db = LocalStore(this)
                val pending = db.incidents().filter { it.status == "Atendida" && it.syncState != "SINCRONIZADO" }
                for (incident in pending) {
                    if (!syncOne(db,incident,endpoint,token)) {retry=true;break}
                }
            } catch (_: Exception) {retry=true}
            finally {running.set(false);jobFinished(params,retry)}
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { return true }

    private fun syncOne(db: LocalStore, o: Incident, endpoint: String, token: String): Boolean {
        val zip = FileTools.archive(this,o,db.report(o.id),db.photos(o.id),db.auditEvents(o.id))
        val hash = FileTools.sha256(zip)
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("Content-Type","application/zip")
            connection.setRequestProperty("Authorization","Bearer $token")
            connection.setRequestProperty("X-Sentinela-Id",o.id)
            connection.setRequestProperty("X-Sentinela-Number",o.number)
            connection.setRequestProperty("X-Sentinela-Date",o.date)
            connection.setRequestProperty("X-Sentinela-Category",o.category.replace("INTERDIÇÕES","INTERDICOES"))
            connection.setRequestProperty("X-Sentinela-Address-Base64",android.util.Base64.encodeToString(o.rua.toByteArray(Charsets.UTF_8),android.util.Base64.NO_WRAP))
            connection.setRequestProperty("X-Sentinela-Sha256",hash)
            connection.setFixedLengthStreamingMode(zip.length())
            connection.outputStream.use { output -> zip.inputStream().use { it.copyTo(output) } }
            if (connection.responseCode != 200) return false
            val ack = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (ack.optBoolean("stored") && ack.optString("incident_id") == o.id && ack.optString("sha256") == hash) {
                db.markSynced(o.id,"servico_sync")
                return true
            }
            return false
        } finally {connection.disconnect()}
    }

    companion object {
        private const val JOB_ID = 7101
        fun schedule(ctx: Context) {
            val job = JobInfo.Builder(JOB_ID,ComponentName(ctx,SyncJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true)
                .build()
            (ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).schedule(job)
        }
    }
}
