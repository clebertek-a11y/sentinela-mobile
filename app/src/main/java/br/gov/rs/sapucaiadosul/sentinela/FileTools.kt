package br.gov.rs.sapucaiadosul.sentinela

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

object FileTools {
    fun dir(ctx: Context, incidentId: String): File = File(ctx.filesDir, "atendimentos/$incidentId").apply { mkdirs() }

    /** Mantém a imagem original privativamente e gera cópia JPEG de alta qualidade para sincronização. */
    fun importPhoto(ctx: Context, incidentId: String, uri: Uri): Pair<File, File> {
        val folder = File(dir(ctx, incidentId), "fotos").apply { mkdirs() }
        val name = UUID.randomUUID().toString()
        val original = File(folder, "${name}_original.bin")
        ctx.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Não foi possível abrir a imagem" }
            FileOutputStream(original).use { out -> input.copyTo(out) }
        }
        // ImageDecoder respeita a orientação EXIF das fotos em Android moderno.
        val source = ImageDecoder.createSource(ctx.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val size = info.size
            val maxEdge = maxOf(size.width, size.height)
            if (maxEdge > 3000) {
                val ratio = 3000.0 / maxEdge
                decoder.setTargetSize((size.width * ratio).toInt().coerceAtLeast(1),
                    (size.height * ratio).toInt().coerceAtLeast(1))
            }
        }
        val optimized = File(folder, "$name.jpg")
        FileOutputStream(optimized).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) { "Falha na compactação" }
        }
        bitmap.recycle()
        return optimized to original
    }

    /** ZIP autocontido, com manifesto e cópias de fotos e PDF. Nunca apaga originais. */
    fun archive(ctx: Context, incident: Incident, report: Report, photos: List<Photo>, events: List<AuditEvent>): File {
        val zipFile = File(dir(ctx, incident.id), "PACOTE_${incident.number}.zip")
        // Pacote imutável após conclusão; hash estável para reenvio idempotente.
        if (zipFile.exists()) return zipFile
        val meta = JSONObject().apply {
            put("schema", "sentinela-1")
            put("incident_id", incident.id); put("number", incident.number)
            put("date", incident.date); put("category", incident.category)
            put("address", incident.rua); put("neighborhood", incident.bairro)
            put("solicitante", incident.solicitante); put("status", incident.status)
            put("historico", report.historico); put("providencias", report.providencias)
            put("created_at", LocalStore.now())
        }
        val timelinePdf = TimelinePdf.generate(ctx, incident, events)
        ZipOutputStream(FileOutputStream(zipFile)).use { out ->
            fun add(name: String, file: File) {
                if (!file.exists()) return
                out.putNextEntry(ZipEntry(name))
                FileInputStream(file).use { it.copyTo(out) }
                out.closeEntry()
            }
            out.putNextEntry(ZipEntry("manifest.json"))
            out.write(meta.toString(2).toByteArray(Charsets.UTF_8))
            out.closeEntry()
            if (incident.pdfPath.isNotBlank()) add("BA_${incident.number}.pdf", File(incident.pdfPath))
            add("HISTORICO_${incident.number}.pdf", timelinePdf)
            photos.forEachIndexed { index, photo ->
                add("FOTO_${(index + 1).toString().padStart(2, '0')}.jpg", File(photo.path))
            }
        }
        return zipFile
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { stream ->
            val buf = ByteArray(8192)
            while (true) { val read = stream.read(buf); if (read < 0) break; digest.update(buf, 0, read) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
