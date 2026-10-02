package com.catkiss.screenmate

/** RAM only. No keys, requests, screenshots or OCR are written to diagnostic files. */
object CaptureDiagnostics {
    data class Frame(val jpeg: ByteArray, val capturedAt: Long, val width: Int, val height: Int, val darkPercent: Int)
    var latest: Frame? = null
    var sent: Frame? = null
    var ocrText = ""
    var ocrMillis = 0L
    var visionMillis = 0L
    var replyMillis = 0L
    var dialogueCount = 0
    var visibility = true
    var note = "等待首张截图"
    fun reset() {
        latest=null; sent=null; ocrText=""; ocrMillis=0; visionMillis=0; replyMillis=0
        dialogueCount=0; visibility=true; note="等待首张截图"
    }
}
