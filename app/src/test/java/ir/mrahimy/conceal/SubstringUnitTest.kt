package ir.mrahimy.conceal

import org.junit.Test

import org.junit.Assert.*

class SubstringUnitTest {

    @Test
    fun `check exclusive substring index 1`() {
        val string = "1234"
        val sub12 = string.substring(0, 2)
        assert(sub12 == "12")
    }

    @Test
    fun `check exclusive substring index 2`() {
        val string = "1234"
        val sub12 = string.substring(2, 4)
        assert(sub12 == "34")
    }
}
