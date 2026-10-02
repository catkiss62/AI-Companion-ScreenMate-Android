package com.catkiss.screenmate

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class PolicyTest {
    @Test fun stoppedEpochRejectsLateNetworkResponse() {
        val p=WatchPolicy(); p.start(); val ticket=p.epoch
        assertTrue(p.accepts(ticket)); p.invalidate(); assertFalse(p.accepts(ticket)); p.start(); assertFalse(p.accepts(ticket))
    }
    @Test fun noProactiveCommentsWhenPausedOrStale() {
        val p=WatchPolicy(); p.start()
        assertTrue(p.mayComment(100_000,99_000,true,90_000))
        assertFalse(p.mayComment(100_000,30_000,true,90_000))
        assertFalse(p.mayComment(100_000,110_000,true,90_000))
        p.invalidate(); assertFalse(p.mayComment(100_000,99_000,true,90_000))
    }
    @Test fun userTurnsTakePriorityAndCommentCooldownIsRespected() {
        val p=WatchPolicy(); p.start(); p.user(100_000)
        assertFalse(p.mayComment(120_000,119_000,true,90_000))
        assertTrue(p.mayComment(146_000,145_000,true,90_000))
        p.commented(146_000)
        assertFalse(p.mayComment(200_000,199_000,true,90_000))
        assertTrue(p.mayComment(236_000,235_000,true,90_000))
    }
    @Test fun uninterestingFramesNeverForceASpeech() {
        val p=WatchPolicy(); p.start(); assertFalse(p.mayComment(999_999,999_990,false,30_000))
    }
    @Test fun rateLimitsBackOffAndHonorRetryAfter() {
        val p=WatchPolicy(); p.start(); p.failed(1000,120)
        assertFalse(p.mayObserve(120_999)); assertTrue(p.mayObserve(121_000))
        repeat(20) { p.failed(1000) }; assertEquals(301_000,p.nextVisionAt)
        p.observed(400_000,15_000); assertEquals(415_000,p.nextVisionAt)
    }
    @Test fun whitespaceDoesNotMakeANewSubtitle() { assertTrue(TextBounds.sameEvidence("角色：你好\n世界","角色： 你好世界")) }
    @Test fun credentialsAndUnsafeUrlsAreRejected() {
        assertTrue(Endpoints.validate("https://relay.example/v1/chat/completions"))
        listOf("http://host/v1","https://key@host/v1","https://host/v1?key=secret","https://host/v1#secret","file:///tmp/key","https:///nohost").forEach { assertFalse(it,Endpoints.validate(it)) }
    }
    @Test fun officialModelCannotInjectAUrl() {
        assertTrue(Endpoints.model("gemini-2.5-flash")); assertFalse(Endpoints.model("x:generateContent?key=x")); assertFalse(Endpoints.model("models/test"))
    }
    @Test fun officialAndRelayWireFormatsStaySeparate() {
        val v=Wire.visionBody("look","abc")
        assertEquals("abc",v.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(1).getJSONObject("inlineData").getString("data"))
        assertFalse(v.has("messages"))
        val c=Wire.chatBody("relay","persona","evidence")
        assertEquals("user",c.getJSONArray("messages").getJSONObject(1).getString("role"))
        assertFalse(c.has("contents")); assertFalse(c.getBoolean("stream"))
    }
    @Test fun geminiThoughtPartsAreNotEvidence() {
        val j=JSONObject("""{"candidates":[{"content":{"parts":[{"text":"private","thought":true},{"text":"visible"}]}}]}""")
        assertEquals("visible",Wire.geminiText(j))
    }
    @Test fun malformedObservationDoesNotBecomeSpeech() {
        try { Wire.observation("nice scene"); fail() } catch(_: ApiFailure) { }
        try { Wire.observation("{}"); fail() } catch(_: ApiFailure) { }
        val o=Wire.observation("""{"summary":"菜单","ocr":"继续","comment_worthy":false}""")
        assertFalse(o.interesting)
    }
    @Test fun emptyProviderContentIsAnError() {
        try { Wire.chatText(JSONObject("""{"choices":[{"message":{"content":null}}]}""")); fail() } catch(_: ApiFailure) { }
    }
}
