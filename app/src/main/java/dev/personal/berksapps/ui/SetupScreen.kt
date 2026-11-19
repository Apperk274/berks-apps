package dev.personal.berksapps.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.personal.berksapps.adb.Link
import dev.personal.berksapps.adb.PairingProgress
import dev.personal.berksapps.adb.PairingService

@Composable
fun SetupScreen(model: AppViewModel, activity: Activity) {
    val setup by model.setup.collectAsStateWithLifecycle()
    val adb by model.adb.status.collectAsStateWithLifecycle()
    val pairing by PairingService.state.collectAsStateWithLifecycle()
    val pairingMessage by model.pairingMessage.collectAsStateWithLifecycle()
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { model.refreshDeviceState() }
    var manualPairing by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    var pairingPort by rememberSaveable { mutableStateOf("") }
    var connectPort by rememberSaveable { mutableStateOf("") }
    var manualConnect by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Advanced setup", style = MaterialTheme.typography.titleLarge)
        Text(
            "Most phones do not need this. Only do it if an install ended with \"Not accepted " +
                "by Android Auto\". It gives Berk's Apps the access a computer gets through " +
                "\"Wireless debugging\", so it can start Android's installer a second way. You " +
                "set it up once, and nothing leaves your phone.",
        )

        Step(
            number = 1,
            title = "Turn on Developer options",
            done = setup.developerOptions,
            body = "Open About phone and tap \"Build number\" seven times. On Samsung it is under " +
                "About phone, then Software information. Then come back here.",
        ) {
            if (!setup.developerOptions) {
                Button(onClick = { open(activity, Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }) {
                    Text("Open About phone")
                }
            }
        }

        Step(
            number = 2,
            title = "Turn on Wireless debugging",
            done = setup.wirelessDebugging == true,
            body = (if (setup.wifi) "" else "Connect to Wi-Fi first: Wireless debugging only works on Wi-Fi (any network; it does not use the internet). ") +
                "In Developer options, find \"Wireless debugging\", switch it on and allow it for " +
                "this network. Android switches it off again after every restart, so repeat this " +
                "step after you restart the phone. You do not need to pair again.",
        ) {
            OutlinedButton(onClick = { openDeveloperOptions(activity) }) { Text("Open Developer options") }
        }

        val needsNotification = !setup.notificationsAllowed
        val needsLocalNetwork = !setup.localNetworkAllowed
        Step(
            number = 3,
            title = "Allow notifications and nearby devices",
            done = !needsNotification && !needsLocalNetwork,
            body = "You type the pairing code into a notification, because the code disappears if " +
                "you leave the Settings screen. Access to devices on the local network lets " +
                "Berk's Apps find Wireless debugging on this same phone.",
        ) {
            if (needsNotification || needsLocalNetwork) {
                Button(onClick = {
                    val wanted = ArrayList<String>()
                    if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
                    if (Build.VERSION.SDK_INT >= AppViewModel.LOCAL_NETWORK_SDK) {
                        wanted += AppViewModel.LOCAL_NETWORK_PERMISSION
                    }
                    permissions.launch(wanted.toTypedArray())
                }) { Text("Allow") }
            }
        }

        Step(
            number = 4,
            title = "Pair Berk's Apps with this phone",
            done = adb.paired,
            body = "1. Tap \"Start pairing\" below.\n" +
                "2. In Wireless debugging, tap \"Pair device with pairing code\". A six-digit " +
                "code appears. Leave that dialog open.\n" +
                "3. Pull down the notifications, tap \"Enter pairing code\" on the Berk's Apps " +
                "notification, type the six digits and send.",
        ) {
            Text(
                when (val p = pairing) {
                    PairingProgress.Idle -> if (adb.paired) "Paired." else "Not paired yet."
                    PairingProgress.Searching -> "Looking for the pairing dialog… open it in Wireless debugging."
                    is PairingProgress.Found -> "Pairing dialog found. Enter the code in the notification."
                    PairingProgress.Working -> "Pairing…"
                    PairingProgress.Paired -> "Paired."
                    is PairingProgress.Failed -> p.reason
                },
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    model.startPairing()
                    openDeveloperOptions(activity)
                }) { Text(if (adb.paired) "Pair again" else "Start pairing") }
                TextButton(onClick = { manualPairing = !manualPairing }) { Text("Type it here instead") }
            }
            if (manualPairing) {
                Text(
                    "Use split screen so the pairing dialog stays open, then type the code. The " +
                        "port is the number after the colon in the dialog; leave it empty to find " +
                        "it automatically.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(code, { code = it.take(6) }, "Code", Modifier.width(120.dp))
                    NumberField(pairingPort, { pairingPort = it.take(5) }, "Port (optional)", Modifier.width(160.dp))
                }
                Button(
                    onClick = { model.pairManually(code, pairingPort.toIntOrNull()) },
                    enabled = code.length == 6,
                ) { Text("Pair") }
                pairingMessage?.let { Text(it) }
            }
        }

        Step(
            number = 5,
            title = "Connect",
            done = adb.link == Link.Connected,
            body = when (adb.link) {
                Link.Connected -> "Connected to Wireless debugging on port ${adb.port ?: "?"}. You can install apps now."
                Link.Connecting -> "Connecting…"
                Link.Disconnected -> adb.message
                    ?: "Berk's Apps connects by itself when Wireless debugging is on. After a restart, do step 2 again and tap Connect."
            },
        ) {
            if (adb.link != Link.Connected) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { model.connect() }, enabled = adb.link != Link.Connecting) { Text("Connect") }
                    TextButton(onClick = { manualConnect = !manualConnect }) { Text("Enter port") }
                }
                if (manualConnect) {
                    Text(
                        "If the automatic search fails, type the port shown in Wireless debugging " +
                            "under \"IP address & Port\" (the number after the colon).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField(connectPort, { connectPort = it.take(5) }, "Port", Modifier.width(140.dp))
                        Button(
                            onClick = { model.connect(connectPort.toIntOrNull()) },
                            enabled = connectPort.toIntOrNull() != null,
                        ) { Text("Connect to port") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Step(
    number: Int,
    title: String,
    done: Boolean,
    body: String?,
    content: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "$number. $title" + if (done) "  ✓" else "",
                style = MaterialTheme.typography.titleMedium,
                color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (body != null) Text(body)
            content()
        }
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter { it.isDigit() }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

private fun open(activity: Activity, intent: Intent) {
    try {
        activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        try {
            activity.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (ignored: Exception) {
            return
        }
    }
}

private fun openDeveloperOptions(activity: Activity) {
    val key = "toggle_adb_wireless"
    val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        .putExtra(":settings:fragment_args_key", key)
        .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", key) })
    open(activity, intent)
}
