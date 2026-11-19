package br.gov.rs.sapucaiadosul.sentinela

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Assinatura institucional do PRÓPRIO servidor autenticado.
 * Não é exposta a usuários terceiros e não é enviada como anexo da ocorrência.
 */
object HubSignature {
    private fun pendingKey(mat:String)="signature_upload_pending_"+mat
    fun markPending(ctx:Context,mat:String){
        ctx.getSharedPreferences("sync_settings",Context.MODE_PRIVATE).edit()
            .putBoolean(pendingKey(mat),true).apply()
    }
    fun sync(ctx:Context,mat:String,after:(Boolean)->Unit={}){
        Thread({
            val ok=try{
                val user=LocalStore(ctx).userByMatricula(mat) ?: error("Conta não encontrada")
                val prefs=ctx.getSharedPreferences("sync_settings",Context.MODE_PRIVATE)
                val pending=prefs.getBoolean(pendingKey(mat),false)
                val token=HubDirectory.token(ctx)?:error("Sem internet")
                if(pending){
                    val file=File(user.signaturePath)
                    require(file.isFile){"Assinatura local inexistente"}
                    require(file.length() in 8L..262144L){"Arquivo de assinatura incompatível"}
                    val c=URL(HubConfig.URL+"/api/v1/profile/signature").openConnection() as HttpURLConnection
                    try{
                        c.requestMethod="POST";c.doOutput=true
                        c.connectTimeout=10000;c.readTimeout=15000
                        c.setRequestProperty("Authorization","Bearer "+token)
                        c.setRequestProperty("Content-Type","image/png")
                        c.outputStream.use{out->file.inputStream().use{it.copyTo(out)}}
                        if(c.responseCode!=200)error("Falha ao arquivar assinatura")
                        prefs.edit().putBoolean(pendingKey(mat),false).apply()
                    }finally{c.disconnect()}
                    true
                }else{
                    val c=URL(HubConfig.URL+"/api/v1/profile/signature").openConnection() as HttpURLConnection
                    try{
                        c.connectTimeout=10000;c.readTimeout=15000
                        c.setRequestProperty("Authorization","Bearer "+token)
                        if(c.responseCode==404){
                            android.os.Handler(android.os.Looper.getMainLooper()).post{after(false)}
                            return@Thread
                        }
                        if(c.responseCode!=200)error("Assinatura remota indisponível")
                        val dir=File(ctx.filesDir,"assinaturas").apply{mkdirs()}
                        val file=File(dir,mat.replace(Regex("[^A-Za-z0-9_-]"),"_")+".png")
                        val tmp=File(dir,file.name+".tmp")
                        val bytes=c.inputStream.use{it.readBytes()}
                        if(bytes.size !in 24..262144)error("Assinatura remota inválida")
                        tmp.writeBytes(bytes)
                        if(!tmp.renameTo(file))error("Falha ao preservar assinatura")
                        LocalStore(ctx).saveRegisteredSignature(user.id,file.absolutePath)
                        true
                    }finally{c.disconnect()}
                }
            }catch(_:Exception){false}
            android.os.Handler(android.os.Looper.getMainLooper()).post{after(ok)}
        },"Sentinela-Assinatura").start()
    }
}
