package br.gov.rs.sapucaiadosul.sentinela

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Administração de usuários executada apenas no HUB, nunca em bancos locais isolados. */
object HubAdmin {
    data class Account(
        val matricula:String,val nome:String,val perfil:String,val bloqueado:Boolean
    )
    private fun call(ctx:Context,method:String,path:String,data:JSONObject?):JSONObject {
        val token=HubDirectory.token(ctx)?:error("Sessão do HUB indisponível")
        val conn=URL(HubConfig.URL+path).openConnection() as HttpURLConnection
        return try{
            conn.requestMethod=method;conn.connectTimeout=10000;conn.readTimeout=15000
            conn.setRequestProperty("Authorization","Bearer "+token)
            conn.setRequestProperty("Accept","application/json")
            if(data!=null){
                conn.doOutput=true
                conn.setRequestProperty("Content-Type","application/json")
                conn.outputStream.use{it.write(data.toString().toByteArray(Charsets.UTF_8))}
            }
            val code=conn.responseCode
            val body=(if(code in 200..299)conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use{it.readText()} ?: ""
            if(code !in 200..299){
                val reason=try{JSONObject(body).optString("error")}catch(_:Exception){""}
                error("HUB HTTP "+code+": "+reason.ifBlank{"operação recusada"})
            }
            JSONObject(body)
        }finally{conn.disconnect()}
    }
    fun list(ctx:Context,done:(List<Account>?,String?)->Unit){
        Thread({
            val result=try{
                val data=call(ctx,"GET","/api/v1/admin/users",null).getJSONArray("users")
                val list=(0 until data.length()).map{ i->
                    val item=data.getJSONObject(i)
                    Account(item.optString("username"),item.optString("full_name"),
                        item.optString("role"),item.optBoolean("disabled"))
                }.sortedBy{it.nome}
                Pair(list,null)
            }catch(e:Exception){Pair(null,(e.message?:"Falha ao consultar HUB"))}
            Handler(Looper.getMainLooper()).post{done(result.first,result.second)}
        },"Sentinela-Administracao").start()
    }
    fun write(ctx:Context,matricula:String?,body:JSONObject,done:(String?)->Unit){
        Thread({
            val error=try{
                if(matricula==null) call(ctx,"POST","/api/v1/admin/users",body)
                else call(ctx,"POST","/api/v1/admin/users/"+
                    java.net.URLEncoder.encode(matricula,"UTF-8"),body)
                null
            }catch(e:Exception){e.message ?: "Não foi possível alterar o cadastro"}
            Handler(Looper.getMainLooper()).post{done(error)}
        },"Sentinela-Administracao").start()
    }
}
