package com.kadrava.audio

import com.kadrava.audio.session.TrackCache
import com.kadrava.audio.util.canPlayNow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TrackCacheAndGateTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun cacheFilesAreCreatedReplacedAndRemovedPerTrack() {
        val cache = TrackCache(File(tmp.root, "session"))
        val f1 = cache.prepare(1)
        assertFalse(f1.exists())
        f1.writeBytes(ByteArray(1000))
        cache.prepare(2).writeBytes(ByteArray(500))
        assertEquals(1500L, cache.usedBytes())
        assertFalse(cache.prepare(1).exists())               // preparing again discards the old PCM
        assertEquals(500L, cache.usedBytes())
        cache.delete(2)
        assertEquals(0L, cache.usedBytes())
        cache.prepare(3).writeBytes(ByteArray(10))
        cache.clear()
        assertEquals(0L, cache.usedBytes())
        assertFalse(File(tmp.root, "session").exists())
    }

    @Test fun aGeneratedResultPlaysEvenWhenNothingWasImported() {
        assertFalse(canPlayNow(false, false, false))
        assertTrue(canPlayNow(true, false, false))
        assertTrue("generator-only (Guglielmo) must play", canPlayNow(false, true, false))
        assertTrue(canPlayNow(true, true, false))
        assertFalse(canPlayNow(true, true, true))
        assertFalse(canPlayNow(false, true, true))
    }
}
