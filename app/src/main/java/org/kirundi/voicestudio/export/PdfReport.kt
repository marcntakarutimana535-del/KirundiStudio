package org.kirundi.voicestudio.export

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

/** Petit générateur de PDF (A4, texte seulement) sans bibliothèque externe. */
class PdfReport {
    private val doc = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var pageNo = 0
    private var y = 0f
    private val width = 595
    private val height = 842
    private val margin = 40f

    private val normal = Paint().apply { textSize = 10f; color = Color.BLACK; isAntiAlias = true }
    private val bold = Paint(normal).apply { typeface = Typeface.DEFAULT_BOLD }
    private val big = Paint(bold).apply { textSize = 16f }
    private val head = Paint(bold).apply { textSize = 12f }
    private val mono = Paint(normal).apply { typeface = Typeface.MONOSPACE; textSize = 8f }
    private val warn = Paint(bold).apply { color = Color.rgb(170, 0, 0) }

    private fun newPage() {
        finishPage()
        pageNo += 1
        page = doc.startPage(PdfDocument.PageInfo.Builder(width, height, pageNo).create())
        y = margin
    }

    private fun finishPage() {
        val p = page
        if (p != null) doc.finishPage(p)
        page = null
    }

    private fun write(text: String, paint: Paint) {
        val lineHeight = paint.textSize * 1.4f
        for (raw in text.split("\n")) {
            var rest = raw
            if (rest.isEmpty()) {
                y += lineHeight
                continue
            }
            while (rest.isNotEmpty()) {
                val n = paint.breakText(rest, true, width - 2 * margin, null)
                if (n <= 0) break
                var cut = n
                if (n < rest.length) {
                    val sp = rest.lastIndexOf(' ', n - 1)
                    if (sp > 0) cut = sp
                }
                val seg = rest.substring(0, cut).trimEnd()
                if (page == null || y + lineHeight > height - margin) newPage()
                page!!.canvas.drawText(seg, margin, y + paint.textSize, paint)
                y += lineHeight
                rest = rest.substring(cut).trimStart()
            }
        }
    }

    fun title(t: String) { write(t, big); space(4f) }
    fun heading(t: String) { space(6f); write(t, head) }
    fun boldLine(t: String) { write(t, bold) }
    fun line(t: String) { write(t, normal) }
    fun monoLine(t: String) { write(t, mono) }
    fun warning(t: String) { write(t, warn) }
    fun space(h: Float) { y += h }

    fun save(out: OutputStream) {
        if (page == null && pageNo == 0) newPage()
        finishPage()
        doc.writeTo(out)
        doc.close()
    }
}
