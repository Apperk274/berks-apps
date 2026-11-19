package dev.personal.berksapps.install

data class InstallRecord(
    val packageName: String,
    val installerPackageName: String?,
    val initiatingPackageName: String?,
    val versionName: String?,
    val versionCode: Long?,
) {
    val accepted: Boolean
        get() = AndroidAutoRule.accepts(initiatingPackageName, installerPackageName)
}

object AndroidAutoRule {
    const val VENDING = "com.android.vending"
    const val SHELL = "com.android.shell"
    const val GOOGLE_INSTALLER = "com.google.android.packageinstaller"
    const val AOSP_INSTALLER = "com.android.packageinstaller"

    fun accepts(initiating: String?, installer: String?): Boolean {
        if (initiating == null || installer == null || initiating == SHELL) return false
        return installer == VENDING &&
            (initiating == VENDING || initiating.contains("packageinstaller"))
    }
}

enum class Verdict { NotInstalled, NothingChanged, Accepted, MarkLost }

object InstallVerdict {
    fun decide(
        installed: Boolean,
        lastUpdateBefore: Long,
        lastUpdateNow: Long,
        accepted: Boolean,
    ): Verdict = when {
        !installed -> Verdict.NotInstalled
        lastUpdateNow == lastUpdateBefore -> Verdict.NothingChanged
        accepted -> Verdict.Accepted
        else -> Verdict.MarkLost
    }
}

object InstallRecordParser {
    private val DIGITS = Regex("^[0-9]+")

    fun parse(dumpsys: String, packageName: String): InstallRecord? {
        val header = "Package [$packageName]"
        var inBlock = false
        var blockIndent = -1
        var installer: String? = null
        var initiating: String? = null
        var versionName: String? = null
        var versionCode: Long? = null
        for (raw in dumpsys.lineSequence()) {
            val line = raw.trimEnd('\r')
            val trimmed = line.trim()
            val indent = line.length - line.trimStart().length
            if (!inBlock) {
                if (trimmed.startsWith(header)) {
                    inBlock = true
                    blockIndent = indent
                }
                continue
            }
            if (trimmed.isEmpty()) continue
            if (indent <= blockIndent) break
            when {
                trimmed.startsWith("installerPackageName=") -> installer = value(trimmed)
                trimmed.startsWith("initiatingPackageName=") -> initiating = value(trimmed)
                trimmed.startsWith("versionName=") -> versionName = value(trimmed)
                trimmed.startsWith("versionCode=") ->
                    versionCode = DIGITS.find(trimmed.substringAfter('='))?.value?.toLongOrNull()
            }
        }
        if (!inBlock) return null
        return InstallRecord(packageName, installer, initiating, versionName, versionCode)
    }

    private fun value(line: String): String? {
        val v = line.substringAfter('=').trim()
        return if (v.isEmpty() || v == "null") null else v
    }
}
