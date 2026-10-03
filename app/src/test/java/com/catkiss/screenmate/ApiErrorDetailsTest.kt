package com.catkiss.screenmate

import org.junit.Assert.*
import org.junit.Test

class ApiErrorDetailsTest {
    @Test fun googleQuotaReasonAndRetrySurviveWithoutExposingKeys() {
        val key="test-private-key-123"
        val raw="""{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"Quota exceeded; key=$key https://example.com/?key=$key","details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaMetric":"generativelanguage.googleapis.com/generate_content_free_tier_requests","quotaId":"RequestsPerDay","quotaValue":"0","quotaDimensions":{"model":"gemini-test","location":"global","project":"private-project"}}]},{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"31.5s"}]}}"""
        val error=ApiErrorDetails.parse(raw,listOf(key))
        assertEquals(32L,error.retrySeconds)
        assertTrue(error.detail.contains("RequestsPerDay")); assertTrue(error.detail.contains("quotaValue=0"))
        assertTrue(error.detail.contains("gemini-test")); assertFalse(error.detail.contains(key))
        assertFalse(error.detail.contains("https://")); assertFalse(error.detail.contains("private-project"))
    }
    @Test fun nonJsonDoesNotDumpProxyHtmlOrCredentials() {
        val error=ApiErrorDetails.parse("<html>token=private; arbitrary server page</html>",emptyList())
        assertFalse(error.detail.contains("private")); assertNull(error.retrySeconds)
    }
    @Test fun genericProviderErrorsAndMalformedDetailsRemainUsable() {
        val error=ApiErrorDetails.parse("""{"error":{"message":"Too many requests","details":[null,{"retryDelay":"NaNs"}]}}""",emptyList())
        assertTrue(error.detail.contains("Too many requests")); assertNull(error.retrySeconds)
        val failure=ApiFailure(429,error.retrySeconds,error.detail)
        assertTrue(failure.message!!.contains("429")); assertFalse(failure.message!!.contains("正在退避"))
    }
    @Test fun requestHistoryIsBoundedAndKeepsTheLatestFailure() {
        ApiDiagnostics.clear(); repeat(55) { ApiDiagnostics.add("request-$it") }
        val text=ApiDiagnostics.snapshot()
        assertEquals(40,text.lines().size); assertFalse(text.contains("request-0\n")); assertTrue(text.contains("request-54"))
        ApiDiagnostics.clear()
    }
}
