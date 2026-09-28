package com.example.peertopeer.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.example.peertopeer.app.PeerToPeerController
import com.example.peertopeer.bluetooth.BlePermissions
import com.example.peertopeer.bluetooth.BluetoothReadiness
import com.example.peertopeer.diagnostics.DiagnosticEvent
import com.example.peertopeer.diagnostics.DiagnosticLevel
import com.example.peertopeer.diagnostics.DiagnosticLogger
import com.example.peertopeer.network.model.ChatMessage
import com.example.peertopeer.network.model.MessageStatus
import com.example.peertopeer.network.model.ProtocolType
import com.example.peertopeer.service.PeerToPeerForegroundService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Tab(val label: String, val glyph: String) {
    HOME("Home", "H"),
    PEERS("Peers", "P"),
    CHATS("Chats", "C"),
    GROUPS("Groups", "G"),
    RESEARCH("Research", "R")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerToPeerApp(controller: PeerToPeerController) {
    val state by controller.uiState
    val context = androidx.compose.ui.platform.LocalContext.current
    var permissionRefresh by remember { mutableIntStateOf(0) }
    var readinessRefresh by remember { mutableIntStateOf(0) }
    var currentTab by remember { mutableStateOf(Tab.HOME) }
    var showDiagnostics by remember { mutableStateOf(false) }
    val saveDiagnostics = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) controller.exportDiagnosticsTo(uri)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionRefresh++
        result.forEach { (permission, granted) ->
            if (granted) DiagnosticLogger.success("PERMISSION", "Granted: ${permission.substringAfterLast('.')}")
            else DiagnosticLogger.warning("PERMISSION", "Denied: ${permission.substringAfterLast('.')}")
        }
    }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        readinessRefresh++
        if (BluetoothReadiness.isBluetoothEnabled(context)) {
            DiagnosticLogger.success("BLUETOOTH", "Bluetooth enabled by user")
        } else {
            DiagnosticLogger.warning("BLUETOOTH", "Bluetooth remains off")
        }
    }

    val locationSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        readinessRefresh++
        if (BluetoothReadiness.areLocationServicesReady(context)) {
            DiagnosticLogger.success("LOCATION", "System Location Services enabled for BLE scanning")
        } else {
            DiagnosticLogger.warning("LOCATION", "System Location Services remain off")
        }
    }

    if (state.identity.displayName.isBlank()) {
        ProfileSetupScreen(
            onContinue = controller::setDisplayName
        )
        return
    }

    @Suppress("UNUSED_VARIABLE")
    val refresh = permissionRefresh + readinessRefresh
    val hasBlePermissions = BlePermissions.hasRequiredBlePermissions(context)
    val hasNotifications = BlePermissions.hasNotificationPermission(context)
    val bluetoothEnabled = BluetoothReadiness.isBluetoothEnabled(context)
    val locationServicesReady = BluetoothReadiness.areLocationServicesReady(context)

    if (!hasBlePermissions) {
        PermissionSetupScreen(
            notificationNeeded = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            onRequest = {
                DiagnosticLogger.info("PERMISSION", "Requesting permissions for Android API ${Build.VERSION.SDK_INT}")
                permissionLauncher.launch(BlePermissions.permissionsForOnboarding())
            }
        )
        return
    }

    if (!state.backgroundServiceEnabled) {
        DeviceReadyScreen(
            controller = controller,
            notificationsEnabled = hasNotifications,
            bluetoothEnabled = bluetoothEnabled,
            locationServicesReady = locationServicesReady,
            onEnableBluetooth = {
                DiagnosticLogger.info("BLUETOOTH", "Requesting Bluetooth enable")
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            },
            onOpenLocationSettings = {
                DiagnosticLogger.info("LOCATION", "Opening system Location Services settings")
                locationSettingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            },
            onStart = {
                if (!BluetoothReadiness.isBluetoothEnabled(context)) {
                    DiagnosticLogger.warning("STARTUP", "Start blocked: Bluetooth is off")
                } else if (!BluetoothReadiness.areLocationServicesReady(context)) {
                    DiagnosticLogger.warning("STARTUP", "Start blocked: Location Services are off on this Android version")
                } else {
                    DiagnosticLogger.info("STARTUP", "User started Peer2Peer network")
                    PeerToPeerForegroundService.start(context)
                }
            },
            onRequestNotifications = {
                BlePermissions.notificationPermission()?.let { permissionLauncher.launch(arrayOf(it)) }
            }
        )
        return
    }

    if (showDiagnostics) {
        DiagnosticsScreen(controller = controller, onBack = { showDiagnostics = false })
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Peer2Peer NU", fontWeight = FontWeight.Bold)
                        Text(
                            text = if (state.bleStatus.equals("Stopped", true)) "Offline" else "Local mesh active",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    TextButton(onClick = { showDiagnostics = true }) { Text("Logs") }
                    TextButton(onClick = { saveDiagnostics.launch("peer2peer-diagnostics.txt") }) { Text("Export") }
                    Spacer(Modifier.width(4.dp))
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = {
                            currentTab = tab
                            if (tab != Tab.CHATS) controller.selectPeer(null)
                            if (tab != Tab.GROUPS) controller.selectGroup(null)
                        },
                        icon = {
                            Surface(
                                shape = CircleShape,
                                color = if (currentTab == tab) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                            ) {
                                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                                    Text(tab.glyph, fontWeight = FontWeight.Bold)
                                }
                            }
                        },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).padding(horizontal = 8.dp, vertical = 4.dp)) {
            val panelShape = RoundedCornerShape(26.dp)
            Box(Modifier.fillMaxSize().clip(panelShape).background(MaterialTheme.colorScheme.surface, panelShape)) {
                when (currentTab) {
                    Tab.HOME -> HomeScreen(controller, onLogs = { showDiagnostics = true })
                    Tab.PEERS -> SavedPeersScreen(
                        controller = controller,
                        onOpenChat = { id ->
                            controller.selectPeer(id)
                            currentTab = Tab.CHATS
                        }
                    )
                    Tab.CHATS -> ChatsScreen(controller)
                    Tab.GROUPS -> GroupsScreen(controller)
                    Tab.RESEARCH -> ResearchScreen(controller)
                }
            }
        }
    }
}

@Composable
private fun ProfileSetupScreen(onContinue: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val submit = {
        val cleaned = name.trim()
        if (cleaned.isNotBlank()) {
            focusManager.clearFocus(force = true)
            onContinue(cleaned)
        }
    }

    Surface(Modifier.fillMaxSize(), color = Color(0xFFE6F7FF)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(220.dp),
                shape = RoundedCornerShape(bottomStart = 56.dp, bottomEnd = 56.dp),
                color = Color(0xFF12ADE8)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("Register", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(22.dp))
                    Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.35f)) {
                        Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
                            Text(name.trim().take(2).uppercase().ifBlank { "P2P" }, color = Color.White, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 34.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(24) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Your name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() })
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = submit,
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Register") }
            }
        }
    }
}

@Composable
private fun PermissionSetupScreen(notificationNeeded: Boolean, onRequest: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("Bluetooth setup", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Peer2Peer NU uses Bluetooth to discover devices, exchange messages and relay packets across the local peer network.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    PermissionRow("Nearby devices", "Android requires Bluetooth Scan, Connect and Advertise access")
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    PermissionRow("Location permission", "Android 6–11 requires this for BLE scanning; Peer2Peer NU does not read GPS coordinates")
                }
                else -> {
                    PermissionRow("Bluetooth access", "Granted at install time on Android 5.x; there is no runtime Bluetooth permission prompt")
                }
            }
            if (notificationNeeded) {
                PermissionRow("Notifications", "Used for incoming messages while the app is in the background")
            } else {
                PermissionRow("Notifications", "Available automatically on this Android version")
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRequest, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Allow permissions")
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
                modifier = Modifier.align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PermissionRow(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.Top) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) { Text("✓", fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DeviceReadyScreen(
    controller: PeerToPeerController,
    notificationsEnabled: Boolean,
    bluetoothEnabled: Boolean,
    locationServicesReady: Boolean,
    onEnableBluetooth: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onStart: () -> Unit,
    onRequestNotifications: () -> Unit
) {
    val s by controller.uiState
    val readyToStart = s.bleSupported && bluetoothEnabled && locationServicesReady

    Surface(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(28.dp),
            verticalArrangement = Arrangement.Center
        ) {
            item {
                Text("Device check", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Peer2Peer NU checks both permissions and the current phone settings before starting the local mesh.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))

                CapabilityRow("Bluetooth LE hardware", s.bleSupported, if (s.bleSupported) "Supported" else "Not supported")
                CapabilityRow("Bluetooth", bluetoothEnabled, if (bluetoothEnabled) "On" else "Off — turn it on before starting")
                CapabilityRow("BLE advertising", s.advertisingSupported, if (s.advertisingSupported) "Full relay capability" else "Limited node — advertising unavailable")
                CapabilityRow("Required runtime permissions", true, "Granted for Android API ${Build.VERSION.SDK_INT}")

                if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.R) {
                    CapabilityRow(
                        "System Location Services",
                        locationServicesReady,
                        if (locationServicesReady) "On — required by this Android version for BLE scan results" else "Off — Android may suppress BLE scan results"
                    )
                }

                CapabilityRow("Notifications", notificationsEnabled, if (notificationsEnabled) "Enabled" else "Optional permission not granted")

                if (!bluetoothEnabled) {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onEnableBluetooth, modifier = Modifier.fillMaxWidth()) {
                        Text("Turn on Bluetooth")
                    }
                    Text(
                        "Android shows its own confirmation dialog; Peer2Peer NU does not silently change Bluetooth state.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                if (!locationServicesReady && Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.R) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onOpenLocationSettings, modifier = Modifier.fillMaxWidth()) {
                        Text("Open Location Services")
                    }
                }

                if (!s.advertisingSupported) {
                    Spacer(Modifier.height(12.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(
                            "This phone can still be tested as a limited node if scanning/GATT work, but it cannot be a full symmetric relay when BLE advertising is unsupported by its hardware.",
                            Modifier.padding(14.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                if (!notificationsEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = onRequestNotifications) { Text("Enable notifications") }
                }

                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onStart,
                    enabled = readyToStart,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text(if (readyToStart) "Start Peer2Peer NU" else "Complete device setup")
                }
            }
        }
    }
}

@Composable
private fun CapabilityRow(label: String, ok: Boolean, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "●" else "!", color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HomeScreen(controller: PeerToPeerController, onLogs: () -> Unit) {
    val s by controller.uiState
    val context = androidx.compose.ui.platform.LocalContext.current
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Hi, ${s.identity.displayName}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Your User ID", fontWeight = FontWeight.SemiBold)
                        Text(s.identity.nodeId, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item { SectionTitle("Network") }
        item { StatusCard("Peer service", s.bleStatus, s.backgroundServiceEnabled) }
        item { MetricCard("Advertising", if (s.advertisingSupported) "On" else "Unavailable") }
        item { MetricCard("Visible peers", s.peers.size.toString()) }
        item { MetricCard("Active chats", s.savedPeers.size.toString()) }
        item { MetricCard("Routing protocol", "CARBLE") }
        if (!s.advertisingSupported) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text("Advertising is unsupported on this phone. Use another advertising-capable phone as the formal relay node.", Modifier.padding(14.dp))
                }
            }
        }
        item {
            OutlinedButton(onClick = onLogs, modifier = Modifier.fillMaxWidth()) { Text("Open live diagnostics") }
        }
        item {
            OutlinedButton(
                onClick = { PeerToPeerForegroundService.stop(context) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Pause background network") }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun StatusCard(label: String, value: String, active: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("●", color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.SemiBold)
                Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, subtitle: String? = null) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.Medium)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun MetricLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SavedPeersScreen(controller: PeerToPeerController, onOpenChat: (String) -> Unit) {
    val s by controller.uiState
    val peers = s.peers.sortedWith(
        compareByDescending<com.example.peertopeer.network.model.PeerInfo> { it.isDirect }
            .thenBy { it.hopEstimate ?: Int.MAX_VALUE }
            .thenBy { it.displayName.lowercase() }
    )

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(14.dp))
        Text("Peers", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))

        if (peers.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Looking for peers…", fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(peers, key = { it.nodeId }) { peer ->
                    val routePath = controller.routePathTo(peer.nodeId)
                    val relayName = routePath.getOrNull(1)?.let { relayId ->
                        peers.firstOrNull { it.nodeId == relayId }?.displayName ?: relayId
                    }
                    val routeLabel = when {
                        peer.isDirect -> "Direct"
                        peer.hopEstimate != null && routePath.size > 2 -> "${peer.hopEstimate} hops via $relayName"
                        peer.hopEstimate != null -> "${peer.hopEstimate} hops"
                        else -> "Offline"
                    }
                    Card(
                        onClick = {
                            if (controller.openPeerChat(peer.nodeId)) onOpenChat(peer.nodeId)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Avatar(peer.displayName)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(peer.displayName, fontWeight = FontWeight.SemiBold)
                                Text(
                                    peer.nodeId,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                peer.rssi?.let {
                                    if (peer.isDirect) Text(
                                        "RSSI $it dBm",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (peer.hopEstimate != null || peer.isDirect) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            ) {
                                Text(
                                    routeLabel,
                                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Avatar(name: String) {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    val initials = when {
        words.size >= 2 -> words.take(2).joinToString("") { it.take(1) }
        words.isNotEmpty() -> words.first().take(2)
        else -> "?"
    }.uppercase()
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            Text(initials, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ChatsScreen(controller: PeerToPeerController) {
    val s by controller.uiState
    val selected = s.savedPeers.firstOrNull { it.userId == s.selectedPeerId }

    if (selected != null) {
        ChatDetailScreen(controller, selected.userId, selected.displayName, onBack = { controller.selectPeer(null) })
        return
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Text("Chats", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(s.savedPeers, key = { it.userId }) { peer ->
                val conversation = s.messages.filter { it.peerId == peer.userId }.maxByOrNull { it.createdAtEpochMs }
                Row(
                    Modifier.fillMaxWidth().clickable { controller.selectPeer(peer.userId) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(peer.displayName)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(peer.displayName, fontWeight = FontWeight.SemiBold)
                            conversation?.let { Text(formatTime(it.createdAtEpochMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Text(
                            conversation?.text ?: "Start a conversation",
                            maxLines = 1,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(start = 68.dp))
            }
        }
    }
}

@Composable
private fun ChatDetailScreen(controller: PeerToPeerController, peerId: String, peerName: String, onBack: () -> Unit) {
    val s by controller.uiState
    var text by remember(peerId) { mutableStateOf("") }
    val messages = s.messages.filter { it.peerId == peerId }.sortedBy { it.createdAtEpochMs }
    val network = s.peers.firstOrNull { it.nodeId == peerId }
    val routePath = controller.routePathTo(peerId)
    val relayName = routePath.getOrNull(1)?.let { relayId ->
        s.peers.firstOrNull { it.nodeId == relayId }?.displayName ?: relayId
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Surface(tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹") }
                Avatar(peerName)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(peerName, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            network?.isDirect == true -> "Direct · ${network.transportState}"
                            network?.hopEstimate != null && routePath.size > 2 -> "Via $relayName · ${network.hopEstimate} hops"
                            network?.hopEstimate != null -> "Reachable · ${network.hopEstimate} hops"
                            else -> "Offline · messages will queue"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Text(s.lastDecision, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
        LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No messages yet. Say hello.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(messages, key = { it.messageId }) { message -> MessageBubble(message) }
        }

        Surface(tonalElevation = 4.dp) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(500) },
                    placeholder = { Text("Message") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = text.isNotBlank(),
                    onClick = {
                        controller.sendChat(peerId, text)
                        text = ""
                    },
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(52.dp)
                ) { Text("➤") }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    senderName: String? = null,
    recipientNames: Map<String, String> = emptyMap()
) {
    val outgoingColor = Color(0xFF1267C7)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (message.outgoing) 18.dp else 4.dp,
                bottomEnd = if (message.outgoing) 4.dp else 18.dp
            ),
            color = if (message.outgoing) outgoingColor else MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.widthIn(max = 300.dp).padding(horizontal = 12.dp, vertical = 9.dp)) {
                senderName?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(3.dp))
                }
                Text(message.text, color = if (message.outgoing) Color.White else MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTime(message.createdAtEpochMs), style = MaterialTheme.typography.labelSmall, color = if (message.outgoing) Color.White.copy(alpha = 0.78f) else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (message.outgoing) {
                        Text(messageStatusShort(message), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.9f))
                    }
                }
                if (message.outgoing && message.groupId != null && message.recipientStatuses.isNotEmpty()) {
                    val delivered = message.recipientStatuses.values.count { it == MessageStatus.DELIVERED }
                    Text("$delivered/${message.recipientStatuses.size} members delivered", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.82f))
                    message.recipientStatuses.forEach { (nodeId, status) ->
                        val label = recipientNames[nodeId] ?: nodeId
                        val result = when (status) {
                            MessageStatus.DELIVERED -> "✓ Delivered"
                            MessageStatus.FAILED -> "Failed"
                            MessageStatus.QUEUED -> "Queued"
                            else -> "Sending"
                        }
                        Text("$label: $result", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.82f))
                    }
                }
            }
        }
    }
}

private fun messageStatusShort(message: ChatMessage): String = when (message.status) {
    MessageStatus.QUEUED -> if (message.groupId != null) "Queued to members" else "Queued"
    MessageStatus.SENDING -> "Sending"
    MessageStatus.IN_TRANSIT -> if (message.groupId != null) "Partially delivered" else "In transit · awaiting delivery"
    MessageStatus.DELIVERED -> if (message.groupId != null) "Delivered to all · ✓✓" else "Delivered · ✓✓"
    MessageStatus.RECEIVED -> ""
    MessageStatus.FAILED -> "Failed"
}

private fun formatTime(epochMs: Long): String = runCatching {
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))
}.getOrDefault("")

@Composable
private fun GroupsScreen(controller: PeerToPeerController) {
    val s by controller.uiState
    val selectedGroup = s.groups.firstOrNull { it.groupId == s.selectedGroupId }
    if (selectedGroup != null) {
        GroupChatDetailScreen(controller, selectedGroup, onBack = { controller.selectGroup(null) })
        return
    }

    var createStep by remember { mutableIntStateOf(0) }
    var groupName by remember { mutableStateOf("") }
    var selectedMembers by remember { mutableStateOf(setOf<String>()) }
    var memberSearch by remember { mutableStateOf("") }
    val memberNames = remember(s.peers, s.savedPeers) {
        buildMap {
            s.savedPeers.forEach { put(it.userId, it.displayName) }
            s.peers.forEach { put(it.nodeId, it.displayName) }
        }.filterKeys { it != s.identity.nodeId }
    }

    if (createStep > 0) {
        val filteredMembers = memberNames.entries.filter {
            memberSearch.isBlank() || it.value.contains(memberSearch, ignoreCase = true) || it.key.contains(memberSearch, ignoreCase = true)
        }
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            Surface(color = Color(0xFF1267C7)) {
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { if (createStep == 2) createStep = 1 else createStep = 0 }) {
                        Text("‹", color = Color.White)
                    }
                    Text(if (createStep == 1) "Create Group" else "Add members to group", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            if (createStep == 1) {
                Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(
                        value = groupName,
                        onValueChange = { groupName = it.take(32) },
                        label = { Text("Group name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedButton(onClick = { createStep = 2 }, modifier = Modifier.fillMaxWidth()) {
                        Text("＋  Add members to group (${selectedMembers.size})")
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        enabled = groupName.isNotBlank() && selectedMembers.isNotEmpty(),
                        onClick = {
                            controller.createGroup(groupName, selectedMembers.toList())
                            groupName = ""
                            selectedMembers = emptySet()
                            memberSearch = ""
                            createStep = 0
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) { Text("Create Group") }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(14.dp)) {
                    OutlinedTextField(
                        value = memberSearch,
                        onValueChange = { memberSearch = it.take(40) },
                        placeholder = { Text("Search") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (filteredMembers.isEmpty()) item { Text("No peers available", Modifier.padding(14.dp)) }
                        items(filteredMembers, key = { it.key }) { member ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    selectedMembers = if (member.key in selectedMembers) selectedMembers - member.key else selectedMembers + member.key
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Avatar(member.value)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(member.value, fontWeight = FontWeight.SemiBold)
                                    Text(member.key, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Checkbox(
                                    checked = member.key in selectedMembers,
                                    onCheckedChange = { checked -> selectedMembers = if (checked) selectedMembers + member.key else selectedMembers - member.key }
                                )
                            }
                        }
                    }
                    Button(onClick = { createStep = 1 }, modifier = Modifier.fillMaxWidth(), enabled = selectedMembers.isNotEmpty()) {
                        Text("Add ${selectedMembers.size} members")
                    }
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Groups", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Button(onClick = { createStep = 1 }) { Text("New") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (s.groups.isEmpty()) {
                item { Text("No groups yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(s.groups, key = { it.groupId }) { group ->
                val latest = s.messages.filter { it.groupId == group.groupId }.maxByOrNull { it.createdAtEpochMs }
                Card(Modifier.fillMaxWidth().clickable { controller.selectGroup(group.groupId) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(group.name)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(group.name, fontWeight = FontWeight.Bold)
                                latest?.let { Text(formatTime(it.createdAtEpochMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            Text("${group.memberNodeIds.size} members", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(latest?.text ?: "No messages yet", maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupChatDetailScreen(controller: PeerToPeerController, group: com.example.peertopeer.network.model.PeerGroup, onBack: () -> Unit) {
    val s by controller.uiState
    var text by remember(group.groupId) { mutableStateOf("") }
    var showAddMembers by remember { mutableStateOf(false) }
    var membersToAdd by remember { mutableStateOf(setOf<String>()) }
    val messages = s.messages.filter { it.groupId == group.groupId }.sortedBy { it.createdAtEpochMs }
    val isOwner = group.ownerNodeId.isBlank() || group.ownerNodeId == s.identity.nodeId

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Surface(tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹") }
                Avatar(group.name)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(group.name, fontWeight = FontWeight.Bold)
                    Text("${group.memberNodeIds.size} members · offline mesh group", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (isOwner) TextButton(onClick = { showAddMembers = true }) { Text("Add") }
                TextButton(onClick = { controller.deleteGroup(group.groupId); onBack() }) { Text("Leave") }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            if (messages.isEmpty()) item {
                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No messages yet. Start the group chat.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(messages, key = { "${it.groupId}-${it.messageId}" }) { message ->
                val senderName = if (message.outgoing) null else s.peers.firstOrNull { it.nodeId == message.senderId }?.displayName ?: message.senderId
                val names = buildMap {
                    s.savedPeers.forEach { put(it.userId, it.displayName) }
                    s.peers.forEach { put(it.nodeId, it.displayName) }
                }
                MessageBubble(message, senderName, names)
            }
        }

        Surface(tonalElevation = 4.dp) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(value = text, onValueChange = { text = it.take(500) }, placeholder = { Text("Message group") }, modifier = Modifier.weight(1f), maxLines = 4, shape = RoundedCornerShape(24.dp))
                Spacer(Modifier.width(8.dp))
                Button(enabled = text.isNotBlank(), onClick = { controller.sendGroup(group.groupId, text); text = "" }, shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(52.dp)) { Text("➤") }
            }
        }
    }

    if (showAddMembers) {
        val eligible = buildMap<String, String> {
            s.savedPeers.forEach { put(it.userId, it.displayName) }
            s.peers.forEach { put(it.nodeId, it.displayName) }
        }.filterKeys { it !in group.memberNodeIds && it != s.identity.nodeId }
        AlertDialog(
            onDismissRequest = { showAddMembers = false },
            title = { Text("Add members") },
            text = {
                Column {
                    if (eligible.isEmpty()) Text("All saved peers are already members.")
                    eligible.forEach { (nodeId, displayName) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = nodeId in membersToAdd, onCheckedChange = { checked -> membersToAdd = if (checked) membersToAdd + nodeId else membersToAdd - nodeId })
                            Text(displayName)
                        }
                    }
                }
            },
            confirmButton = {
                Button(enabled = membersToAdd.isNotEmpty(), onClick = {
                    controller.addGroupMembers(group.groupId, membersToAdd)
                    membersToAdd = emptySet()
                    showAddMembers = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddMembers = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ResearchScreen(controller: PeerToPeerController) {
    val state by controller.uiState
    var destinationId by remember { mutableStateOf("") }
    var runLabel by remember { mutableStateOf("R01") }
    var conditionLabel by remember { mutableStateOf("STABLE") }
    val protocol = ProtocolType.CARBLE
    var error by remember { mutableStateOf<String?>(null) }

    val reachablePeers = state.peers.filter { it.hopEstimate != null }
    val conditions = listOf("STABLE", "DIRECT", "TWO_HOP", "MEDIUM_TRANSITION", "DISRUPTION", "RECOVERY")

    LaunchedEffect(reachablePeers.map { it.nodeId }) {
        if (destinationId.isBlank() && reachablePeers.size == 1) {
            destinationId = reachablePeers.first().nodeId
        }
    }

    val readiness = if (destinationId.isNotBlank()) {
        controller.formalExperimentReadiness(destinationId)
    } else {
        "Start all three devices and wait for a ready direct or relay route to the destination."
    }
    val relayCandidates = if (destinationId.isNotBlank()) {
        controller.formalCandidateRelays(destinationId)
    } else emptyList()

    val createCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null && !controller.exportResearchTo(uri)) {
            error = "CSV export failed. Open Logs for details."
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Physical experiment", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "CARBLE routes and stages come from live BLE evidence; test labels never force a route or stage.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Choose a qualification or relay test", fontWeight = FontWeight.Bold)
                    Text("Two phones: choose the nearby peer for direct qualification.")
                    Text("Three devices: verify A ↔ C direct and A ↔ B ↔ C relay delivery in both directions.")
                    Text("The Android 8 tablet can be B; every device can still send, receive, and relay.")
                    Text("Record the topology and condition. A test label never forces a stage.")
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Destination", fontWeight = FontWeight.Bold)
                    if (reachablePeers.isEmpty()) {
                        Text(
                            "No reachable destination yet. Start the same v17 build on the other devices and wait for discovery.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        reachablePeers.forEach { peer ->
                            FilterChip(
                                selected = destinationId == peer.nodeId,
                                onClick = {
                                    if (!state.experimentRunning) {
                                        destinationId = peer.nodeId
                                        error = null
                                    }
                                },
                                label = {
                                    val relay = controller.routePathTo(peer.nodeId).getOrNull(1)?.let { relayId ->
                                        state.peers.firstOrNull { it.nodeId == relayId }?.displayName ?: relayId
                                    }
                                    val route = if (peer.isDirect) "Direct · 1 hop" else if (relay != null) "Via $relay · ${peer.hopEstimate} hops" else "${peer.hopEstimate} hops"
                                    Text("${peer.displayName} · $route")
                                }
                            )
                        }
                    }

                    Text(
                        if (readiness == null) "✓ Ready — destination has a route" else readiness,
                        color = if (readiness == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (relayCandidates.isNotEmpty()) {
                        val names = relayCandidates.joinToString("  /  ") { id ->
                            state.peers.firstOrNull { it.nodeId == id }?.let { "${it.displayName} ($id)" } ?: id
                        }
                        Text("Relay choices: $names", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            val selectedDirect = state.peers.firstOrNull { it.nodeId == destinationId }?.isDirect == true &&
                controller.peerTransportState(destinationId) == "Ready"
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Direct measurement", fontWeight = FontWeight.Bold)
                    Text(
                        "Use this before a formal run. It sends 20 one-hop probes even when Q is weak, so D, T and R are measured without a relay fallback.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.directMeasurementRunning) {
                        LinearProgressIndicator(
                            progress = {
                                if (state.directMeasurementTotal == 0) 0f
                                else state.directMeasurementProgress.toFloat() / state.directMeasurementTotal.toFloat()
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("${state.directMeasurementProgress}/${state.directMeasurementTotal} direct probes", style = MaterialTheme.typography.bodySmall)
                    } else {
                        OutlinedButton(
                            onClick = { controller.startDirectMeasurement(destinationId) },
                            enabled = selectedDirect && !state.experimentRunning,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Run 20 direct probes") }
                        if (!selectedDirect) Text("Select a direct Ready peer. This mode never relays through another phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    state.directMeasurementStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Run settings", fontWeight = FontWeight.Bold)
                    MetricLine("Protocol", "CARBLE")
                    OutlinedTextField(
                        value = runLabel,
                        onValueChange = { runLabel = it.take(20) },
                        label = { Text("Run label") },
                        supportingText = { Text("Example: R01") },
                        singleLine = true,
                        enabled = !state.experimentRunning,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Condition", fontWeight = FontWeight.SemiBold)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        conditions.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.forEach { condition ->
                                    FilterChip(
                                        selected = conditionLabel == condition,
                                        onClick = { if (!state.experimentRunning) conditionLabel = condition },
                                        label = { Text(condition) }
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        "Condition names are labels only. They never change route metrics or confidence in code.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Fixed workload", fontWeight = FontWeight.Bold)
                    MetricLine("Warm-up", "20 s")
                    MetricLine("Traffic", "50 packets · 1 packet/s")
                    MetricLine("Final ACK wait", "up to 55 s")
                    Text(
                        "The 50 packets are research traffic, not 50 chat bubbles.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Live run", fontWeight = FontWeight.Bold)
                    MetricLine("Phase", state.experimentPhase)
                    MetricLine("Generated", "${state.experimentProgress}/${state.experimentTotal}")
                    MetricLine("Delivered / ACK", state.experimentAcked.toString())
                    state.lastExperimentRoute?.takeIf { it.isNotBlank() }?.let { MetricLine("Current route", it) }
                    state.lastExperimentPrimaryHop?.let { MetricLine("Primary relay", it) }
                    state.lastExperimentBackupHop?.let { MetricLine("Backup relay", it) }
                    state.lastExperimentRawStage?.let { MetricLine("Raw Q stage", it) }
                    state.lastExperimentStage?.let { MetricLine("${protocol.displayName()} state", it) }
                    state.lastExperimentHysteresisStatus?.let { MetricLine("Stage gate", it) }
                    state.lastExperimentConfidence?.let { MetricLine("Q", "%.3f".format(it)) }
                    state.lastExperimentComponents?.let { MetricLine("Measured inputs", it) }
                    state.experimentStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

                    if (state.experimentRunning) {
                        LinearProgressIndicator(
                            progress = {
                                if (state.experimentTotal == 0) 0f
                                else state.experimentProgress.toFloat() / state.experimentTotal.toFloat()
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedButton(
                            onClick = controller::stopPhysicalExperiment,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Stop run") }
                    } else {
                        Button(
                            onClick = {
                                val ok = controller.startPhysicalExperiment(
                                    destinationId = destinationId,
                                    protocol = protocol,
                                    runLabel = runLabel,
                                    conditionLabel = conditionLabel
                                )
                                if (!ok) {
                                    error = controller.formalExperimentReadiness(destinationId)
                                        ?: "Unable to start run."
                                }
                            },
                            enabled = readiness == null,
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) { Text("Start ${protocol.displayName()} · 50 packets") }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        if (state.experimentPdrPercent != null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Run result", fontWeight = FontWeight.Bold)
                        MetricLine("PDR", "%.1f%%".format(state.experimentPdrPercent))
                        state.experimentMedianLatencyMs?.let { MetricLine("Median ACK latency", "$it ms") }
                        state.experimentFirstHopSummary?.let { Text("First-hop choices: $it", style = MaterialTheme.typography.bodySmall) }
                        state.experimentStageSummary?.let { Text("States: $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }

        item {
            Button(
                onClick = { createCsv.launch(controller.suggestedResearchFileName()) },
                enabled = !state.experimentRunning && state.researchEvents > 0,
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) { Text("Export CSV to Files") }
        }
        state.lastExportPath?.let { path ->
            item { Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }
        item {
            Text(
                "Each formal run exports its own CSV. It includes raw and applied stages, HOP_ACK delivery samples, ACK RTT, GATT timing, freshness age, stability events, every Q input, recovery activations and one final result row per packet. B is a controlled constant to match the simulation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsScreen(controller: PeerToPeerController, onBack: () -> Unit) {
    val s by controller.uiState
    var filter by remember { mutableStateOf("ALL") }
    val events = DiagnosticLogger.events.toList()
    val filtered = events.filter { filter == "ALL" || it.level.name == filter || it.component == filter }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Live diagnostics") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } },
                actions = { TextButton(onClick = DiagnosticLogger::clear) { Text("Clear") } }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Card {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        DiagnosticLine("Android", "${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
                        DiagnosticLine("Node", s.identity.nodeId)
                        DiagnosticLine("Service", if (s.backgroundServiceEnabled) "RUNNING" else "STOPPED")
                        DiagnosticLine("BLE", s.bleStatus)
                        DiagnosticLine("Advertising", if (s.advertisingSupported) "SUPPORTED" else "UNSUPPORTED")
                        DiagnosticLine("Direct peers", s.peers.count { it.isDirect }.toString())
                        DiagnosticLine("Known nodes", s.peers.size.toString())
                        DiagnosticLine("Protocol", s.selectedProtocol.displayName())
                        DiagnosticLine("Queued messages", s.messages.count { it.status == MessageStatus.QUEUED }.toString())
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Last MM route decision", fontWeight = FontWeight.Bold)
                        DiagnosticLine("Destination", s.liveDecisionDestination ?: "Waiting")
                        DiagnosticLine("Selected route", s.liveMmSelectedRoute ?: "Waiting")
                        DiagnosticLine("MM cost", s.liveMmTotalCost?.let { "%.4f".format(Locale.US, it) } ?: "—")
                        DiagnosticLine("Route Q", s.liveRouteQ?.let { "%.4f".format(Locale.US, it) } ?: "—")
                        s.liveMmReason?.let { Text("Why: $it", style = MaterialTheme.typography.bodySmall) }
                        s.liveMmCandidates.forEach { candidate ->
                            Text(candidate, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Last CARBLE decision", fontWeight = FontWeight.Bold)
                        DiagnosticLine("Stage", s.liveCarbleStage ?: "Waiting")
                        DiagnosticLine("Current-hop Q", s.liveCurrentHopQ?.let { "%.4f".format(Locale.US, it) } ?: "—")
                        DiagnosticLine("Action", s.liveForwardingAction ?: "Waiting")
                        DiagnosticLine("Primary hop", s.livePrimaryHop ?: "—")
                        DiagnosticLine("Backup hop", s.liveBackupHop ?: "—")
                        s.liveCarbleStageReason?.let { Text("Why: $it", style = MaterialTheme.typography.bodySmall) }
                        s.liveQComponents?.let { Text("Q inputs: $it", style = MaterialTheme.typography.bodySmall) }
                        s.liveQueueEvidence?.let { Text("Adaptive queue: $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("ALL", "ERROR", "WARNING", "BLE").forEach { option ->
                        FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option) })
                    }
                }
            }
            if (filtered.isEmpty()) {
                item { Text("No diagnostic events yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(filtered) { event -> DiagnosticEventRow(event) }
        }
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DiagnosticEventRow(event: DiagnosticEvent) {
    val indicator = when (event.level) {
        DiagnosticLevel.INFO -> MaterialTheme.colorScheme.primary
        DiagnosticLevel.SUCCESS -> MaterialTheme.colorScheme.tertiary
        DiagnosticLevel.WARNING -> MaterialTheme.colorScheme.secondary
        DiagnosticLevel.ERROR -> MaterialTheme.colorScheme.error
    }
    Card {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(9.dp).background(indicator, CircleShape).align(Alignment.CenterVertically))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(event.component, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text(formatTime(event.timestampEpochMs), style = MaterialTheme.typography.labelSmall)
                }
                Text(event.message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun ProtocolType.displayName(): String = when (this) {
    ProtocolType.B0 -> "B0"
    ProtocolType.MM -> "MM"
    ProtocolType.TWO_RH -> "2BRH"
    ProtocolType.CARBLE -> "CARBLE"
}
