package com.example

import com.android.dx.command.UsageException
import com.android.dx.command.dexer.Main
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class J2meDxArgumentsTest {

    @Test
    fun `vendored dexer accepts J2ME core library flags`() {
        val args = Main.Arguments()
        args.parseFlags(arrayOf("--no-optimize", "--core-library", "--output=/tmp/j2me-test.dex"))
        assertFalse(args.optimize)
        assertTrue(args.coreLibrary)
        assertEquals("/tmp/j2me-test.dex", args.outName)
    }

    @Test(expected = UsageException::class)
    fun `vendored dexer rejects unsupported dex flag`() {
        Main.Arguments().parseFlags(arrayOf("--dex"))
    }
}
