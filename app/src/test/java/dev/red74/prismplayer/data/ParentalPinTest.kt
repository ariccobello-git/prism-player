package dev.red74.prismplayer.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The parental PIN: only a hash is kept, the adult groups are recognised, and a right PIN opens them for a while. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParentalPinTest {
    @Before
    fun fresh() {
        ParentalPin.init(ApplicationProvider.getApplicationContext())
        ParentalPin.clear()
    }

    @Test
    fun onlyFourDigitsAreAPin() {
        assertTrue(ParentalPin.isValid("0042"))
        assertFalse(ParentalPin.isValid("123"))
        assertFalse(ParentalPin.isValid("12345"))
        assertFalse(ParentalPin.isValid("12a4"))
        assertFalse(ParentalPin.set("12"))
        assertFalse(ParentalPin.isSet)
    }

    @Test
    fun theHashDependsOnTheSaltAndNeverHoldsTheDigits() {
        val a = ParentalPin.hash("s1", "1234")
        assertNotEquals(a, ParentalPin.hash("s2", "1234"))
        assertNotEquals(a, ParentalPin.hash("s1", "1235"))
        assertFalse(a.contains("1234"))
        assertEquals(64, a.length)
    }

    @Test
    fun aRightPinChecksAndAWrongOneDoesNot() {
        assertFalse(ParentalPin.check("1234"))
        assertTrue(ParentalPin.set("1234"))
        assertTrue(ParentalPin.isSet)
        assertTrue(ParentalPin.check("1234"))
        assertFalse(ParentalPin.check("4321"))
        assertTrue(ParentalPin.set("9999"))
        assertFalse(ParentalPin.check("1234"))
        assertTrue(ParentalPin.check("9999"))
        ParentalPin.clear()
        assertFalse(ParentalPin.isSet)
        assertFalse(ParentalPin.check("9999"))
    }

    @Test
    fun adultGroupsAreRecognisedAndOthersAreNot() {
        for (name in listOf("XXX", "Adult Movies", "ADULTI", "Canali 18+", "+18 HD", "Porn", "Erotica", "IT | Adulti")) {
            assertTrue(name, ParentalPin.isAdult(name))
        }
        for (name in listOf("Sex and the City", "Sport", "Sussex", "Madulto", "Bambini", "Netflix 2018")) {
            assertFalse(name, ParentalPin.isAdult(name))
        }
    }

    @Test
    fun withoutAPinNothingIsLockedAndAfterARightPinTheGroupsStayOpenForAWhile() {
        assertFalse(ParentalPin.locks("XXX", 0L))
        ParentalPin.set("1234")
        assertTrue(ParentalPin.locks("XXX", 1_000L))
        assertFalse(ParentalPin.locks("Sport", 1_000L))
        ParentalPin.openFor(1_000L)
        assertFalse(ParentalPin.locks("XXX", 1_000L + ParentalPin.OPEN_MS - 1))
        assertTrue(ParentalPin.locks("XXX", 1_000L + ParentalPin.OPEN_MS))
        ParentalPin.closeNow()
        assertTrue(ParentalPin.locks("XXX", 2_000L))
    }
}
