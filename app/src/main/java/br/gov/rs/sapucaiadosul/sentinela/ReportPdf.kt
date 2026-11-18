package br.gov.rs.sapucaiadosul.sentinela

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream

/** PDF A4 nativo Android, vinculado ao número único do atendimento. */
object ReportPdf {
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val LEFT = 42f
    private const val RIGHT = 553f
    private val navy = Color.rgb(20, 40, 73)
    private val orange = Color.rgb(241, 126, 38)

    fun generate(ctx: Context, o: Incident, r: Report, photos: List<Photo>): File {
        val doc = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var pageNumber = 0
        var activePage: PdfDocument.Page? = null
        lateinit var canvas: Canvas
        var y = 48f
        fun page() {
            if (pageNumber > 0) doc.finishPage(activePage!!)
            pageNumber++
            activePage = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
            canvas = activePage!!.canvas
            canvas.drawColor(Color.WHITE)
            paint.color = navy; paint.textSize = 19f; paint.isFakeBoldText = true
            canvas.drawText("DEFESA CIVIL • SAPUCAIA DO SUL", LEFT, 35f, paint)
            paint.color = orange; canvas.drawRect(LEFT, 45f, RIGHT, 48f, paint)
            y = 75f
            paint.isFakeBoldText = false
        }
        fun text(label: String, value: String, size: Float = 12f) {
            paint.color = navy; paint.textSize = size; paint.isFakeBoldText = label.isNotBlank()
            if (y > 755f) page()
            if (label.isNotBlank()) {
                canvas.drawText(label, LEFT, y, paint); y += 17f
            }
            paint.isFakeBoldText = false; paint.color = Color.rgb(55, 64, 78)
            val maxWidth = RIGHT - LEFT
            for (paragraph in value.split('\n')) {
                var line = ""
                for (word in paragraph.split(' ')) {
                    val next = if (line.isEmpty()) word else "$line $word"
                    if (paint.measureText(next) > maxWidth && line.isNotBlank()) {
                        if (y > 770f) page()
                        canvas.drawText(line, LEFT, y, paint); y += 18f
                        line = word
                    } else line = next
                }
                if (y > 770f) page()
                canvas.drawText(line, LEFT, y, paint); y += 18f
            }
            y += 9f
        }
        fun signature(label: String, path: String) {
            if (path.isBlank()) { text(label, "Sem assinatura"); return }
            val bitmap = BitmapFactory.decodeFile(path) ?: run { text(label, "Imagem indisponível"); return }
            if (y + 115 > 770) page()
            paint.color = navy; paint.textSize = 12f; paint.isFakeBoldText = true
            canvas.drawText(label, LEFT, y, paint); paint.isFakeBoldText = false; y += 10
            canvas.drawBitmap(bitmap, null, android.graphics.RectF(LEFT, y, LEFT + 235f, y + 85f), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            y += 96f
            bitmap.recycle()
        }
        try {
            page()
            text("BOLETIM DE ATENDIMENTO", "Protocolo: ${o.number}  |  Data: ${o.date}", 13f)
            text("CLASSIFICAÇÃO", "${o.category} • ${o.tipo} • ${o.prioridade}")
            text("SOLICITANTE", "${o.solicitante} | Telefone: ${o.telefone}")
            text("ENDEREÇO", "${o.rua} — ${o.bairro}")
            text("EQUIPE", "${o.equipe} | Agentes adicionais: ${r.outrosAgentes}")
            text("DESCRIÇÃO INICIAL", o.descricao)
            text("HISTÓRICO / CONSTATAÇÃO", r.historico)
            text("PROVIDÊNCIAS ADOTADAS", r.providencias)
            text("RESPONSÁVEL NO LOCAL", "${r.responsavel}  | Documento: ${r.responsavelDocumento}")
            text("AGENTE RESPONSÁVEL", "${r.agenteNome} — Matrícula ${r.agenteMatricula}")
            if (r.gps.isNotBlank()) text("LOCALIZAÇÃO", r.gps)
            signature("ASSINATURA DO PRIMEIRO AGENTE", r.agenteSignature)
            signature("ASSINATURA DO RESPONSÁVEL NO LOCAL", r.responsavelSignature)
            if (photos.isNotEmpty()) {
                page()
                text("REGISTRO FOTOGRÁFICO", "${photos.size} imagem(ns) vinculada(s) ao atendimento")
                photos.forEachIndexed { ix, foto ->
                    if (y > 565f) page()
                    val bitmap = BitmapFactory.decodeFile(foto.path) ?: return@forEachIndexed
                    val h = (bitmap.height.toFloat() / bitmap.width * 470f).coerceAtMost(270f)
                    canvas.drawBitmap(bitmap, null, android.graphics.RectF(LEFT, y, LEFT + 470f, y + h), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    y += h + 20f
                    bitmap.recycle()
                    paint.color = navy; paint.textSize = 11f
                    canvas.drawText("Foto ${ix + 1}", LEFT, y, paint)
                    y += 25f
                }
            }
            doc.finishPage(activePage!!)
            activePage = null
            val file = File(FileTools.dir(ctx, o.id), "BA_${o.number}.pdf")
            FileOutputStream(file).use { doc.writeTo(it) }
            return file
        } finally {
            if (activePage != null) { try { doc.finishPage(activePage!!) } catch (_: Exception) {} }
            doc.close()
        }
    }

}
