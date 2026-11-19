package dev.personal.berksapps.adb

import dev.personal.berksapps.core.Names
import dev.personal.berksapps.install.AndroidAutoRule

class ShellCommand private constructor(val line: String) {

    override fun toString(): String = line

    companion object {
        const val EXIT_MARKER = "__BERKSAPPS_EXIT:"
        const val APK_MIME = "application/vnd.android.package-archive"
        const val RESULT_LAUNCHER_CLASS = "dev.personal.berksapps.shell.ResultLauncher"

        private val SAFE_TOKEN = Regex("^[A-Za-z0-9_./:=~+,@%$ *-]+$")
        private val APK_PATH = Regex("^/[A-Za-z0-9_./=~+-]+[.]apk$")
        private val CONTENT_URI = Regex("^content://[A-Za-z0-9_.]+/[A-Za-z0-9_.]+$")
        private val LOGCAT_TIME = Regex("^[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}[.][0-9]{3}$")

        private val INSTALLER_LOG_TAGS = listOf(
            "InstallStart",
            "InstallRepository",
            "InstallStaging",
            "InstallInstalling",
            "InstallLaunch",
            "PackageInstaller",
            "PackageInstallerSession",
            "PackageManager",
        )

        fun identity(): ShellCommand = build(emptyList(), listOf("id", "-u"))

        fun dumpPackage(packageName: String): ShellCommand {
            require(Names.isPackageName(packageName)) { "Invalid package name" }
            return build(emptyList(), listOf("dumpsys", "package", packageName))
        }

        fun launchInstaller(installerComponent: String, apkUri: String): ShellCommand {
            require(Names.isComponentName(installerComponent)) { "Invalid installer component" }
            require(CONTENT_URI.matches(apkUri)) { "Invalid APK URI" }
            return build(
                emptyList(),
                listOf(
                    "/system/bin/am", "start",
                    "-n", installerComponent,
                    "-a", "android.intent.action.INSTALL_PACKAGE",
                    "-d", apkUri,
                    "-t", APK_MIME,
                    "--es", "android.intent.extra.INSTALLER_PACKAGE_NAME", AndroidAutoRule.VENDING,
                    "--ez", "android.intent.extra.NOT_UNKNOWN_SOURCE", "true",
                    "--ei", "android.intent.extra.INSTALL_REASON", "1",
                    "--es", "android.intent.extra.REFERRER_NAME",
                    "android-app://" + AndroidAutoRule.VENDING,
                ),
            )
        }

        fun launchInstallerForResult(
            ownApkPath: String,
            installerComponent: String,
            apkUri: String,
            requestCode: Int,
        ): ShellCommand {
            require(APK_PATH.matches(ownApkPath) && !ownApkPath.contains("..")) { "Invalid APK path" }
            require(Names.isComponentName(installerComponent)) { "Invalid installer component" }
            require(CONTENT_URI.matches(apkUri)) { "Invalid APK URI" }
            require(requestCode in 1..0xffff) { "Invalid request code" }
            return build(
                listOf("CLASSPATH" to ownApkPath),
                listOf(
                    "/system/bin/app_process", "/system/bin", RESULT_LAUNCHER_CLASS,
                    installerComponent, apkUri, requestCode.toString(),
                ),
            )
        }

        fun installerLog(sinceLogcatTime: String): ShellCommand {
            require(LOGCAT_TIME.matches(sinceLogcatTime)) { "Invalid logcat time" }
            val args = ArrayList<String>()
            args += listOf("logcat", "-d", "-v", "time", "-t", sinceLogcatTime)
            for (tag in INSTALLER_LOG_TAGS) args += "$tag:V"
            args += "*:S"
            return build(emptyList(), args)
        }

        private fun build(env: List<Pair<String, String>>, argv: List<String>): ShellCommand {
            val sb = StringBuilder()
            for ((name, value) in env) {
                sb.append(name).append('=').append(quote(value)).append(' ')
            }
            sb.append(argv.joinToString(" ") { quote(it) })
            sb.append(" 2>&1; echo ").append(EXIT_MARKER).append("$?")
            return ShellCommand(sb.toString())
        }

        private fun quote(token: String): String {
            require(SAFE_TOKEN.matches(token)) { "Unsafe shell token" }
            return "'$token'"
        }
    }
}

data class ShellOutput(val text: String, val exitCode: Int?) {
    val ok: Boolean get() = exitCode == 0

    companion object {
        fun parse(raw: String): ShellOutput {
            val at = raw.lastIndexOf(ShellCommand.EXIT_MARKER)
            if (at < 0) return ShellOutput(raw.trimEnd(), null)
            val code = raw.substring(at + ShellCommand.EXIT_MARKER.length)
                .trim().takeWhile { it.isDigit() }.toIntOrNull()
            return ShellOutput(raw.substring(0, at).trimEnd(), code)
        }
    }
}
