package com.catkiss.screenmate

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/** Local bounded diagnostics. Never retain request bodies, headers, keys or full URLs. */
object ApiDiagnostics {
    private val rows=ArrayDeque<String>()
    @Synchronized fun clear() { rows.clear() }
    @Synchronized fun add(text: String) {
        rows.addLast("${SimpleDateFormat("HH:mm:ss.SSS",Locale.CHINA).format(Date())} $text")
        while(rows.size>40) rows.removeFirst()
    }
    @Synchronized fun snapshot() = rows.joinToString("\n").ifBlank { "暂无请求" }
}

data class ProviderError(val detail: String,val retrySeconds: Long?)
object ApiErrorDetails {
    fun redact(text: String, secrets: List<String>): String {
        var out=text
        secrets.filter { it.isNotBlank() }.sortedByDescending { it.length }.forEach { out=out.replace(it,"[密钥已隐藏]") }
        out=out.replace(Regex("https?://[^\\s<>\"']+"),"[链接已隐藏]")
            .replace(Regex("(?i)(?:bearer\\s+)[^\\s,;\"']+"),"Bearer [已隐藏]")
            .replace(Regex("(?i)(?:api[_-]?key|authorization|token|secret)\\s*[:=]\\s*[^\\s,;\"']+"),"凭据=[已隐藏]")
            .replace(Regex("(?:AIza|sk-)[A-Za-z0-9_-]{12,}"),"[密钥已隐藏]")
            .replace(Regex("[\\p{Cntrl}&&[^\\n\\t]]")," ")
        return out.take(2400)
    }
    fun parse(body: String, secrets: List<String>): ProviderError {
        val root=runCatching { JSONObject(body) }.getOrNull()
            ?: return ProviderError("服务端未返回可解析的JSON错误详情",null)
        val error=root.optJSONObject("error") ?: root
        val lines=mutableListOf<String>()
        error.optString("status").takeIf { it.isNotBlank() }?.let { lines.add("状态：$it") }
        error.optString("message").takeIf { it.isNotBlank() }?.let { lines.add("服务端：$it") }
        val details=error.optJSONArray("details")
        var retry: Long?=null
        if(details!=null) for(i in 0 until details.length()) {
            val entry=details.optJSONObject(i) ?: continue
            val delay=entry.optString("retryDelay").removeSuffix("s").toDoubleOrNull()
            if(delay!=null && delay.isFinite() && delay>=0) retry=maxOf(retry ?: 0,ceil(delay).toLong().coerceAtMost(86400))
            val violations=entry.optJSONArray("violations") ?: continue
            for(j in 0 until minOf(violations.length(),8)) {
                val v=violations.optJSONObject(j) ?: continue
                val parts=listOf("quotaMetric","quotaId","quotaValue","description").mapNotNull { key ->
                    v.optString(key).takeIf { it.isNotBlank() }?.let { "$key=$it" }
                }.toMutableList()
                val dims=v.optJSONObject("quotaDimensions")
                listOf("model","location").forEach { key -> dims?.optString(key)?.takeIf { it.isNotBlank() }?.let { parts.add("$key=$it") } }
                if(parts.isNotEmpty()) lines.add("限额："+parts.joinToString("；"))
            }
        }
        if(retry!=null) lines.add("服务端建议等待：${retry}秒")
        return ProviderError(redact(lines.joinToString("\n").ifBlank { "服务端未提供具体限额说明" },secrets),retry)
    }
}
