package br.gov.rs.sapucaiadosul.sentinela

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Canal em tempo real com o HUB 1.1: recebe apenas numero da revisao.
 * Os dados continuam sendo carregados pela sincronizacao versionada existente.
 * Se o HUB ainda for 1.0, o polling em foreground permanece funcionando.
 */
class HubLiveEvents(private val ctx: Context,private val changed: () -> Unit) {
    private val running=AtomicBoolean(false)
    @Volatile private var connection: HttpURLConnection?=null
    private var worker:Thread?=null

    fun start() {
        if (!running.compareAndSet(false,true)) return
        worker=Thread({
            val prefs=ctx.getSharedPreferences("sync_settings",Context.MODE_PRIVATE)
            val url=prefs.getString("url","").orEmpty().trimEnd('/')
            val username=prefs.getString("hub_user","").orEmpty()
            val pass=SecureSecrets.read(ctx)
            if(!url.startsWith("https://")||username.isBlank()||pass.isBlank()) {
                running.set(false);return@Thread
            }
            var lastSequence=-1L
            while(running.get()) {
                try {
                    val auth=(URL(url+"/api/v1/auth/login").openConnection() as HttpURLConnection)
                    auth.connectTimeout=10000;auth.readTimeout=15000
                    auth.requestMethod="POST";auth.doOutput=true
                    auth.setRequestProperty("Content-Type","application/json")
                    auth.outputStream.use{
                        val json=JSONObject().put("username",username).put("password",pass)
                        it.write(json.toString().toByteArray(Charsets.UTF_8))
                    }
                    val token=try {
                        if(auth.responseCode !in 200..299)throw IllegalStateException("Autenticação rejeitada: "+auth.responseCode)
                        JSONObject(auth.inputStream.bufferedReader().use{it.readText()}).getString("access_token")
                    }finally{auth.disconnect()}
                    val stream=URL(url+"/api/v1/sync/events").openConnection() as HttpURLConnection
                    connection=stream
                    stream.connectTimeout=10000;stream.readTimeout=35000
                    stream.setRequestProperty("Authorization","Bearer "+token)
                    stream.setRequestProperty("Accept","text/event-stream")
                    stream.setRequestProperty("Cache-Control","no-cache")
                    if(stream.responseCode==404) {
                        // Servidor ainda sem canal de eventos: manter sincronizacao periodica.
                        stream.disconnect();break
                    }
                    if(stream.responseCode!=200)throw IllegalStateException("Canal de eventos indisponivel")
                    stream.inputStream.bufferedReader().use { input ->
                        while(running.get()) {
                            val line=input.readLine()?:break
                            if(line.startsWith("data:")) {
                                val next=line.removePrefix("data:").trim().toLongOrNull()?:continue
                                if(lastSequence>=0 && next>lastSequence) {
                                    Handler(Looper.getMainLooper()).post {
                                        if(running.get())changed()
                                    }
                                }
                                if(next>lastSequence)lastSequence=next
                            }
                        }
                    }
                } catch (_:Exception) {
                    // A rede pode falhar: o JobScheduler continua o envio offline.
                } finally {
                    connection?.disconnect();connection=null
                }
                if(running.get())try{Thread.sleep(5000)}catch(_:InterruptedException){break}
            }
            running.set(false)
        },"Sentinela-HUB-Eventos").apply{isDaemon=true;start()}
    }
    fun stop() {
        running.set(false)
        connection?.disconnect()
        worker?.interrupt()
        worker=null
    }
}
