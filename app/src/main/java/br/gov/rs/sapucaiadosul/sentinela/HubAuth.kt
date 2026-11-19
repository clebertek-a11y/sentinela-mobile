package br.gov.rs.sapucaiadosul.sentinela

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Primeira autenticacao pelo HUB. Offline somente apos login online valido. */
object HubAuth {
    data class Identity(val matricula: String, val name: String, val role: String)
    fun authenticate(ctx: Context, user: String, password: String, callback: (Identity?, String?) -> Unit) {
        Thread {
            val result = try {
                val conn = URL(HubConfig.URL + "/api/v1/auth/login").openConnection() as HttpURLConnection
                try {
                    conn.requestMethod="POST"
                    conn.connectTimeout=11000
                    conn.readTimeout=13000
                    conn.doOutput=true
                    conn.setRequestProperty("Content-Type","application/json")
                    conn.outputStream.use { it.write(JSONObject().put("username",user)
                        .put("password",password).toString().toByteArray(Charsets.UTF_8)) }
                    val code=conn.responseCode
                    when(code) {
                        200 -> {
                            val body=JSONObject(conn.inputStream.bufferedReader().use {it.readText()})
                            val who=body.getJSONObject("user")
                            val role=when(who.optString("role","leitura").lowercase()) {
                                "admin" -> "ADMIN"
                                "operador" -> "OPERADOR"
                                "agente" -> "AGENTE"
                                else -> "CONSULTA"
                            }
                            Pair(Identity(who.optString("username",user),
                                who.optString("full_name",user),role),null)
                        }
                        401,403 -> Pair(null,"Matrícula ou senha inválida no HUB")
                        else -> Pair(null,"HUB respondeu HTTP " + code)
                    }
                } finally { conn.disconnect() }
            } catch(e:Exception) { Pair(null,"HUB inacessível: " + (e.message ?: "sem conexão").take(110)) }
            Handler(Looper.getMainLooper()).post { callback(result.first,result.second) }
        }.start()
    }
}
