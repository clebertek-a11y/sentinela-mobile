package br.gov.rs.sapucaiadosul.sentinela

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Modelo A4 do BA de Sapucaia do Sul, baseado no PDF institucional fornecido.
 * Layout em barras azuis, protocolo laranja e blocos cinza, com paginação segura.
 */
object ReportPdf {
    private const val W=595
    private const val H=842
    private const val LEFT=42f
    private const val RIGHT=553f
    private val BLUE=Color.rgb(0,63,117)
    private val ORANGE=Color.rgb(255,107,0)
    private val GRAY=Color.rgb(248,248,248)
    private val INK=Color.rgb(35,35,35)

    fun generate(ctx:Context,o:Incident,r:Report,photos:List<Photo>):File {
        val extras=BAExtrasStore(LocalStore(ctx)).get(o.id)
        val pdf=PdfDocument()
        val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface=Typeface.create("sans-serif",Typeface.NORMAL)
            textSize=11f;color=INK
        }
        var pageNo=0
        var active:PdfDocument.Page?=null
        lateinit var cv:Canvas
        var y=55f
        fun p(text:String,size:Float=11f,bold:Boolean=false,color:Int=INK){
            pen.textSize=size;pen.isFakeBoldText=bold;pen.color=color
        }
        fun rect(x:Float,top:Float,w:Float,h:Float,color:Int){
            pen.color=color;cv.drawRoundRect(RectF(x,top,x+w,top+h),3f,3f,pen)
        }
        fun startPage() {
            if(active!=null) pdf.finishPage(active!!)
            pageNo++
            active=pdf.startPage(PdfDocument.PageInfo.Builder(W,H,pageNo).create())
            cv=active!!.canvas
            cv.drawColor(Color.WHITE)
            y=37f
        }
        fun ensure(height:Float){if(y+height>H-45f)startPage()}
        fun lineWrap(txt:String,maxWidth:Float,size:Float=11f,bold:Boolean=false):List<String>{
            p("",size,bold)
            val lines=mutableListOf<String>()
            txt.split("\n").forEach { paragraph ->
                if(paragraph.isEmpty()){lines.add("");return@forEach}
                var line=""
                paragraph.split(' ').forEach {word ->
                    val candidate=if(line.isBlank())word else "$line $word"
                    if(pen.measureText(candidate)>maxWidth && line.isNotBlank()){
                        lines.add(line);line=word
                    }else line=candidate
                }
                lines.add(line)
            }
            return lines
        }
        fun section(name:String) {
            ensure(34f)
            rect(LEFT,y,RIGHT-LEFT,23f,BLUE)
            p("",11f,true,Color.WHITE)
            cv.drawText(name,LEFT+10f,y+16f,pen)
            y+=31f
        }
        fun panelText(value:String,minHeight:Float=24f) {
            val lines=lineWrap(value.ifBlank{"-"},RIGHT-LEFT-20f)
            val total=maxOf(minHeight,lines.size*16f+16f)
            if(y+total<=H-44f){
                rect(LEFT,y,RIGHT-LEFT,total,GRAY)
                p("",11f)
                lines.forEachIndexed {index,txt -> cv.drawText(txt,LEFT+10f,y+18f+index*16f,pen)}
                y+=total+14f
            } else {
                for(txt in lines) {
                    ensure(24f)
                    rect(LEFT,y,RIGHT-LEFT,23f,GRAY)
                    p("",11f)
                    cv.drawText(txt,LEFT+10f,y+16f,pen)
                    y+=23f
                }
                y+=12f
            }
        }
        fun kv(keys:List<Pair<String,String>>){
            val content=keys.flatMap { (name,value) ->
                val wrapped=lineWrap(value.ifBlank{"-"},RIGHT-LEFT-120f)
                (wrapped.ifEmpty{listOf("-")}).mapIndexed {i,t -> (if(i==0)name+": " else "   ")+t }
            }
            val height=content.size*16f+18f
            ensure(height+10f)
            rect(LEFT,y,RIGHT-LEFT,height,GRAY)
            var pos=y+18f
            keys.forEach { (name,value) ->
                p("",11f,true,Color.rgb(100,100,100))
                cv.drawText(name+":",LEFT+9f,pos,pen)
                p("",11f,false)
                val values=lineWrap(value.ifBlank{"-"},RIGHT-LEFT-135f)
                for(v in values) {cv.drawText(v,LEFT+111f,pos,pen);pos+=16f}
            }
            y+=height+13f
        }
        fun checkbox(x:Float,top:Float,title:String,checked:Boolean){
            pen.style=if(checked)Paint.Style.FILL else Paint.Style.STROKE
            pen.color=if(checked)ORANGE else Color.GRAY
            cv.drawRoundRect(RectF(x,top,x+11,top+11),2f,2f,pen)
            pen.style=Paint.Style.FILL
            p("",10.5f)
            cv.drawText(title,x+16,top+10,pen)
        }
        fun signature(title:String,file:String,name:String,sub:String) {
            section(title)
            ensure(112f)
            rect(LEFT,y,244f,101f,GRAY)
            if(file.isNotBlank()){
                val bitmap=BitmapFactory.decodeFile(file)
                if(bitmap!=null) {
                    cv.drawBitmap(bitmap,null,RectF(LEFT+10f,y+4f,LEFT+222f,y+58f),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    bitmap.recycle()
                }
            }
            pen.color=Color.GRAY;pen.strokeWidth=0.6f
            cv.drawLine(LEFT+11f,y+65f,LEFT+228f,y+65f,pen)
            p("",11f,true)
            cv.drawText(name.ifBlank{"-"},LEFT+10f,y+80f,pen)
            p("",10f,false,Color.DKGRAY)
            cv.drawText(sub,LEFT+10f,y+93f,pen)
            y+=112f
        }
        try {
            startPage()
            rect(0f,0f,W.toFloat(),138f,BLUE)
            // Identidade institucional. O brasao municipal sera mantido em
            // recurso especifico quando homologado pela Prefeitura.
            rect(44f,28f,81f,81f,Color.WHITE)
            p("",9f,true,BLUE)
            cv.drawText("PREFEITURA",53f,63f,pen)
            p("",8.5f,false,BLUE)
            cv.drawText("Sapucaia do Sul",48f,80f,pen)
            rect(470f,28f,81f,81f,Color.WHITE)
            val logo=BitmapFactory.decodeResource(ctx.resources,R.drawable.defesa_civil_logo)
            if(logo!=null) {
                cv.drawBitmap(logo,null,RectF(478f,34f,543f,103f),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                logo.recycle()
            }
            p("",10f,false,Color.WHITE)
            cv.drawText("COORDENADORIA MUNICIPAL DE PROTEÇÃO E DEFESA CIVIL",138f,36f,pen)
            cv.drawText("SAPUCAIA DO SUL - RS",224f,50f,pen)
            p("",19f,true,Color.WHITE)
            cv.drawText("BOLETIM DE ATENDIMENTO",155f,80f,pen)
            rect(204f,95f,187f,26f,ORANGE)
            p("",11f,true,Color.WHITE)
            cv.drawText("Protocolo: "+o.number.take(34),211f,113f,pen)
            y=157f
            val date=try { LocalDate.parse(o.date).format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) } catch(_:Exception){o.date}
            var time=""
            try {
                LocalStore(ctx).readableDatabase.rawQuery("SELECT created_at FROM incidents WHERE id=?",arrayOf(o.id))
                    .use { if(it.moveToFirst())time=it.getString(0).substring(11,16) }
            } catch(_:Exception){}
            rect(LEFT,y,249f,40f,GRAY)
            rect(LEFT+261f,y,250f,40f,GRAY)
            p("",9f,true,Color.GRAY)
            cv.drawText("DATA",LEFT+8f,y+12f,pen)
            cv.drawText("HORÁRIO",LEFT+270f,y+12f,pen)
            p("",14f,true,INK)
            cv.drawText(date,LEFT+8f,y+30f,pen)
            cv.drawText(time.ifBlank{"-"},LEFT+270f,y+30f,pen)
            y+=59f

            section("DADOS DO SOLICITANTE")
            kv(listOf("Solicitante" to o.solicitante,"CPF" to extras.solicitanteCpf,
                "Telefone" to o.telefone,"Endereço" to listOf(o.rua,o.bairro)
                    .filter{it.isNotBlank()}.joinToString(" - ")))
            section("TIPO DO SOLICITANTE E ATENDIMENTO")
            ensure(54f)
            rect(LEFT,y,RIGHT-LEFT,52f,GRAY)
            listOf("Proprietário","Inquilino","Vizinho","Parente","Outros").forEachIndexed {i,c ->
                checkbox(LEFT+10f+i*101f,y+8f,c,extras.tipoSolicitante==c)
            }
            checkbox(LEFT+10f,y+30f,"Emergencial",extras.tipoAtendimento=="Emergencial")
            checkbox(LEFT+150f,y+30f,"Preventiva",extras.tipoAtendimento=="Preventiva")
            y+=66f
            section("RESPONSÁVEL NO MOMENTO DA VISTORIA")
            kv(listOf("Responsável" to r.responsavel,"CPF" to r.responsavelDocumento,
                "Telefone" to extras.responsavelTelefone,"Vínculo" to extras.responsavelVinculo))
            section("TIPOS DE OCORRÊNCIA")
            val selected=extras.tiposOcorrencia.toMutableList()
            if(selected.isEmpty()&&o.tipo.isNotBlank())selected.add(o.tipo)
            if(extras.outroTipo.isNotBlank())selected.add("Outro - "+extras.outroTipo)
            panelText(selected.joinToString("\n") { "●  "+it },32f)
            section("HISTÓRICO DA VISTORIA")
            panelText(r.historico.ifBlank{o.descricao},45f)
            section("PROVIDÊNCIAS ADOTADAS")
            panelText(r.providencias,45f)

            // Como no modelo fornecido, servidores e assinaturas comecam na pagina seguinte.
            startPage()
            section("SERVIDORES RESPONSÁVEIS")
            val people=extras.servidores.ifEmpty {
                listOf(BAServidor(r.agenteNome,r.agenteMatricula))
            }
            panelText(people.joinToString("\n"){"●  "+it.nome+"       Mat: "+it.matricula},32f)
            section("LOCALIZAÇÃO DA OCORRÊNCIA")
            panelText(r.gps.ifBlank{"Localização não capturada"},35f)
            section("REGISTRO FOTOGRÁFICO")
            if(photos.isEmpty())panelText("Sem fotografias anexadas",30f)
            else {
                val width=249f
                val height=170f
                photos.forEachIndexed {i,item ->
                    if(i%2==0)ensure(190f)
                    val x=LEFT+(i%2)*(width+13f)
                    if(i%2==0)rect(LEFT,y,RIGHT-LEFT,height+18f,GRAY)
                    val bitmap=BitmapFactory.decodeFile(item.path)
                    if(bitmap!=null){
                        val maxW=width-8f;val maxH=height-6f
                        val scale=minOf(maxW/bitmap.width.toFloat(),maxH/bitmap.height.toFloat())
                        val bw=bitmap.width*scale;val bh=bitmap.height*scale
                        cv.drawBitmap(bitmap,null,RectF(x+(width-bw)/2f,y+(height-bh)/2f,x+(width+bw)/2f,y+(height+bh)/2f),
                            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                        bitmap.recycle()
                    }
                    p("",9f,false)
                    cv.drawText("Foto "+(i+1),x+6f,y+height+11f,pen)
                    if(i%2==1 || i==photos.lastIndex)y+=height+27f
                }
            }
            signature("ASSINATURA DO RESPONSÁVEL NO LOCAL",r.responsavelSignature,
                r.responsavel,"CPF: "+r.responsavelDocumento.ifBlank{"-"})
            signature("ASSINATURA DO AGENTE DE DEFESA CIVIL",r.agenteSignature,
                r.agenteNome,"Matrícula: "+r.agenteMatricula.ifBlank{"-"})
            ensure(37f)
            rect(LEFT,y,RIGHT-LEFT,28f,GRAY)
            p("",9f,false,Color.GRAY)
            val timestamp=java.time.LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm:ss"))
            cv.drawText("Documento gerado em "+timestamp,LEFT+100f,y+18f,pen)
            pdf.finishPage(active!!)
            active=null
            val named=r.responsavel.ifBlank{o.solicitante}
                .replace(Regex("[\\\\/:*?\"<>|]"),"-").trim().take(65).ifBlank{"Pessoa não identificada"}
            val unique=o.number.replace(Regex("[\\\\/:*?\"<>|]"),"-")
            val filename="BA - "+named+" - "+unique+".pdf"
            val file=File(FileTools.dir(ctx,o.id),filename)
            FileOutputStream(file).use {pdf.writeTo(it)}
            return file
        } finally {
            if(active!=null)try {pdf.finishPage(active!!)}catch(_:Exception){}
            pdf.close()
        }
    }
}
