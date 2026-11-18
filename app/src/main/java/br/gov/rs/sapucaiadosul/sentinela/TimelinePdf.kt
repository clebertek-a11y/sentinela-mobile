package br.gov.rs.sapucaiadosul.sentinela

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream

/** Registro operacional da movimentação de uma ocorrência, separado do BA. */
object TimelinePdf {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 42f
    private val navy = Color.rgb(20, 40, 73)
    private val orange = Color.rgb(241, 126, 38)

    fun generate(context: Context, incident: Incident, events: List<AuditEvent>): File {
        val file = File(FileTools.dir(context, incident.id), "HISTORICO_${incident.number}.pdf")
        val pdf = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var current: PdfDocument.Page? = null
        lateinit var canvas: Canvas
        var currentPageNumber = 0
        var y = 0f

        fun newPage() {
            current?.let(pdf::finishPage)
            currentPageNumber++
            current = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, currentPageNumber).create())
            canvas = current!!.canvas
            canvas.drawColor(Color.WHITE)
            paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            paint.textSize = 15f
            paint.color = navy
            canvas.drawText("SENTINELA / DEFESA CIVIL - SAPUCAIA DO SUL", MARGIN, 38f, paint)
            paint.color = orange
            canvas.drawRect(MARGIN, 47f, PAGE_WIDTH - MARGIN, 50f, paint)
            paint.typeface = android.graphics.Typeface.DEFAULT
            paint.textSize = 9f
            paint.color = Color.DKGRAY
            canvas.drawText("OCORRÊNCIA: ${incident.number}   |   Página $currentPageNumber", MARGIN, 67f, paint)
            canvas.drawText("Documento gerado a partir dos registros armazenados pelo sistema", MARGIN, PAGE_HEIGHT - 30f, paint)
            y = 95f
        }

        fun paragraph(value: String, bold: Boolean = false, color: Int = navy, size: Float = 11f) {
            paint.color = color
            paint.textSize = size
            paint.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            val width = PAGE_WIDTH - 2 * MARGIN
            for (part in value.split('\n')) {
                var line = ""
                val words = part.split(' ')
                for (word in words) {
                    val next = if (line.isBlank()) word else "$line $word"
                    if (paint.measureText(next) > width && line.isNotBlank()) {
                        if (y + 17 > PAGE_HEIGHT - 62) newPage()
                        canvas.drawText(line, MARGIN, y, paint)
                        y += 17f
                        line = word
                    } else line = next
                }
                if (y + 17 > PAGE_HEIGHT - 62) newPage()
                canvas.drawText(line, MARGIN, y, paint)
                y += 17f
            }
            y += 6f
        }

        try {
            newPage()
            paragraph("REGISTRO CRONOLÓGICO DE MOVIMENTAÇÕES", true, navy, 14f)
            paragraph("Categoria: ${incident.category}   |   Abertura: ${incident.date}")
            paragraph("Local: ${incident.rua} - ${incident.bairro}")
            paragraph("Natureza: ${incident.tipo}   |   Prioridade: ${incident.prioridade}")
            paragraph("Equipe originalmente designada: ${incident.equipe.ifBlank { "Não informada" }}")
            paragraph("Status no momento da geração: ${incident.status}")
            y += 10f
            if (events.isEmpty()) paragraph("Nenhuma movimentação registrada até o momento.")
            events.forEachIndexed { i, event ->
                if (y > PAGE_HEIGHT - 135) newPage()
                val actor = if (event.actorMatricula.isBlank()) event.actorName
                    else "${event.actorName} (matrícula ${event.actorMatricula})"
                paragraph("${i+1}. ${event.at}  |  $actor", true, navy, 10f)
                paragraph(event.action, false, Color.rgb(51, 60, 76), 11f)
                paint.color = Color.rgb(221, 227, 236)
                canvas.drawLine(MARGIN, y - 1f, PAGE_WIDTH - MARGIN, y - 1f, paint)
                y += 7f
            }
            paragraph("Total de registros: ${events.size}  |  Horários conforme relógio do dispositivo de origem", false, Color.DKGRAY, 9f)
            current?.let(pdf::finishPage)
            current = null
            FileOutputStream(file).use(pdf::writeTo)
            return file
        } finally {
            current?.let { runCatching { pdf.finishPage(it) } }
            pdf.close()
        }
    }
}
