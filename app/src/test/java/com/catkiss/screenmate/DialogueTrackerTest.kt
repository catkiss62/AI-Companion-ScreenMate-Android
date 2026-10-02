package com.catkiss.screenmate

import org.junit.Assert.*
import org.junit.Test

class DialogueTrackerTest {
    @Test fun typewriterMustSettleAndDoesNotRepeat() {
        val t=DialogueTracker()
        assertNull(t.accept("局长\n你",0))
        assertNull(t.accept("局长\n你来了",750))
        assertEquals("局长\n你来了",t.accept("局长\n你来了",1500)!!.text)
        assertNull(t.accept("局长\n你来了",2250))
        assertNull(t.accept("局长\n你来了。",3000))
        assertTrue(t.accept("局长\n你来了。",3750)!!.extension)
        assertNull(t.accept("局长\n你来",4500))
        assertNull(t.accept("局长\n你来",5250))
    }
    @Test fun newPageAndBlankGapAreDistinctButWhitespaceIsNot() {
        val t=DialogueTracker()
        t.accept("局长：你好",0); assertNotNull(t.accept("局长：你好",750))
        assertNull(t.accept("局长： 你好",1500))
        t.accept("海拉：走吧",2250); assertFalse(t.accept("海拉：走吧",3000)!!.extension)
        t.accept("",3750); t.accept("",6000)
        t.accept("海拉：走吧",6750); assertNotNull(t.accept("海拉：走吧",7500))
    }
    @Test fun pausedCandidateCannotCommitOnResume() {
        val t=DialogueTracker(); t.accept("旧对白",0); t.reset()
        assertNull(t.accept("新对白",5000)); assertNotNull(t.accept("新对白",5750))
    }
    @Test fun requestSpacingAndBackoffAreIndependentOfSuccessfulLatency() {
        val p=WatchPolicy(); p.start(); p.requested(1000,15000)
        p.succeeded(); assertFalse(p.mayObserve(15999)); assertTrue(p.mayObserve(16000))
        p.failed(20000); assertEquals(30000L,p.nextVisionAt)
        p.requested(30000,15000); p.failed(31000); assertEquals(51000L,p.nextVisionAt)
    }
}
