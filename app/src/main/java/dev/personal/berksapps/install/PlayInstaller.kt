package dev.personal.berksapps.install

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import androidx.core.net.toUri
import dev.personal.berksapps.adb.AdbRepository
import dev.personal.berksapps.adb.ShellCommand
import dev.personal.berksapps.catalog.CatalogEntry
import dev.personal.berksapps.shell.TokenProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class InstallResult(
    val label: String,
    val packageName: String,
    val installed: Boolean,
    val changed: Boolean,
    val accepted: Boolean,
    val installerPackageName: String?,
    val initiatingPackageName: String?,
    val message: String,
    val recordSource: String?,
    val needsAdvanced: Boolean,
    val log: String?,
)

data class InstallUi(
    val busy: String? = null,
    val staged: StagedApk? = null,
    val notice: String? = null,
    val waiting: String? = null,
    val result: InstallResult? = null,
)

data class InstalledState(
    val versionName: String?,
    val versionCode: Long,
    val installerPackageName: String?,
    val initiatingPackageName: String?,
) {
    val accepted: Boolean
        get() = AndroidAutoRule.accepts(initiatingPackageName, installerPackageName)
}

class PlayInstaller(context: Context, private val adb: AdbRepository) {
    private val appContext = context.applicationContext
    private val pm = appContext.packageManager
    private val prefs = appContext.getSharedPreferences("install", Context.MODE_PRIVATE)
    private val stager = ApkStager(appContext)
    private val state = MutableStateFlow(InstallUi())
    private val shellPreferred = MutableStateFlow(prefs.getBoolean(KEY_PREFER_SHELL, false))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Mutex()
    private val starting = AtomicBoolean(false)
    private var generation = 0
    private var watcher: Job? = null
    private var sawPause = false
    private var launchedThisProcess = false
    private var awaitingPermission = false
    private var activityRef: WeakReference<Activity>? = null

    val ui: StateFlow<InstallUi> get() = state
    val prefersShell: StateFlow<Boolean> get() = shellPreferred

    suspend fun stageFromUri(uri: Uri, catalog: List<CatalogEntry>) = stage("Reading the file…") {
        stager.fromUri(uri, catalog)
    }

    suspend fun stageFromDownload(entry: CatalogEntry) = stage("Downloading ${entry.name}…") {
        stager.fromDownload(entry)
    }

    fun dismiss() {
        generation++
        watcher?.cancel()
        awaitingPermission = false
        clearPending()
        stager.clear()
        state.value = InstallUi()
    }

    fun installedState(packageName: String): InstalledState? {
        val info = installedInfo(packageName) ?: return null
        val (installer, initiating) = sourceFromPackageManager(packageName)
        return InstalledState(info.versionName, info.longVersionCode, installer, initiating)
    }

    suspend fun install(activity: Activity) {
        if (!starting.compareAndSet(false, true)) return
        try {
            lock.withLock { startInstall(activity) }
        } finally {
            starting.set(false)
        }
    }

    fun onPause() {
        if (loadPending() != null) sawPause = true
    }

    suspend fun onResume(activity: Activity) {
        activityRef = WeakReference(activity)
        if (awaitingPermission) {
            awaitingPermission = false
            if (pm.canRequestPackageInstalls() && state.value.staged != null) {
                install(activity)
                return
            }
        }
        val pending = loadPending() ?: return
        if (!launchedThisProcess) {
            launchedThisProcess = true
            if (isStale(pending)) {
                clearPending()
                return
            }
            generation++
            verify(generation, allowRetry = false)
            return
        }
        if (!sawPause) return
        sawPause = false
        val current = generation
        delay(SETTLE_MS)
        var waited = 0L
        while (waited < CANCEL_GRACE_MS && current == generation && !changedSince(pending)) {
            delay(POLL_MS)
            waited += POLL_MS
        }
        verify(current, allowRetry = true)
    }

    suspend fun checkAgain() {
        val pending = loadPending()
        if (pending != null) {
            verify(generation, allowRetry = !isStale(pending))
            return
        }
        val result = state.value.result ?: return
        lock.withLock {
            state.value = state.value.copy(busy = "Checking…")
            val installed = installedInfo(result.packageName)
            val refreshed = if (installed == null) {
                notInstalled(result.label, result.packageName, null)
            } else {
                describe(result.label, readRecord(result.packageName), true, false, null)
            }
            state.value = state.value.copy(busy = null, result = refreshed)
        }
    }

    private suspend fun stage(message: String, work: () -> StageResult) = lock.withLock {
        generation++
        watcher?.cancel()
        awaitingPermission = false
        clearPending()
        state.value = InstallUi(busy = message)
        val result = withContext(Dispatchers.IO) { work() }
        state.value = when (result) {
            is StageResult.Ready -> InstallUi(staged = result.staged)
            is StageResult.Refused -> InstallUi(notice = result.reason)
        }
    }

    private suspend fun startInstall(activity: Activity) {
        val staged = state.value.staged ?: return
        if (loadPending() != null || state.value.waiting != null || state.value.busy != null) return
        activityRef = WeakReference(activity)
        state.value = state.value.copy(busy = "Starting…", notice = null, result = null)
        val installed = installedInfo(staged.facts.packageName)
        if (installed != null) {
            if (installed.longVersionCode > staged.facts.versionCode) {
                refuse(
                    "A newer ${staged.label} (${installed.versionName}) is already installed. " +
                        "Android does not install an older version over a newer one. " +
                        "Uninstall it first, then install this one."
                )
                return
            }
            val current = ApkStager.signerDigests(installed)
            if (current.isNotEmpty() && current != staged.facts.signerSha256) {
                refuse(
                    "The installed ${staged.label} is signed with a different key. " +
                        "Uninstall it first (its data is lost), then install this one."
                )
                return
            }
        }
        val useShell = prefs.getBoolean(KEY_PREFER_SHELL, false)
        val pending = Pending(
            packageName = staged.facts.packageName,
            label = staged.label,
            lastUpdateBefore = installed?.lastUpdateTime ?: 0L,
            viaShell = useShell,
            retried = false,
            startedAt = System.currentTimeMillis(),
        )
        if (!useShell) {
            launchViaApp(activity, pending)
            return
        }
        val connected = adb.connect()
        if (!stillStaged(staged)) return
        if (!connected) {
            refuse(
                "On this phone the simple way did not keep the Google Play mark, so installs go " +
                    "through the Advanced setup connection. Open the Advanced tab, connect, " +
                    "then tap Install again."
            )
            return
        }
        launchViaShell(activity, pending, staged)
    }

    private fun refuse(reason: String) {
        state.value = state.value.copy(busy = null, waiting = null, notice = reason)
    }

    private fun stillStaged(staged: StagedApk): Boolean =
        state.value.staged === staged && stager.servedFile.exists()

    private fun beginWaiting(pending: Pending, message: String) {
        generation++
        savePending(pending)
        sawPause = false
        launchedThisProcess = true
        state.value = state.value.copy(busy = null, notice = null, result = null, waiting = message)
        val current = generation
        watcher?.cancel()
        watcher = scope.launch {
            val deadline = System.currentTimeMillis() + MAX_PENDING_AGE_MS
            while (System.currentTimeMillis() < deadline) {
                delay(POLL_MS)
                if (current != generation) return@launch
                if (changedSince(pending)) {
                    scope.launch {
                        delay(SETTLE_MS)
                        verify(current, allowRetry = true)
                    }
                    return@launch
                }
            }
        }
    }

    private suspend fun launchViaShell(activity: Activity, pending: Pending, staged: StagedApk?) {
        val target = systemInstallerActivity()
        if (target == null) {
            shellLaunchFailed(activity, pending, "No system package installer takes APK installs on this phone.")
            return
        }
        val needsResultTarget = Build.VERSION.SDK_INT <= 34
        if (needsResultTarget && !ensureInstallPermission(activity)) return
        grantToInstallers(target.packageName)
        val command = try {
            if (needsResultTarget) {
                TokenProvider.offer(activity.window.attributes.token)
                ShellCommand.launchInstallerForResult(
                    appContext.applicationInfo.sourceDir,
                    target.flattenToString(),
                    ApkProvider.URI.toString(),
                    REQUEST_INSTALL,
                )
            } else {
                ShellCommand.launchInstaller(target.flattenToString(), ApkProvider.URI.toString())
            }
        } catch (e: IllegalArgumentException) {
            shellLaunchFailed(activity, pending, "The installer could not be addressed safely.")
            return
        }
        val launched = pending.copy(viaShell = true)
        beginWaiting(launched, WAITING_MESSAGE)
        val current = generation
        val output = adb.run(command)
        if (current != generation || (staged != null && !stillStaged(staged))) return
        val text = output?.text.orEmpty()
        val failed = output == null || !output.ok || text.contains("Error") ||
            (needsResultTarget && text.trim().lines().lastOrNull() != "ok")
        if (failed) {
            shellLaunchFailed(activity, launched, text.ifEmpty { "The shell command did not run." })
        }
    }

    private fun shellLaunchFailed(activity: Activity, pending: Pending, reason: String) {
        generation++
        watcher?.cancel()
        clearPending()
        if (pending.retried) {
            refuse("The system installer could not be started: $reason")
            return
        }
        launchViaApp(activity, pending.copy(retried = true))
    }

    private fun launchViaApp(activity: Activity, pending: Pending) {
        if (!ensureInstallPermission(activity)) return
        val target = systemInstallerActivity()
        grantToInstallers(target?.packageName)
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
        intent.setDataAndType(ApkProvider.URI, ShellCommand.APK_MIME)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        intent.putExtra(Intent.EXTRA_RETURN_RESULT, true)
        intent.putExtra("android.intent.extra.INSTALLER_PACKAGE_NAME", AndroidAutoRule.VENDING)
        intent.putExtra("android.content.pm.extra.VERIFICATION_INSTALLER_PACKAGE", AndroidAutoRule.VENDING)
        intent.putExtra("android.intent.extra.INSTALL_REASON", 1)
        intent.putExtra(Intent.EXTRA_REFERRER_NAME, "android-app://" + AndroidAutoRule.VENDING)
        intent.putExtra(Intent.EXTRA_REFERRER, ("android-app://" + AndroidAutoRule.VENDING).toUri())
        intent.putExtra(
            "android.intent.extra.ORIGINATING_URI",
            ("https://play.google.com/store/apps/details?id=" + pending.packageName).toUri(),
        )
        if (Build.VERSION.SDK_INT >= 34) {
            intent.putExtra("android.content.pm.extra.REQUEST_UPDATE_OWNERSHIP", true)
        }
        if (target != null && hasThirdPartyInstaller(intent)) {
            intent.setPackage(target.packageName)
        }
        beginWaiting(pending.copy(viaShell = false), WAITING_MESSAGE)
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, REQUEST_INSTALL)
        } catch (e: Exception) {
            generation++
            watcher?.cancel()
            clearPending()
            refuse("No system installer handled the request.")
        }
    }

    private suspend fun verify(expectedGeneration: Int, allowRetry: Boolean) = lock.withLock {
        if (expectedGeneration != generation) return@withLock
        val pending = loadPending() ?: return@withLock
        state.value = state.value.copy(busy = "Checking the result…")
        val now = installedInfo(pending.packageName)
        val record = readRecord(pending.packageName)
        val verdict = InstallVerdict.decide(
            installed = now != null,
            lastUpdateBefore = pending.lastUpdateBefore,
            lastUpdateNow = now?.lastUpdateTime ?: 0L,
            accepted = AndroidAutoRule.accepts(record.initiating, record.installer),
        )
        when (verdict) {
            Verdict.NotInstalled ->
                finish(notInstalled(pending.label, pending.packageName, installerLog(pending)))
            Verdict.NothingChanged ->
                finish(describe(pending.label, record, false, false, installerLog(pending)))
            Verdict.Accepted -> {
                rememberPath(pending.viaShell)
                stager.clear()
                finish(describe(pending.label, record, true, false, null), keepStaged = false)
            }
            Verdict.MarkLost -> {
                rememberPath(!pending.viaShell)
                val activity = activityRef?.get()
                val canRetry = allowRetry && !pending.retried && !isStale(pending) &&
                    (pending.viaShell || adb.status.value.paired)
                if (!canRetry || activity == null || now == null) {
                    val offerAdvanced = !pending.viaShell && !pending.retried
                    finish(describe(pending.label, record, true, offerAdvanced, installerLog(pending)))
                    return@withLock
                }
                val next = pending.copy(
                    retried = true,
                    lastUpdateBefore = now.lastUpdateTime,
                    viaShell = !pending.viaShell,
                    startedAt = System.currentTimeMillis(),
                )
                clearPending()
                state.value = state.value.copy(busy = "Trying the other way…")
                if (pending.viaShell) {
                    launchViaApp(activity, next)
                } else if (adb.connect()) {
                    launchViaShell(activity, next, null)
                } else {
                    finish(describe(pending.label, record, true, true, null))
                    return@withLock
                }
                if (loadPending() != null) {
                    state.value = state.value.copy(
                        waiting = "One more step. This phone dropped the Google Play mark the " +
                            "first way, so the installer is opening once more the other way. " +
                            "Confirm the update again.",
                    )
                }
            }
        }
    }

    private fun rememberPath(shell: Boolean) {
        prefs.edit { putBoolean(KEY_PREFER_SHELL, shell) }
        shellPreferred.value = shell
    }

    private fun finish(result: InstallResult, keepStaged: Boolean = true) {
        generation++
        watcher?.cancel()
        clearPending()
        state.value = InstallUi(
            staged = if (keepStaged) state.value.staged else null,
            result = result,
        )
    }

    private fun notInstalled(label: String, packageName: String, log: String?) = InstallResult(
        label = label,
        packageName = packageName,
        installed = false,
        changed = false,
        accepted = false,
        installerPackageName = null,
        initiatingPackageName = null,
        message = "$label is not installed. The install was cancelled or refused.",
        recordSource = null,
        needsAdvanced = false,
        log = log,
    )

    private fun describe(
        label: String,
        record: Record,
        changed: Boolean,
        needsAdvanced: Boolean,
        log: String?,
    ): InstallResult {
        val accepted = AndroidAutoRule.accepts(record.initiating, record.installer)
        return InstallResult(
            label = label,
            packageName = record.packageName,
            installed = true,
            changed = changed,
            accepted = accepted,
            installerPackageName = record.installer,
            initiatingPackageName = record.initiating,
            message = when {
                !changed && accepted ->
                    "$label was not installed this time: the install was cancelled or refused. " +
                        "The version already on the phone is still accepted by Android Auto."
                !changed ->
                    "$label was not installed this time: the install was cancelled or refused. " +
                        "The version already on the phone is not accepted by Android Auto."
                accepted -> "$label is installed, and Android Auto should accept it."
                else ->
                    "$label is installed, but without the Google Play mark. Android Auto will not show it."
            },
            recordSource = record.source,
            needsAdvanced = needsAdvanced,
            log = log,
        )
    }

    private data class Record(
        val packageName: String,
        val installer: String?,
        val initiating: String?,
        val source: String,
    )

    private suspend fun readRecord(packageName: String): Record {
        if (adb.isConnected()) {
            try {
                val output = adb.run(ShellCommand.dumpPackage(packageName))
                if (output != null && output.ok) {
                    val parsed = InstallRecordParser.parse(output.text, packageName)
                    if (parsed != null) {
                        return Record(
                            packageName,
                            parsed.installerPackageName,
                            parsed.initiatingPackageName,
                            "dumpsys package",
                        )
                    }
                }
            } catch (e: IllegalArgumentException) {
                Unit
            }
        }
        val (installer, initiating) = sourceFromPackageManager(packageName)
        return Record(packageName, installer, initiating, "Android's package manager")
    }

    private fun sourceFromPackageManager(packageName: String): Pair<String?, String?> = try {
        val info = pm.getInstallSourceInfo(packageName)
        info.installingPackageName to info.initiatingPackageName
    } catch (e: Exception) {
        null to null
    }

    private suspend fun installerLog(pending: Pending): String? {
        if (!adb.isConnected()) return null
        val since = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
            .format(Date(pending.startedAt - 2000))
        val output = try {
            adb.run(ShellCommand.installerLog(since))
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        val lines = output.text.lines().filter { line ->
            line.isNotBlank() && (
                !line.contains("PackageManager") ||
                    line.contains(pending.packageName) ||
                    line.contains("INSTALL_FAILED")
                )
        }.takeLast(MAX_LOG_LINES).map { if (it.length > 240) it.take(240) + "…" else it }
        return lines.joinToString("\n").ifEmpty { null }
    }

    private fun ensureInstallPermission(activity: Activity): Boolean {
        if (pm.canRequestPackageInstalls()) return true
        generation++
        watcher?.cancel()
        clearPending()
        awaitingPermission = true
        state.value = state.value.copy(
            busy = null,
            waiting = null,
            notice = "Android asks once whether Berk's Apps may install apps. Switch on \"Allow " +
                "from this source\" on the screen that just opened, then come back. The install " +
                "continues by itself.",
        )
        try {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    ("package:" + appContext.packageName).toUri(),
                )
            )
        } catch (e: Exception) {
            awaitingPermission = false
            refuse(
                "Allow \"Install unknown apps\" for Berk's Apps in Android's Settings, then tap " +
                    "Install again."
            )
        }
        return false
    }

    private fun installProbe(): Intent {
        @Suppress("DEPRECATION")
        val probe = Intent(Intent.ACTION_INSTALL_PACKAGE)
        probe.setDataAndType(ApkProvider.URI, ShellCommand.APK_MIME)
        return probe
    }

    private fun systemInstallerActivity(): ComponentName? {
        val handlers = try {
            pm.queryIntentActivities(installProbe(), PackageManager.MATCH_DEFAULT_ONLY)
        } catch (e: Exception) {
            return null
        }
        var best: ComponentName? = null
        var bestRank = Int.MAX_VALUE
        for (handler in handlers) {
            val pkg = handler.activityInfo.packageName
            if (!isSystemApp(pkg)) continue
            val rank = when (pkg) {
                AndroidAutoRule.GOOGLE_INSTALLER -> 0
                AndroidAutoRule.AOSP_INSTALLER -> 1
                else -> 2
            }
            if (rank < bestRank) {
                best = ComponentName(pkg, handler.activityInfo.name)
                bestRank = rank
            }
        }
        return best
    }

    private fun hasThirdPartyInstaller(intent: Intent): Boolean = try {
        pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .any { !isSystemApp(it.activityInfo.packageName) }
    } catch (e: Exception) {
        false
    }

    private fun grantToInstallers(resolved: String?) {
        val targets = listOfNotNull(
            AndroidAutoRule.GOOGLE_INSTALLER,
            AndroidAutoRule.AOSP_INSTALLER,
            AndroidAutoRule.VENDING,
            resolved,
        ).distinct()
        for (target in targets) {
            try {
                appContext.grantUriPermission(
                    target,
                    ApkProvider.URI,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (e: Exception) {
                continue
            }
        }
    }

    private fun isSystemApp(packageName: String): Boolean = try {
        pm.getApplicationInfo(packageName, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
    } catch (e: Exception) {
        false
    }

    private fun installedInfo(packageName: String): PackageInfo? = try {
        pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    } catch (e: Exception) {
        null
    }

    private fun changedSince(pending: Pending): Boolean {
        val now = try {
            pm.getPackageInfo(pending.packageName, 0).lastUpdateTime
        } catch (e: Exception) {
            0L
        }
        return now != pending.lastUpdateBefore
    }

    private fun isStale(pending: Pending): Boolean {
        val age = System.currentTimeMillis() - pending.startedAt
        return age < 0 || age > MAX_PENDING_AGE_MS
    }

    private data class Pending(
        val packageName: String,
        val label: String,
        val lastUpdateBefore: Long,
        val viaShell: Boolean,
        val retried: Boolean,
        val startedAt: Long,
    )

    private fun savePending(pending: Pending) {
        prefs.edit {
            putString(KEY_PENDING_PACKAGE, pending.packageName)
            putString(KEY_PENDING_LABEL, pending.label)
            putLong(KEY_PENDING_BEFORE, pending.lastUpdateBefore)
            putBoolean(KEY_PENDING_VIA_SHELL, pending.viaShell)
            putBoolean(KEY_PENDING_RETRIED, pending.retried)
            putLong(KEY_PENDING_STARTED, pending.startedAt)
        }
    }

    private fun loadPending(): Pending? {
        val pkg = prefs.getString(KEY_PENDING_PACKAGE, null) ?: return null
        return Pending(
            packageName = pkg,
            label = prefs.getString(KEY_PENDING_LABEL, null) ?: pkg,
            lastUpdateBefore = prefs.getLong(KEY_PENDING_BEFORE, 0L),
            viaShell = prefs.getBoolean(KEY_PENDING_VIA_SHELL, false),
            retried = prefs.getBoolean(KEY_PENDING_RETRIED, false),
            startedAt = prefs.getLong(KEY_PENDING_STARTED, 0L),
        )
    }

    private fun clearPending() {
        prefs.edit {
            remove(KEY_PENDING_PACKAGE)
            remove(KEY_PENDING_LABEL)
            remove(KEY_PENDING_BEFORE)
            remove(KEY_PENDING_VIA_SHELL)
            remove(KEY_PENDING_RETRIED)
            remove(KEY_PENDING_STARTED)
        }
    }

    companion object {
        const val REQUEST_INSTALL = 4711
        private const val KEY_PREFER_SHELL = "preferShell"
        private const val KEY_PENDING_PACKAGE = "pendingPackage"
        private const val KEY_PENDING_LABEL = "pendingLabel"
        private const val KEY_PENDING_BEFORE = "pendingBefore"
        private const val KEY_PENDING_VIA_SHELL = "pendingViaShell"
        private const val KEY_PENDING_RETRIED = "pendingRetried"
        private const val KEY_PENDING_STARTED = "pendingStarted"
        private const val SETTLE_MS = 600L
        private const val POLL_MS = 1000L
        private const val CANCEL_GRACE_MS = 3000L
        private const val MAX_PENDING_AGE_MS = 15L * 60 * 1000
        private const val MAX_LOG_LINES = 80
        private const val WAITING_MESSAGE =
            "The system installer is opening. Tap Install or Update. If Play Protect asks, " +
                "choose \"More details\" and then \"Install without scanning\"."
    }
}
