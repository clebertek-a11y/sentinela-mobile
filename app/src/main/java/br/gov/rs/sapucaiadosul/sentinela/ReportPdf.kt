package br.gov.rs.sapucaiadosul.sentinela

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Modelo institucional do BA baseado no formulario atualmente utilizado pela COMPDEC. */
object ReportPdf {
    private const val W=595
    private const val H=842
    private const val LEFT=42f
    private const val RIGHT=553f
    private val navy=Color.rgb(0,63,117)
    private val orange=Color.rgb(255,107,0)
    private val pale=Color.rgb(247,247,247)
    private val ink=Color.rgb(30,30,30)
    private val gray=Color.rgb(94,94,94)
    private fun safeFilePart(name:String):String=
        name.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"),"-").trim().take(75).ifBlank{"Sem nome"}

    fun generate(ctx:Context,o:Incident,r:Report,photos:List<Photo>):File {
        val meta=LocalStore(ctx).baMeta(o.id)
        val doc=PdfDocument()
        val pen=Paint(Paint.ANTI_ALIAS_FLAG)
        var active:PdfDocument.Page?=null
        lateinit var canvas:Canvas
        var y=0f
        var pageNumber=0
        fun drawText(text:String,x:Float,top:Float,size:Float=10f,color:Int=ink,bold:Boolean=false) {
            pen.color=color;pen.textSize=size;pen.isFakeBoldText=bold;pen.style=Paint.Style.FILL
            canvas.drawText(text,x,top,pen);pen.isFakeBoldText=false
        }
        fun imageResource(res:Int,x:Float,top:Float,width:Float,height:Float) {
            val bmp=BitmapFactory.decodeResource(ctx.resources,res)?:return
            try {
                canvas.drawRoundRect(RectF(x,top,x+width,top+height),5f,5f,Paint().apply{color=Color.WHITE})
                val scale=minOf((width-8f)/bmp.width,(height-8f)/bmp.height)
                val bw=bmp.width*scale;val bh=bmp.height*scale
                canvas.drawBitmap(bmp,null,RectF(x+(width-bw)/2,top+(height-bh)/2,
                    x+(width+bw)/2,top+(height+bh)/2),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            } finally {bmp.recycle()}
        }
        fun page(first:Boolean=false) {
            if(active!=null){doc.finishPage(active!!);active=null}
            pageNumber++
            active=doc.startPage(PdfDocument.PageInfo.Builder(W,H,pageNumber).create())
            canvas=active!!.canvas
            canvas.drawColor(Color.WHITE)
            if(first) {
                pen.color=navy;canvas.drawRect(0f,0f,W.toFloat(),138f,pen)
                imageResource(R.drawable.sentinela_app_icon,44f,27f,75f,78f)
                imageResource(R.drawable.defesa_civil_logo,470f,27f,75f,78f)
                drawText("COORDENADORIA MUNICIPAL DE PROTEÇÃO E DEFESA CIVIL",W/2f-161f,43f,9.5f,Color.WHITE)
                drawText("SAPUCAIA DO SUL - RS",W/2f-65f,59f,10f,Color.WHITE)
                drawText("BOLETIM DE ATENDIMENTO",W/2f-127f,83f,18f,Color.WHITE,true)
                pen.color=orange;canvas.drawRoundRect(RectF(200f,94f,395f,120f),5f,5f,pen)
                drawText("Protocolo: "+o.number,210f,111f,12f,Color.WHITE,true)
                y=159f
            } else y=23f
        }
        fun ensure(height:Float) {if(y+height>H-38f)page(false)}
        fun section(title:String){
            ensure(35f);pen.color=navy;canvas.drawRoundRect(RectF(LEFT,y,RIGHT,y+21f),3f,3f,pen)
            drawText(title,LEFT+9f,y+15f,11f,Color.WHITE,true)
            y+=29f
        }
        fun wrap(value:String,maxWidth:Float,size:Float=10f):List<String>{
            pen.textSize=size;pen.isFakeBoldText=false
            val res=mutableListOf<String>()
            value.replace("\r","").split("\n").forEach { para ->
                var line=""
                para.split(" ").forEach { word ->
                    val candidate=if(line.isEmpty())word else "$"+"line $"+ "word"
                    if(pen.measureText(candidate)>maxWidth && line.isNotEmpty()){
                        res.add(line);line=word
                    } else line=candidate
                }
                res.add(line)
            }
            return res.ifEmpty{listOf("-")}
        }
        fun rows(vararg items:Pair<String,String>){
            val values=items.map{wrap(it.second.ifBlank{"-"},RIGHT-LEFT-135f)}
            val height=items.indices.sumOf{maxOf(17,values[it].size*15).toInt()}.toFloat()+15f
            ensure(height+8f)
            pen.color=pale;canvas.drawRoundRect(RectF(LEFT,y,RIGHT,y+height),4f,4f,pen)
            var yy=y+17f
            items.forEachIndexed {i,item ->
                drawText(item.first+":",LEFT+10f,yy,10f,gray,true)
                values[i].forEachIndexed{j,line->drawText(line,LEFT+118f,yy+j*15f,10f)}
                yy+=maxOf(17f,values[i].size*15f)
            }
            y+=height+14f
        }
        fun paragraph(value:String){
            val lines=wrap(value.ifBlank{"-"},RIGHT-LEFT-24f,10.2f)
            var start=0
            while(start<lines.size){
                val available=((H-48f-y-20f)/15f).toInt()
                if(available<1){page(false);continue}
                val count=minOf(lines.size-start,available)
                val height=count*15f+20f
                pen.color=pale;canvas.drawRoundRect(RectF(LEFT,y,RIGHT,y+height),4f,4f,pen)
                for(i in 0 until count)drawText(lines[start+i],LEFT+10f,y+17f+i*15f,10.2f)
                y+=height+12f
                start+=count
            }
        }
        fun check(label:String,checked:Boolean,x:Float,yy:Float) {
            pen.color=if(checked)orange else gray
            if(checked)canvas.drawRoundRect(RectF(x,yy-11f,x+11f,yy),2f,2f,pen)
            else{pen.style=Paint.Style.STROKE;pen.strokeWidth=1f;canvas.drawRoundRect(RectF(x,yy-11f,x+11f,yy),2f,2f,pen);pen.style=Paint.Style.FILL}
            drawText(label,x+17f,yy-1f,10f)
        }
        fun signature(title:String,name:String,sub:String,path:String) {
            section(title);ensure(110f)
            pen.color=pale;canvas.drawRoundRect(RectF(LEFT,y,LEFT+260f,y+99f),4f,4f,pen)
            val bmp=if(path.isNotBlank()) BitmapFactory.decodeFile(path) else null
            if(bmp!=null)try{
                val scale=minOf(230f/bmp.width,53f/bmp.height)
                val bw=bmp.width*scale;val bh=bmp.height*scale
                canvas.drawBitmap(bmp,null,RectF(LEFT+8f,y+5f,LEFT+8f+bw,y+5f+bh),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }finally{bmp.recycle()}
            pen.color=gray;canvas.drawRect(LEFT+10f,y+64f,LEFT+250f,y+64.6f,pen)
            drawText(name.ifBlank{"-"},LEFT+10f,y+79f,10f,ink,true)
            drawText(sub,LEFT+10f,y+92f,9f,gray)
            y+=112f
        }
        fun dateFormatted(s:String):String=try {
            LocalDate.parse(s).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        }catch(_:Exception){s}
        try {
            page(true)
            val y0=y
            pen.color=pale;canvas.drawRoundRect(RectF(LEFT,y0,LEFT+245f,y0+36f),5f,5f,pen)
            canvas.drawRoundRect(RectF(LEFT+256f,y0,RIGHT,y0+36f),5f,5f,pen)
            drawText("DATA",LEFT+9f,y0+12f,9f,gray,true)
            drawText(dateFormatted(o.date),LEFT+9f,y0+28f,13f,ink,true)
            drawText("HORÁRIO",LEFT+265f,y0+12f,9f,gray,true)
            drawText("Não informado",LEFT+265f,y0+28f,11f)
            y+=52f
            section("DADOS DO SOLICITANTE")
            rows("Solicitante" to o.solicitante,"CPF" to meta.solicitanteCpf,
                "Telefone" to o.telefone,"Endereço" to (o.rua+" - "+o.bairro))
            section("TIPO DO SOLICITANTE E ATENDIMENTO")
            ensure(51f);pen.color=pale;canvas.drawRoundRect(RectF(LEFT,y,RIGHT,y+38f),4f,4f,pen)
            listOf("Proprietário","Inquilino","Vizinho","Parente","Outros").forEachIndexed{ i,item ->
                check(item,meta.tipoSolicitante==item,LEFT+9f+i*101f,y+16f)
            }
            check("Emergencial",meta.tipoAtendimento=="Emergencial",LEFT+9f,y+34f)
            check("Preventiva",meta.tipoAtendimento=="Preventiva",LEFT+148f,y+34f)
            y+=50f
            section("RESPONSÁVEL NO MOMENTO DA VISTORIA")
            rows("Responsável" to r.responsavel,"CPF" to r.responsavelDocumento,
                "Telefone" to meta.responsavelTelefone,"Vínculo" to meta.responsavelVinculo)
            section("TIPOS DE OCORRÊNCIA")
            val types=mutableListOf<String>()
            try {
                val arr=JSONArray(meta.tiposOcorrencia)
                for(i in 0 until arr.length())if(arr.optString(i).isNotBlank())types.add(arr.optString(i))
            }catch(_:Exception){}
            if(types.isEmpty())types.addAll(o.tipo.split("; ").filter{it.isNotBlank()})
            paragraph(types.joinToString("\n"){"●  "+it})
            section("HISTÓRICO DA VISTORIA")
            paragraph(r.historico.ifBlank{o.descricao})
            section("PROVIDÊNCIAS ADOTADAS")
            paragraph(r.providencias)
            page(false)
            section("SERVIDORES RESPONSÁVEIS")
            val staff=mutableListOf<Pair<String,String>>()
            try {
                val arr=JSONArray(meta.servidores)
                for(i in 0 until arr.length()){
                    val v=arr.optJSONObject(i)?:continue
                    staff.add(v.optString("nome") to v.optString("matricula"))
                }
            }catch(_:Exception){}
            if(staff.isEmpty())staff.add(r.agenteNome to r.agenteMatricula)
            paragraph(staff.joinToString("\n"){"●  "+it.first+"     Mat: "+it.second})
            section("LOCALIZAÇÃO DA OCORRÊNCIA")
            paragraph(r.gps.ifBlank{"Localização não capturada"})
            section("REGISTRO FOTOGRÁFICO")
            if(photos.isEmpty())paragraph("Nenhuma fotografia vinculada.")
            else photos.forEachIndexed{idx,foto->
                val bitmap=BitmapFactory.decodeFile(foto.path)?:return@forEachIndexed
                try {
                    ensure(190f)
                    val scale=minOf(460f/bitmap.width,158f/bitmap.height)
                    val bw=bitmap.width*scale;val bh=bitmap.height*scale
                    canvas.drawBitmap(bitmap,null,RectF(LEFT+8f,y,LEFT+8f+bw,y+bh),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    y+=bh+5f
                    drawText("Foto "+(idx+1),LEFT+9f,y+11f,9f,gray)
                    y+=26f
                }finally{bitmap.recycle()}
            }
            signature("ASSINATURA DO RESPONSÁVEL NO LOCAL",r.responsavel,
                "CPF: "+r.responsavelDocumento,r.responsavelSignature)
            signature("ASSINATURA DO AGENTE DE DEFESA CIVIL",r.agenteNome,
                "Matrícula: "+r.agenteMatricula,r.agenteSignature)
            ensure(35f);pen.color=pale;canvas.drawRoundRect(RectF(LEFT,y,RIGHT,y+26f),4f,4f,pen)
            val created=LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm:ss"))
            drawText("Documento gerado em "+created,LEFT+110f,y+17f,9f,gray)
            doc.finishPage(active!!);active=null
            val filename="BA - "+safeFilePart(r.responsavel.ifBlank{o.solicitante})+
                " - "+safeFilePart(o.number)+".pdf"
            val file=File(FileTools.dir(ctx,o.id),filename)
            FileOutputStream(file).use {doc.writeTo(it)}
            return file
        }finally{
            if(active!=null)try{doc.finishPage(active!!)}catch(_:Exception){}
            doc.close()
        }
    }
}
