package com.catkiss.screenmate

/** Stabilize OCR before recording. This is observed text, never a claim of complete dialogue. */
class DialogueTracker {
    data class Line(val text: String, val extension: Boolean)
    private var candidate = ""
    private var candidateSince = 0L
    private var emitted = ""
    private var blankSince: Long? = null
    fun reset() { candidate=""; emitted=""; blankSince=null }
    fun accept(raw: String, now: Long): Line? {
        val text=raw.lines().map(String::trim).filter(String::isNotEmpty).joinToString("\n").take(6000)
        val key=text.filterNot(Char::isWhitespace)
        if(key.length<2) {
            candidate=""
            if(blankSince==null) blankSince=now
            if(now-blankSince!!>=2000) emitted=""
            return null
        }
        blankSince=null
        if(key!=candidate) { candidate=key; candidateSince=now; return null }
        if(now-candidateSince<650 || key==emitted) return null
        // Brief OCR regressions of a growing sentence are not new dialogue.
        if(emitted.startsWith(key)) return null
        val extension=emitted.isNotEmpty() && key.startsWith(emitted)
        emitted=key
        return Line(text,extension)
    }
}
