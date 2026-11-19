package br.gov.rs.sapucaiadosul.sentinela

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Limpeza reversível: remove APENAS arquivos de atendimentos concluídos
 * e sincronizados, quando o checksum idêntico existe no HUB.
 * Nunca apaga registro, histórico SQLite, formulário pendente nem fonte da galeria.
 */
object StorageCleaner {
    data class Result(val arquivos:Int,val bytes:Long,val ignorados:Int,val error:String?=null)
    private data class Remote(val id:String,val sha:String)
    private fun db(ctx:Context):SQLiteDatabase {
        val d=LocalStore(ctx).writableDatabase
        d.execSQL("""CREATE TABLE IF NOT EXISTS cache_evictions(
            local_path TEXT PRIMARY KEY,
            occurrence_id TEXT NOT NULL,
            remote_id TEXT NOT NULL,
            sha256 TEXT NOT NULL,
            cleared_at TEXT NOT NULL
        )""")
        return d
    }
    fun hasEvicted(ctx:Context,incidentId:String):Boolean =
        db(ctx).rawQuery("SELECT 1 FROM cache_evictions WHERE occurrence_id=? LIMIT 1",
            arrayOf(incidentId)).use{it.moveToFirst()}
    fun wasEvictedLocal(ctx:Context,path:String):Boolean =
        db(ctx).rawQuery("SELECT 1 FROM cache_evictions WHERE local_path=? LIMIT 1",
            arrayOf(path)).use{it.moveToFirst()}
    fun wasEvictedRemote(ctx:Context,id:String,sha:String):Boolean =
        db(ctx).rawQuery("SELECT 1 FROM cache_evictions WHERE remote_id=? AND sha256=? LIMIT 1",
            arrayOf(id,sha)).use{it.moveToFirst()}

    private fun connection(path:String,token:String):HttpURLConnection {
        val c=URL(HubConfig.URL+path).openConnection() as HttpURLConnection
        c.connectTimeout=12000;c.readTimeout=60000
        c.setRequestProperty("Authorization","Bearer "+token)
        return c
    }
    private fun remoteFiles(incidentId:String,token:String):List<Remote> {
        val url="/api/v1/files?occurrence_id="+java.net.URLEncoder.encode(incidentId,"UTF-8")
        val c=connection(url,token)
        return try{
            if(c.responseCode!=200)error("HUB indisponível ("+c.responseCode+")")
            val arr=JSONObject(c.inputStream.bufferedReader().use{it.readText()})
                .optJSONArray("files")?:error("Lista de anexos inválida")
            (0 until arr.length()).mapNotNull{ ix->
                val ob=arr.optJSONObject(ix)?:return@mapNotNull null
                val id=ob.optString("id")
                val sha=ob.optString("sha256")
                if(id.isBlank()||sha.length!=64)null else Remote(id,sha)
            }
        }finally{c.disconnect()}
    }
    fun clean(ctx:Context,minAgeMillis:Long,preview:Boolean=false,done:(Result)->Unit) {
        Thread({
            var removed=0;var bytes=0L;var ignored=0;var failure:String?=null
            try{
                val token=HubDirectory.token(ctx)?:error("Sem conexão autenticada com o HUB")
                val store=LocalStore(ctx)
                val cutoff=System.currentTimeMillis()-minAgeMillis
                for(item in store.incidents()){
                    if(item.status!="Atendida"||item.syncState!="SINCRONIZADO"){
                        ignored++;continue
                    }
                    val candidates=store.photos(item.id).map{File(it.path)}+
                        listOfNotNull(item.pdfPath.takeIf{it.isNotBlank()}?.let{File(it)})+
                        listOf(File(FileTools.dir(ctx,item.id),"HISTORICO_${item.number}.pdf"))
                    val eligible=candidates.filter{it.isFile && it.lastModified()<=cutoff}
                    if(eligible.isEmpty())continue
                    val remote=remoteFiles(item.id,token)
                    val matching=remote.associateBy{it.sha}
                    for(file in eligible){
                        if(wasEvictedLocal(ctx,file.absolutePath))continue
                        val hash=FileTools.sha256(file)
                        val uploaded=matching[hash]
                        if(uploaded==null){ignored++;continue}
                        val length=file.length()
                        if(preview){removed++;bytes+=length;continue}
                        val db=db(ctx)
                        db.insertWithOnConflict("cache_evictions",null,ContentValues().apply{
                            put("local_path",file.absolutePath)
                            put("occurrence_id",item.id)
                            put("remote_id",uploaded.id)
                            put("sha256",uploaded.sha)
                            put("cleared_at",LocalStore.now())
                        },SQLiteDatabase.CONFLICT_REPLACE)
                        if(file.delete()){removed++;bytes+=length}
                        else{
                            db.delete("cache_evictions","local_path=?",arrayOf(file.absolutePath))
                            ignored++
                        }
                    }
                }
            }catch(e:Exception){failure=e.message?:"Falha na limpeza"}
            val result=Result(removed,bytes,ignored,failure)
            Handler(Looper.getMainLooper()).post{done(result)}
        },"Sentinela-Limpeza-Segura").start()
    }
    fun restore(ctx:Context,incidentId:String,done:(Result)->Unit) {
        Thread({
            var restored=0;var bytes=0L;var ignored=0;var failure:String?=null
            try{
                val token=HubDirectory.token(ctx)?:error("Sem conexão autenticada com o HUB")
                val db=db(ctx)
                val files=mutableListOf<Triple<String,String,String>>()
                db.rawQuery("SELECT local_path,remote_id,sha256 FROM cache_evictions WHERE occurrence_id=?",
                    arrayOf(incidentId)).use{cursor->
                    while(cursor.moveToNext())
                        files.add(Triple(cursor.getString(0),cursor.getString(1),cursor.getString(2)))
                }
                for((path,remoteId,hash) in files){
                    val output=File(path);output.parentFile?.mkdirs()
                    val temp=File(path+".restoring")
                    val conn=connection("/api/v1/files/"+remoteId,token)
                    try{
                        if(conn.responseCode!=200){ignored++;continue}
                        var total=0L
                        conn.inputStream.use{source->
                            FileOutputStream(temp).use{dest->
                                val buffer=ByteArray(32768)
                                while(true){
                                    val n=source.read(buffer);if(n<0)break
                                    total+=n
                                    if(total>16L*1024*1024)error("Arquivo acima do limite de segurança")
                                    dest.write(buffer,0,n)
                                }
                            }
                        }
                        if(FileTools.sha256(temp)!=hash)error("Hash do arquivo não confere")
                        if(!temp.renameTo(output))error("Falha ao restaurar arquivo")
                        db.delete("cache_evictions","local_path=?",arrayOf(path))
                        restored++;bytes+=total
                    }finally{temp.delete();conn.disconnect()}
                }
            }catch(e:Exception){failure=e.message?:"Falha na restauração"}
            val result=Result(restored,bytes,ignored,failure)
            Handler(Looper.getMainLooper()).post{done(result)}
        },"Sentinela-Restauracao").start()
    }
}
