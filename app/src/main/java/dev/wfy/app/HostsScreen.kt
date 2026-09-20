package dev.wfy.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay

@Composable fun HostsSettings(vm: WorkspaceModel) {
    val discovery = vm.discovery
    var relay by remember { mutableStateOf(discovery.hosts.firstOrNull()?.relay ?: vm.config.relayUrl) }
    var phrase by remember { mutableStateOf("") }
    var invitation by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var add by remember { mutableStateOf(discovery.hosts.isEmpty()) }
    var forget by remember { mutableStateOf<PairedHost?>(null) }
    var inputError by remember { mutableStateOf<String?>(null) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { value ->
            runCatching { readPairingInvitation(value) }.onSuccess { (address, code) ->
                relay = address; phrase = code; add = true; inputError = null
                discovery.pair(address, code) { phrase = ""; invitation = ""; add = false }
            }.onFailure { inputError = it.message }
        }
    }
    LaunchedEffect(Unit) { while (true) { discovery.refresh(); delay(15000) } }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Your laptops", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        IconButton(onClick = { discovery.refresh() }, enabled = !discovery.loading) { Icon(Icons.Outlined.Refresh, "Refresh hosts") }
        IconButton(onClick = { add = !add }, enabled = !discovery.pairing) { Icon(if (add) Icons.Outlined.Close else Icons.Outlined.Add, if (add) "Close pairing" else "Pair a laptop") }
    }
    Text("Pair once. Open the same workspace wherever you take your phone.", color = Muted, fontSize = 14.sp)
    if (discovery.loading || discovery.pairing) LinearProgressIndicator(Modifier.fillMaxWidth())
    (inputError ?: discovery.error)?.let { Text(it, color = Warm, fontSize = 14.sp) }
    if (add) CardBlock {
        Text("Add a laptop", fontWeight = FontWeight.SemiBold)
        Text("On your laptop, open Outpost > Pair a phone. Keep hosting running while pairing.", color = Muted, fontSize = 14.sp)
        WorkbenchButton(onClick = { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the QR in Outpost on your laptop").setBeepEnabled(false).setOrientationLocked(false)) }, enabled = !discovery.pairing, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Scan laptop QR") }
        ConfigField("Pairing invitation (optional)", invitation, { invitation = it }, secret = true, help = "Paste Copy pairing invitation from the desktop to fill both fields.")
        if (invitation.isNotBlank()) TextButton(onClick = {
            runCatching { readPairingInvitation(invitation.trim()) }.onSuccess { (address, code) -> relay = address; phrase = code; invitation = ""; inputError = null }.onFailure { inputError = it.message }
        }) { Text("Use invitation") }
        ConfigField("Relay address", relay, { relay = it }, help = "HTTPS domain or relay URL. No SSH ports or API token needed.")
        ConfigField("Pairing code", phrase, { phrase = it }, secret = true)
        WorkbenchButton(onClick = { inputError = null; discovery.pair(relay, phrase) { phrase = ""; invitation = ""; add = false } }, enabled = !discovery.pairing && relay.isNotBlank() && phrase.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(if (discovery.pairing) "Finding laptop…" else "Find and pair laptop") }
    }
    if (discovery.hosts.isNotEmpty()) {
        ConfigField("Find a laptop", query, { query = it })
        val filtered = discovery.hosts.filter { it.name.contains(query, true) || it.relay.contains(query, true) }.sortedWith(compareByDescending<PairedHost> { it.online }.thenBy { it.name.lowercase() })
        if (filtered.isEmpty()) Text("No matching laptops", color = Muted)
        filtered.forEach { host ->
            val current = vm.connected && vm.config.pairedHostId == host.id && vm.config.relayUrl.substringBefore("?") == host.relay.replaceFirst("https://", "wss://") + "/phone"
            CardBlock {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Laptop, null, tint = if (host.online) LinkBlue else Muted)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(host.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(when { current -> "Connected"; host.online -> "Online · ready to connect"; host.error.isNotEmpty() -> host.error; else -> "Offline" }, color = if (host.online) LinkBlue else Muted, fontSize = 13.sp)
                    }
                    IconButton(onClick = { forget = host }, enabled = !vm.busy && !vm.connecting) { Icon(Icons.Outlined.DeleteOutline, "Forget ${host.name}") }
                }
                Text(host.relay.removePrefix("https://"), color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!host.online && host.lastSeen > 0) Text("Last seen ${android.text.format.DateUtils.getRelativeTimeSpanString(host.lastSeen * 1000)}", color = Muted, fontSize = 12.sp)
                WorkbenchOutlinedButton(onClick = { if (current) vm.tab = 0 else vm.connectPairedHost(host) }, enabled = host.online && !vm.busy && !vm.connecting, modifier = Modifier.fillMaxWidth()) { Text(if (current) "Open workspace" else "Connect") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Connect to my last laptop when Outpost opens", Modifier.weight(1f), fontSize = 14.sp)
            Switch(discovery.autoConnect, { discovery.setAutomatic(it) })
        }
    } else if (!add) Text("Pair your laptop to see it here when it is online.", color = Muted)
    TextButton(onClick = { vm.settingsSection = "Connection" }) { Text("Use manual SSH connection") }
    forget?.let { host -> ConfirmDialog("Forget ${host.name}?", "Remove this laptop's saved pairing from this phone. Workspace files stay on the laptop.", "Forget", { forget = null }) {
        if (vm.config.pairedHostId == host.id && vm.config.relayUrl.substringBefore("?") == host.relay.replaceFirst("https://", "wss://") + "/phone") { vm.disconnect(); vm.saveConfig(vm.config.copy(host = "", password = "", token = "", relayUrl = "", relayAccess = "", pairedHostId = "", pairedPublicKey = "", hostLabel = "")) }
        discovery.forget(host); forget = null
    } }
}
