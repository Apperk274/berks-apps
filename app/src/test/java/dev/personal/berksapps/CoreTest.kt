package dev.personal.berksapps

import dev.personal.berksapps.adb.ShellCommand
import dev.personal.berksapps.adb.ShellOutput
import dev.personal.berksapps.core.Hashing
import dev.personal.berksapps.core.Names
import dev.personal.berksapps.core.Versions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    @Test
    fun sha256OfKnownInput() {
        val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(expected, Hashing.sha256("abc".toByteArray()))
        assertEquals(expected, Hashing.sha256("abc".byteInputStream()))
    }

    @Test
    fun digestNormalizationAndComparison() {
        val plain = "ab".repeat(32)
        val colons = plain.chunked(2).joinToString(":").uppercase()
        assertEquals(plain, Hashing.normalizeSha256(colons))
        assertNull(Hashing.normalizeSha256("abcd"))
        assertNull(Hashing.normalizeSha256("zz".repeat(32)))
        assertTrue(Hashing.sameDigest(plain, colons))
        assertFalse(Hashing.sameDigest(plain, "cd".repeat(32)))
        assertFalse(Hashing.sameDigest(null, null))
        assertFalse(Hashing.sameDigest("", ""))
    }

    @Test
    fun versionNameComparison() {
        assertTrue(Versions.compareNames("1.10.0", "1.9.9") > 0)
        assertEquals(0, Versions.compareNames("1.0", "1.0.0"))
        assertEquals(0, Versions.compareNames("v1.2.3", "1.2.3"))
        assertTrue(Versions.compareNames("0.1.0", "0.2.0") < 0)
        assertEquals(0, Versions.compareNames("1.2.3-debug", "1.2.3"))
        assertTrue(Versions.compareNames("2", "1.99") > 0)
    }

    @Test
    fun updateAvailablePrefersVersionCode() {
        assertTrue(Versions.updateAvailable(1, "9.0", 2, "1.0"))
        assertFalse(Versions.updateAvailable(2, "1.0", 2, "1.1"))
        assertFalse(Versions.updateAvailable(3, "1.0", 2, "1.1"))
        assertTrue(Versions.updateAvailable(null, "1.0", 2, "1.1"))
        assertTrue(Versions.updateAvailable(5, "1.0", null, "1.1"))
        assertFalse(Versions.updateAvailable(null, null, 2, "1.1"))
    }

    @Test
    fun nameValidation() {
        assertTrue(Names.isPackageName("dev.personal.autocaster"))
        assertFalse(Names.isPackageName("autocaster"))
        assertFalse(Names.isPackageName("dev.personal.auto caster"))
        assertFalse(Names.isPackageName("dev.personal.x;id"))
        assertFalse(Names.isPackageName("dev..x"))
        assertTrue(Names.isComponentName("com.google.android.packageinstaller/com.android.packageinstaller.InstallStart"))
        assertTrue(Names.isComponentName("a.b/.Main\$Inner"))
        assertFalse(Names.isComponentName("a.b/c/d"))
        assertFalse(Names.isComponentName("a.b/x y"))
        assertFalse(Names.isComponentName("a.b/"))
    }

    @Test
    fun dumpCommandIsQuotedAndCarriesExitMarker() {
        assertEquals(
            "'dumpsys' 'package' 'dev.personal.autocaster' 2>&1; echo __BERKSAPPS_EXIT:\$?",
            ShellCommand.dumpPackage("dev.personal.autocaster").line,
        )
    }

    @Test
    fun installerLaunchCommandMatchesUnknownAuto() {
        val line = ShellCommand.launchInstaller(
            "com.google.android.packageinstaller/com.android.packageinstaller.InstallStart",
            "content://dev.personal.berksapps.apk/install.apk",
        ).line
        assertEquals(
            "'/system/bin/am' 'start' " +
                "'-n' 'com.google.android.packageinstaller/com.android.packageinstaller.InstallStart' " +
                "'-a' 'android.intent.action.INSTALL_PACKAGE' " +
                "'-d' 'content://dev.personal.berksapps.apk/install.apk' " +
                "'-t' 'application/vnd.android.package-archive' " +
                "'--es' 'android.intent.extra.INSTALLER_PACKAGE_NAME' 'com.android.vending' " +
                "'--ez' 'android.intent.extra.NOT_UNKNOWN_SOURCE' 'true' " +
                "'--ei' 'android.intent.extra.INSTALL_REASON' '1' " +
                "'--es' 'android.intent.extra.REFERRER_NAME' 'android-app://com.android.vending' " +
                "2>&1; echo __BERKSAPPS_EXIT:\$?",
            line,
        )
    }

    @Test
    fun resultLauncherCommand() {
        val line = ShellCommand.launchInstallerForResult(
            "/data/app/~~abc-_=/dev.personal.berksapps-xyz==/base.apk",
            "com.google.android.packageinstaller/com.android.packageinstaller.InstallStart",
            "content://dev.personal.berksapps.apk/install.apk",
            7,
        ).line
        assertTrue(line.startsWith("CLASSPATH='/data/app/~~abc-_=/dev.personal.berksapps-xyz==/base.apk' '/system/bin/app_process' '/system/bin' 'dev.personal.berksapps.shell.ResultLauncher' "))
        assertTrue(line.endsWith("'7' 2>&1; echo __BERKSAPPS_EXIT:\$?"))
    }

    @Test
    fun commandsRejectInjection() {
        assertThrows(IllegalArgumentException::class.java) {
            ShellCommand.dumpPackage("dev.personal.x'; reboot; '")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShellCommand.dumpPackage("dev.personal.x\$(reboot)")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShellCommand.launchInstaller("a.b/c;reboot", "content://dev.personal.berksapps.apk/install.apk")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShellCommand.launchInstaller("a.b/.C", "content://x/y' ; reboot")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShellCommand.launchInstallerForResult("/data/app/../x/base.apk", "a.b/.C", "content://x/y", 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShellCommand.launchInstallerForResult("/data/app/x y/base.apk", "a.b/.C", "content://x/y", 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShellCommand.installerLog("11-20 10:00:00.000'; reboot")
        }
    }

    @Test
    fun installerLogCommand() {
        val line = ShellCommand.installerLog("11-20 10:00:00.000").line
        assertTrue(line.startsWith("'logcat' '-d' '-v' 'time' '-t' '11-20 10:00:00.000' 'InstallStart:V' "))
        assertTrue(line.contains("'*:S' 2>&1"))
    }

    @Test
    fun shellOutputParsing() {
        val ok = ShellOutput.parse("2000\n__BERKSAPPS_EXIT:0\n")
        assertEquals("2000", ok.text)
        assertEquals(0, ok.exitCode)
        assertTrue(ok.ok)
        val failed = ShellOutput.parse("Error: Activity class does not exist\r\n__BERKSAPPS_EXIT:1\r\n")
        assertEquals(1, failed.exitCode)
        assertFalse(failed.ok)
        assertTrue(failed.text.startsWith("Error"))
        val cut = ShellOutput.parse("partial output")
        assertNull(cut.exitCode)
        assertFalse(cut.ok)
    }
}
