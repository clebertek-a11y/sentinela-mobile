package br.gov.rs.sapucaiadosul.sentinela

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Conexao de eventos de longa espera, mantida apenas enquanto o Sentinela esta aberto.
 * No segundo plano o JobScheduler ainda recupera dados; offline permanece preservado.
 * O HUB precisa oferecer GET /api/v1/sync/wait via gateway.
 */
class HubRealtime(private val context: Context, private val callback: (Boolean, Boolean)->Unit) {
    private val live=AtomicBoolean(false)
    private val ui=Handler(Looper.getMainLooper())
    @Volatile private var activeConnection: HttpURLConnection? = null

    fun start() {
        if(!live.compareAndSet(false,true)) return
        Thread({ listen() },"sentinela-hub-live").start()
    }

    fun stop() {
        live.set(false)
        activeConnection?.disconnect()
        activeConnection=null
    }

    private fun request(method: String, path: String, payload: JSONObject?, token: String?): JSONObject {
        val conn=(URL(HubConfig.URL+path).openConnection() as HttpURLConnection)
        activeConnection=conn
        try {
            conn.requestMethod=method
            conn.connectTimeout=12_000
            conn.readTimeout=32_000
            conn.setRequestProperty("Accept","application/json")
            if(token != null) conn.setRequestProperty("Authorization","Bearer "+token)
            if(payload != null) {
                conn.doOutput=true
                conn.setRequestProperty("Content-Type","application/json; charset=utf-8")
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code=conn.responseCode
            if(code==401 || code==403) throw SecurityException("HUB negou autenticacao: "+code)
            if(code !in 200..299) throw Exception("HUB respondeu "+code)
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            if(activeConnection===conn)activeConnection=null
            conn.disconnect()
        }
    }

    private fun listen() {
        var failures=0
        var cursor=context.getSharedPreferences("sync_settings",Context.MODE_PRIVATE).getLong("hub_cursor",0L)
        var token=""
        while(live.get()) {
            try {
                val prefs=context.getSharedPreferences("sync_settings",Context.MODE_PRIVATE)
                val user=prefs.getString("hub_user","").orEmpty()
                val password=SecureSecrets.read(context)
                if(user.isBlank() || password.isBlank()) break
                if(token.isEmpty()) {
                    val response=request("POST","/api/v1/auth/login",
                        JSONObject().put("username",user).put("password",password),null)
                    token=response.getString("access_token")
                }
                val wait=request("GET","/api/v1/sync/wait?since="+cursor,null,token)
                val newSeq=wait.optLong("server_seq",cursor)
                if(wait.optBoolean("changed",false) && newSeq>cursor) {
                    cursor=newSeq
                    if(live.get())ui.post { if(live.get())callback(true,false) }
                }
                failures=0
            } catch (e:SecurityException) {
                if(live.get())ui.post { if(live.get())callback(false,true) }
                break
            } catch (_:Exception) {
                token=""
                failures++
                if(!live.get())break
                try {
                    Thread.sleep((failures*2500L).coerceAtMost(20_000L))
                } catch (_:InterruptedException){break}
            }
        }
        live.set(false)
    }
}
