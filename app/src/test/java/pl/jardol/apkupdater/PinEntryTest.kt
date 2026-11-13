package pl.jardol.apkupdater

import org.junit.Assert.assertEquals
import org.junit.Test

class PinEntryTest {
    @Test fun wrongPinClearsEntryAndAllowsRetry() {
        val entry = PinEntry("012")
        assertEquals(PinEntry.Result.INCOMPLETE, entry.add(0))
        assertEquals(PinEntry.Result.INCOMPLETE, entry.add(1))
        assertEquals(PinEntry.Result.WRONG, entry.add(3))
        assertEquals(0, entry.length)
        assertEquals(PinEntry.Result.INCOMPLETE, entry.add(0))
        assertEquals(PinEntry.Result.INCOMPLETE, entry.add(1))
        assertEquals(PinEntry.Result.MATCH, entry.add(2))
    }

    @Test fun deleteCorrectsPreviousDigit() {
        val entry = PinEntry("01")
        entry.add(9)
        entry.delete()
        assertEquals(0, entry.length)
        assertEquals(PinEntry.Result.INCOMPLETE, entry.add(0))
        assertEquals(PinEntry.Result.MATCH, entry.add(1))
    }
}
