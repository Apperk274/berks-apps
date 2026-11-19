package dev.personal.berksapps

import dev.personal.berksapps.install.AndroidAutoRule
import dev.personal.berksapps.install.InstallRecordParser
import dev.personal.berksapps.install.InstallVerdict
import dev.personal.berksapps.install.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallRecordTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader).getResourceAsStream(name)!!.bufferedReader().use { it.readText() }

    @Test
    fun acceptanceRuleMatchesUnknownAuto() {
        val vending = "com.android.vending"
        assertTrue(AndroidAutoRule.accepts("com.google.android.packageinstaller", vending))
        assertTrue(AndroidAutoRule.accepts("com.android.packageinstaller", vending))
        assertTrue(AndroidAutoRule.accepts(vending, vending))
        assertFalse(AndroidAutoRule.accepts("com.android.shell", vending))
        assertFalse(AndroidAutoRule.accepts("com.google.android.packageinstaller", "com.google.android.packageinstaller"))
        assertFalse(AndroidAutoRule.accepts(null, vending))
        assertFalse(AndroidAutoRule.accepts("com.google.android.packageinstaller", null))
        assertFalse(AndroidAutoRule.accepts("dev.personal.berksapps", vending))
    }

    @Test
    fun cancelledUpdateOverAcceptedInstallIsNotSuccess() {
        assertEquals(Verdict.NothingChanged, InstallVerdict.decide(true, 1000L, 1000L, true))
        assertEquals(Verdict.NothingChanged, InstallVerdict.decide(true, 1000L, 1000L, false))
    }

    @Test
    fun verdictOrdering() {
        assertEquals(Verdict.NotInstalled, InstallVerdict.decide(false, 0L, 0L, false))
        assertEquals(Verdict.NotInstalled, InstallVerdict.decide(false, 1000L, 0L, true))
        assertEquals(Verdict.Accepted, InstallVerdict.decide(true, 0L, 2000L, true))
        assertEquals(Verdict.Accepted, InstallVerdict.decide(true, 1000L, 2000L, true))
        assertEquals(Verdict.MarkLost, InstallVerdict.decide(true, 1000L, 2000L, false))
        assertEquals(Verdict.MarkLost, InstallVerdict.decide(true, 0L, 2000L, false))
    }

    @Test
    fun parsesPlayStyleRecord() {
        val record = InstallRecordParser.parse(fixture("dumpsys_play_style.txt"), "dev.personal.autocaster")
        assertNotNull(record)
        assertEquals("com.android.vending", record!!.installerPackageName)
        assertEquals("com.google.android.packageinstaller", record.initiatingPackageName)
        assertEquals("0.1.0", record.versionName)
        assertEquals(1L, record.versionCode)
        assertTrue(record.accepted)
    }

    @Test
    fun parsesAdbSideloadRecord() {
        val record = InstallRecordParser.parse(fixture("dumpsys_adb_sideload.txt"), "dev.personal.autocaster")
        assertNotNull(record)
        assertEquals("com.android.shell", record!!.initiatingPackageName)
        assertFalse(record.accepted)
    }

    @Test
    fun missingPackageGivesNull() {
        assertNull(InstallRecordParser.parse(fixture("dumpsys_play_style.txt"), "dev.personal.other"))
        assertNull(InstallRecordParser.parse("Unable to find package: x", "dev.personal.autocaster"))
    }

    @Test
    fun readsOnlyTheRequestedPackageBlock() {
        val text = """
            Packages:
              Package [dev.personal.first] (1a2b3c):
                versionCode=3 minSdk=26 targetSdk=37
                versionName=3.0
                installerPackageName=com.android.vending
                initiatingPackageName=com.google.android.packageinstaller
              Package [dev.personal.second] (4d5e6f):
                versionCode=9 minSdk=26 targetSdk=37
                versionName=9.0
                installerPackageName=null
        """.trimIndent()
        val first = InstallRecordParser.parse(text, "dev.personal.first")!!
        assertEquals(3L, first.versionCode)
        assertTrue(first.accepted)
        val second = InstallRecordParser.parse(text, "dev.personal.second")!!
        assertEquals(9L, second.versionCode)
        assertNull(second.installerPackageName)
        assertNull(second.initiatingPackageName)
        assertFalse(second.accepted)
    }
}
