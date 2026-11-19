package br.gov.rs.sapucaiadosul.sentinela

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Cadastro institucional, somente contas ativas, sincronizado ao fazer login. */
object HubDirectory {
    data class Staff(val matricula:String,val nome:String,val perfil:String)
    private const val KEY="directory_json"
    fun cached(ctx:Context,matricula:String):Staff? {
        if(matricula.isBlank())return null
        val value=ctx.getSharedPreferences("sync_settings",Context.MODE_PRIVATE).getString(KEY,"") ?: ""
        if(value.isBlank())return null
        return try{
            val arr=JSONObject(value).optJSONArray("users")?:return null
            for(i in 0 until arr.length()){
                val item=arr.optJSONObject(i) ?: continue
                if(item.optString("matricula")==matricula.trim())
                    return Staff(item.optString("matricula"),item.optString("nome"),item.optString("perfil"))
            }
            null
        }catch(_:Exception){null}
    }

    fun token(ctx:Context):String? {
        val prefs=ctx.getSharedPreferences("sync_settings",Context.MODE_PRIVATE)
        val user=prefs.getString("hub_user","").orEmpty()
        val pass=SecureSecrets.read(ctx)
        if(user.isBlank()||pass.isBlank())return null
        val conn=URL(HubConfig.URL+"/api/v1/auth/login").openConnection() as HttpURLConnection
        return try{
            conn.requestMethod="POST";conn.doOutput=true
            conn.connectTimeout=9000;conn.readTimeout=12000
            conn.setRequestProperty("Content-Type","application/json")
            val json=JSONObject().put("username",user).put("password",pass)
            conn.outputStream.use{it.write(json.toString().toByteArray(Charsets.UTF_8))}
            if(conn.responseCode!=200)return null
            JSONObject(conn.inputStream.bufferedReader().use{it.readText()}).optString("access_token")
        }finally{conn.disconnect()}
    }

    fun refresh(ctx:Context,done:(Boolean)->Unit={}){
        Thread({
            val ok=try{
                val token=token(ctx) ?: error("Sem sessão")
                val conn=URL(HubConfig.URL+"/api/v1/directory/users").openConnection() as HttpURLConnection
                try{
                    conn.connectTimeout=9000;conn.readTimeout=12000
                    conn.setRequestProperty("Authorization","Bearer "+token)
                    if(conn.responseCode!=200)error("Diretório indisponível")
                    val json=conn.inputStream.bufferedReader().use{it.readText()}
                    val parsed=JSONObject(json)
                    if(parsed.optJSONArray("users")==null)error("Diretório inválido")
                    ctx.getSharedPreferences("sync_settings",Context.MODE_PRIVATE).edit()
                        .putString(KEY,json).apply()
                    true
                }finally{conn.disconnect()}
            }catch(_:Exception){false}
            android.os.Handler(android.os.Looper.getMainLooper()).post{done(ok)}
        },"Sentinela-Diretorio").start()
    }
}
