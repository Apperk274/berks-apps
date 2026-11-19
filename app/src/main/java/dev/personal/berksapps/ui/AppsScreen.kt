package dev.personal.berksapps.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.personal.berksapps.adb.Link
import dev.personal.berksapps.catalog.CatalogEntry
import dev.personal.berksapps.catalog.CatalogSource
import dev.personal.berksapps.core.Versions
import dev.personal.berksapps.install.InstallResult
import dev.personal.berksapps.install.InstallUi
import dev.personal.berksapps.install.InstalledState
import dev.personal.berksapps.install.StagedApk

private val APK_TYPES = arrayOf("application/vnd.android.package-archive", "application/octet-stream")

@Composable
fun AppsScreen(model: AppViewModel, activity: Activity, openSetup: () -> Unit) {
    val adb by model.adb.status.collectAsStateWithLifecycle()
    val ui by model.installer.ui.collectAsStateWithLifecycle()
    val catalog by model.catalog.collectAsStateWithLifecycle()
    val installed by model.installed.collectAsStateWithLifecycle()
    val loading by model.catalogLoading.collectAsStateWithLifecycle()
    val prefersShell by model.installer.prefersShell.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.stageFile(uri)
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (prefersShell && adb.link != Link.Connected) {
            InfoCard(
                title = if (adb.link == Link.Connecting) "Connecting…" else "This phone needs the advanced setup",
                body = adb.message ?: if (adb.paired) {
                    "Berk's Apps is paired but not connected. Wireless debugging switches off " +
                        "after every restart: turn it on again on the Advanced tab."
                } else {
                    "The simple way of installing did not keep the Google Play mark on this " +
                        "phone. Do the one-time advanced setup, then install again."
                },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = openSetup) { Text("Open advanced setup") }
                    if (adb.paired) {
                        OutlinedButton(onClick = { model.connect() }) { Text("Connect") }
                    }
                }
            }
        }

        InstallPanel(ui, model, activity, openSetup)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Apps", style = MaterialTheme.typography.titleLarge)
                Text(
                    when (catalog.source) {
                        CatalogSource.Online -> "List updated online"
                        CatalogSource.Saved -> "Offline: showing the last list that was downloaded"
                        CatalogSource.Bundled -> "Showing the list built into this app"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = { model.refreshCatalog() }, enabled = !loading) { Text("Refresh") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (catalog.rejected.isNotEmpty()) {
            Text(
                "Ignored online entries whose signing certificate differs from the built-in one: " +
                    catalog.rejected.joinToString(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        for (entry in catalog.entries) {
            EntryCard(
                entry = entry,
                state = installed[entry.packageName],
                busy = ui.busy != null || ui.waiting != null,
                onDownload = { model.download(entry) },
                onPick = { picker.launch(APK_TYPES) },
            )
        }

        OutlinedButton(
            onClick = { picker.launch(APK_TYPES) },
            enabled = ui.busy == null && ui.waiting == null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Install another app from a file…") }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun InstallPanel(ui: InstallUi, model: AppViewModel, activity: Activity, openSetup: () -> Unit) {
    ui.busy?.let { message ->
        InfoCard(title = message, body = null) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
    }
    ui.notice?.let { notice ->
        InfoCard(title = "Can't continue yet", body = notice, error = true) {
            if (ui.staged == null) {
                TextButton(onClick = { model.installer.dismiss() }) { Text("OK") }
            }
        }
    }
    ui.waiting?.let { waiting ->
        InfoCard(title = "Waiting for the system installer", body = waiting) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { model.checkAgain() }) { Text("Check result") }
                TextButton(onClick = { model.installer.dismiss() }) { Text("Cancel") }
            }
        }
    }
    val staged = ui.staged
    if (staged != null && ui.waiting == null && ui.busy == null) {
        StagedCard(staged, onInstall = { model.install(activity) }, onCancel = { model.installer.dismiss() })
    }
    ui.result?.let { result ->
        ResultCard(
            result,
            onCheck = { model.checkAgain() },
            onClose = { model.installer.dismiss() },
            openSetup = openSetup,
        )
    }
}

@Composable
private fun StagedCard(staged: StagedApk, onInstall: () -> Unit, onCancel: () -> Unit) {
    InfoCard(
        title = "Ready to install ${staged.label}",
        body = buildString {
            append(staged.facts.packageName)
            append("\nVersion ").append(staged.facts.versionName ?: "?")
            append(" (").append(staged.facts.versionCode).append(")")
            append("\nSigned by ").append(staged.facts.signerSha256.firstOrNull()?.take(16) ?: "?").append("…")
            append(
                when {
                    staged.entry == null ->
                        "\nThis app is not in the list, so its signature could not be checked " +
                            "against a known one. Only continue if you trust the file."
                    staged.downloaded -> "\nDownload checked: file hash and signing certificate match the list."
                    else -> "\nSigning certificate matches the list."
                }
            )
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onInstall) { Text("Install for Android Auto") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun ResultCard(
    result: InstallResult,
    onCheck: () -> Unit,
    onClose: () -> Unit,
    openSetup: () -> Unit,
) {
    var showLog by remember(result) { mutableStateOf(false) }
    val good = result.installed && result.changed && result.accepted
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (good) Color(0xFF1B5E20) else MaterialTheme.colorScheme.errorContainer,
            contentColor = if (good) Color.White else MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when {
                    !result.installed -> "Not installed"
                    !result.changed -> "Nothing was installed"
                    result.accepted -> "Accepted by Android Auto"
                    else -> "Not accepted by Android Auto"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(result.message)
            if (result.installed) {
                SelectionContainer {
                    Text(
                        "installerPackageName = ${result.installerPackageName}\n" +
                            "initiatingPackageName = ${result.initiatingPackageName}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "Needed: installer com.android.vending and initiator " +
                        "com.google.android.packageinstaller. Read from ${result.recordSource}.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (result.needsAdvanced) {
                Text(
                    "This phone dropped the Google Play mark when Berk's Apps started the " +
                        "installer the simple way. The advanced setup lets it start the " +
                        "installer a second way, which works on such phones. It takes a few " +
                        "minutes, once.",
                )
                Button(onClick = openSetup) { Text("Open advanced setup") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val buttonColors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)
                TextButton(onClick = onCheck, colors = buttonColors) { Text("Check again") }
                if (result.log != null) {
                    TextButton(onClick = { showLog = !showLog }, colors = buttonColors) {
                        Text(if (showLog) "Hide details" else "Show details")
                    }
                }
                TextButton(onClick = onClose, colors = buttonColors) { Text("Close") }
            }
            if (showLog && result.log != null) {
                SelectionContainer {
                    Text(
                        result.log,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryCard(
    entry: CatalogEntry,
    state: InstalledState?,
    busy: Boolean,
    onDownload: () -> Unit,
    onPick: () -> Unit,
) {
    val update = state != null &&
        Versions.updateAvailable(state.versionCode, state.versionName, entry.versionCode, entry.version)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.name, style = MaterialTheme.typography.titleMedium)
            if (entry.description.isNotEmpty()) Text(entry.description)
            Text(
                "${entry.packageName} · version ${entry.version}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                when {
                    state == null -> "Not installed"
                    update -> "Installed: ${state.versionName ?: state.versionCode}. Update available: ${entry.version}"
                    else -> "Installed: ${state.versionName ?: state.versionCode}"
                },
                fontWeight = FontWeight.SemiBold,
            )
            if (state != null) {
                Text(
                    if (state.accepted) {
                        "Android Auto: accepted (installed the Google Play way)"
                    } else {
                        "Android Auto: not accepted. Installer: ${state.installerPackageName ?: "none"}, " +
                            "initiator: ${state.initiatingPackageName ?: "none"}. Install it again from here to fix this."
                    },
                    color = if (state.accepted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (entry.downloadable) {
                    Button(onClick = onDownload, enabled = !busy) {
                        Text(if (state == null) "Download and install" else if (update) "Download update" else "Download again")
                    }
                }
                OutlinedButton(onClick = onPick, enabled = !busy) { Text("Install from file") }
            }
            if (!entry.downloadable) {
                Text(
                    "No download is published for this app yet. Use \"Install from file\" and pick its APK.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
fun InfoCard(
    title: String,
    body: String?,
    error: Boolean = false,
    content: @Composable () -> Unit = {},
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = if (error) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (body != null) Text(body)
            content()
        }
    }
}
