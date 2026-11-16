package cd.eifi.l1apbchat

import cd.localchat.app.R
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.bluetooth.BluetoothManager
import android.location.LocationManager
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.nearby.connection.*
import com.google.android.gms.tasks.Task
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

private const val SERVICE_ID = "cd.localchat.app.localchat"
private data class ChatMessage(val name: String, val text: String, val time: Long, val mine: Boolean = false, val delivery: String = "")

class MainActivity : ComponentActivity() {
    private val client by lazy { Nearby.getConnectionsClient(this) }
    private val peers = mutableStateMapOf<String, String>() // nearby endpoint id -> display name
    private val connectedPeers = mutableStateListOf<String>()
    private val messages = mutableStateListOf<ChatMessage>()
    private var status by mutableStateOf("")
    private var isHost = false
    private var hostConnectionReady by mutableStateOf(false)
    private var searchingForRoom by mutableStateOf(false)
    private var lastHostName: String? = null
    private var reconnecting = false
    private var reconnectAttempt = 0
    private var roomAuthenticated by mutableStateOf(false)
    private var roomAccessCode by mutableStateOf("")
    private var reconnectConnectionPending = false
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectTimeout: Runnable? = null
    private val profilePreferences by lazy { getSharedPreferences("student_profile", MODE_PRIVATE) }
    private var nickname by mutableStateOf("Participant")
    private var programName by mutableStateOf("Salon ouvert")
    private var programCode by mutableStateOf("")
    private val displayName: String get() = nickname.trim().ifBlank { getString(R.string.applicant) } + programCode.trim().takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    private fun bluetoothEnabled(): Boolean = try { (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == true } catch (_: Exception) { false }
    private fun locationEnabled(): Boolean = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) true else try {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (_: Exception) { false }
    private fun playServicesAvailable(): Boolean = try { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == com.google.android.gms.common.ConnectionResult.SUCCESS } catch (_: Exception) { false }
    private fun missingPermissions(): List<String> = requiredPermissions().filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

    private fun serviceIdFor(group: String): String {
        val normalized = group.trim().lowercase(Locale.ROOT).ifBlank { "salon ouvert" }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
        return "cd.localchat.app.r$hash"
    }
    private val receivedIds = mutableSetOf<String>()

    private fun requiredPermissions(): Array<String> {
        val p = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            p += Manifest.permission.BLUETOOTH_ADVERTISE
            p += Manifest.permission.BLUETOOTH_CONNECT
            p += Manifest.permission.BLUETOOTH_SCAN
        } else {
            p += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.NEARBY_WIFI_DEVICES
        return p.toTypedArray()
    }

    private fun hasPermissions() = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private val connectionLifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            val previouslySavedHost = lastHostName
            if (!isHost && reconnecting && info.endpointName == previouslySavedHost) {
                client.acceptConnection(id, payloadCallback)
                return
            }
            if (!isHost && !reconnecting) {
                lastHostName = info.endpointName
                profilePreferences.edit().putString("last_host_name", info.endpointName).apply()
            }
            // Le prototype n'accepte que des membres confirmés par l'utilisateur.
            runOnUiThread {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.join_request))
                    .setMessage(getString(R.string.participant_request, info.endpointName, programName))
                    .setNegativeButton(getString(R.string.reject)) { _, _ -> client.rejectConnection(id) }
                    .setPositiveButton(getString(R.string.accept)) { _, _ -> client.acceptConnection(id, payloadCallback) }
                    .show()
            }
        }
        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            runOnUiThread {
                if (result.status.isSuccess) {
                    roomAuthenticated = isHost
                    if (isHost) peers[id] = peers[id] ?: getString(R.string.member_connected)
                    else status = getString(R.string.access_checking)
                    if (id !in connectedPeers) connectedPeers.add(id)
                    searchingForRoom = false
                    reconnecting = false
                    reconnectConnectionPending = false
                    reconnectAttempt = 0
                    reconnectTimeout?.let(reconnectHandler::removeCallbacks)
                    status = if (isHost) getString(R.string.status_connected) else getString(R.string.access_checking)

                } else {
                    peers.remove(id)
                    if (reconnecting) finishReconnectAttempt() else status = getString(R.string.connection_denied)
                }
            }
        }
        override fun onDisconnected(id: String) {
            runOnUiThread {
                peers.remove(id)
                connectedPeers.remove(id)
                if (connectedPeers.isEmpty()) {
                    status = getString(R.string.connection_interrupted)
                    if (!isHost) beginReconnect()
                } else status = if (connectedPeers.size == 1) getString(R.string.connected_one, connectedPeers.size) else getString(R.string.connected_many, connectedPeers.size)
            }
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            peers[id] = info.endpointName
            if (reconnecting && !reconnectConnectionPending && connectedPeers.isEmpty() && info.endpointName == lastHostName) {
                searchingForRoom = false
                reconnectTimeout?.let(reconnectHandler::removeCallbacks)
                client.stopDiscovery()
                reconnectConnectionPending = true
                client.requestConnection(displayName, id, connectionLifecycle)
                    .addOnFailureListener { reconnectConnectionPending = false; finishReconnectAttempt() }
                reconnectTimeout = Runnable {
                    if (reconnecting && connectedPeers.isEmpty()) {
                        reconnectConnectionPending = false
                        finishReconnectAttempt()
                    }
                }
                reconnectHandler.postDelayed(reconnectTimeout!!, 8_000)
            }
        }
        override fun onEndpointLost(id: String) { peers.remove(id) }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, payload: Payload) {
            val raw = payload.asBytes()?.toString(Charsets.UTF_8) ?: return
            try {
                val obj = JSONObject(raw)
                when (obj.optString("type")) {
                    "auth_required" -> {
                        val nonce = obj.optString("nonce")
                        if (nonce.isBlank()) return
                        val proof = authProof(roomAccessCode, nonce)
                        val response = JSONObject().put("type", "auth").put("proof", proof)
                        client.sendPayload(id, Payload.fromBytes(response.toString().toByteArray()))
                        return
                    }
                    "auth_result" -> {
                        val accepted = obj.optBoolean("ok", false)
                        runOnUiThread {
                            roomAuthenticated = accepted
                            status = if (accepted) getString(R.string.access_code_ok) else getString(R.string.access_code_wrong)
                            if (!accepted) {
                                reconnecting = false
                                lastHostName = null
                                profilePreferences.edit().remove("last_host_name").apply()
                                client.disconnectFromEndpoint(id)
                            }
                        }
                        return
                    }
                }
                if (!roomAuthenticated) return
                val mid = obj.optString("id")
                if (mid.isBlank() || !receivedIds.add(mid)) return
                val chatMessage = ChatMessage(
                    name = obj.optString("name", getString(R.string.applicant)),
                    text = obj.optString("text", ""),
                    time = obj.optLong("time", System.currentTimeMillis()),
                    delivery = getString(R.string.received)
                )
                runOnUiThread { messages.add(chatMessage) }
                // Le téléphone hôte relaie le message du membre aux autres appareils.
                if (isHost) {
                    val bytes = payload.asBytes() ?: return
                    connectedPeers.filter { it != id }.forEach { client.sendPayload(it, Payload.fromBytes(bytes)) }
                }
            } catch (_: Exception) { /* message invalide : on l'ignore */ }
        }
        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) = Unit
    }

    private fun authProof(code: String, nonce: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(code.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(nonce.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun sendSystemWelcome(endpointId: String) {
        val obj = JSONObject().put("id", UUID.randomUUID().toString()).put("name", getString(R.string.app_title)).put("text", getString(R.string.room_welcome, programName)).put("time", System.currentTimeMillis())
        client.sendPayload(endpointId, Payload.fromBytes(obj.toString().toByteArray()))
    }

    private fun startHost() {
        if (!hasPermissions()) { status = getString(R.string.permission_prompt); return }
        if (roomAccessCode.isNotBlank() && roomAccessCode.length != 4) { status = getString(R.string.access_code_length); return }
        isHost = true
        roomAuthenticated = true
        profilePreferences.edit().putBoolean("host_active", true).apply()
        status = getString(R.string.group_creating)
        HostConnectionService.start(this, displayName, programName, programCode, roomAccessCode)
    }

    private fun searchGroup() {
        reconnecting = false
        reconnectConnectionPending = false
        reconnectTimeout?.let(reconnectHandler::removeCallbacks)
        reconnectAttempt = 0
        if (!hasPermissions()) { status = getString(R.string.permission_prompt); return }
        isHost = false
        roomAuthenticated = false
        searchingForRoom = true
        status = getString(R.string.search_room, programName)
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        client.startDiscovery(serviceIdFor(programName), discovery, options)
            .addOnFailureListener { searchingForRoom = false; status = getString(R.string.search_failed, it.localizedMessage ?: "") }
    }

    private fun connect(id: String) {
        reconnecting = false
        reconnectConnectionPending = false
        reconnectTimeout?.let(reconnectHandler::removeCallbacks)
        client.requestConnection(displayName, id, connectionLifecycle)
            .addOnFailureListener { status = getString(R.string.connection_failed, it.localizedMessage ?: "") }
    }

    private fun beginReconnect() {
        if (isHost || lastHostName.isNullOrBlank() || reconnecting) return
        reconnecting = true
        reconnectAttempt = 0
        runReconnectAttempt()
    }

    private fun runReconnectAttempt() {
        if (!reconnecting || isHost || connectedPeers.isNotEmpty()) return
        if (reconnectAttempt >= 3) {
            reconnecting = false
            searchingForRoom = false
            status = getString(R.string.host_not_found)
            return
        }
        reconnectAttempt++
        searchingForRoom = true
        status = getString(R.string.reconnect_search, reconnectAttempt)
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        client.startDiscovery(serviceIdFor(programName), discovery, options)
            .addOnFailureListener { finishReconnectAttempt() }
        reconnectTimeout = Runnable {
            if (reconnecting && connectedPeers.isEmpty() && !reconnectConnectionPending) {
                client.stopDiscovery()
                finishReconnectAttempt()
            }
        }
        reconnectHandler.postDelayed(reconnectTimeout!!, 8_000)
    }

    private fun finishReconnectAttempt() {
        reconnectTimeout?.let(reconnectHandler::removeCallbacks)
        if (!reconnecting || connectedPeers.isNotEmpty()) return
        reconnectConnectionPending = false
        if (reconnectAttempt >= 3) {
            reconnecting = false
            searchingForRoom = false
            status = getString(R.string.host_not_found)
        } else reconnectHandler.postDelayed({ runReconnectAttempt() }, 1_000)
    }

    private fun sendMessage(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (connectedPeers.isEmpty()) { status = getString(R.string.connect_before_send); return }
        if (!roomAuthenticated) { status = getString(R.string.access_checking); return }
        val sentAt = System.currentTimeMillis()
        val obj = JSONObject().put("id", UUID.randomUUID().toString()).put("name", displayName).put("text", clean).put("time", sentAt)
        val bytes = Payload.fromBytes(obj.toString().toByteArray())
        messages.add(ChatMessage(displayName, clean, sentAt, mine = true, delivery = getString(R.string.message_delivery)))
        if (isHost) HostConnectionService.send(this, obj.toString())
        else connectedPeers.firstOrNull()?.let { client.sendPayload(it, bytes) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = getString(R.string.pending_status)
        val profilePrefs = getSharedPreferences("student_profile", MODE_PRIVATE)
        programName = profilePrefs.getString("room_name", "Salon ouvert") ?: "Salon ouvert"
        programCode = profilePrefs.getString("room_note", "") ?: ""
        roomAccessCode = profilePrefs.getString("room_access_code", "") ?: ""
        nickname = profilePrefs.getString("name", "")?.ifBlank { getString(R.string.applicant) } ?: getString(R.string.applicant)
        lastHostName = profilePrefs.getString("last_host_name", null)
        setContent {
            val navy = androidx.compose.ui.graphics.Color(0xFF10233F)
            val teal = androidx.compose.ui.graphics.Color(0xFF16877F)
            val mint = androidx.compose.ui.graphics.Color(0xFFEAF6F3)
            val page = androidx.compose.ui.graphics.Color(0xFFF4F7FA)
            val ink = androidx.compose.ui.graphics.Color(0xFF1D2B3A)
            val connected = connectedPeers.isNotEmpty()
            val groupActive = hostConnectionReady
            val searching = searchingForRoom
            val online = connected || groupActive
            val networkLabel = when {
                connected -> getString(R.string.status_connected)
                groupActive -> getString(R.string.network_ready)
                searching -> getString(R.string.searching_label)
                else -> getString(R.string.pending_status)
            }
            MaterialTheme(
                colorScheme = lightColorScheme(primary = teal, secondary = navy, background = page, surface = androidx.compose.ui.graphics.Color.White),
                shapes = Shapes(
                    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
                    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
                )
            ) {
                var name by remember { mutableStateOf(profilePrefs.getString("name", "") ?: "") }
                var profileSaved by rememberSaveable { mutableStateOf(profilePrefs.getBoolean("profile_saved", false)) }
                var input by remember { mutableStateOf("") }
                var aboutOpen by rememberSaveable { mutableStateOf(false) }
                var diagnosticOpen by rememberSaveable { mutableStateOf(false) }
                val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                val drawerScope = rememberCoroutineScope()
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet(drawerContainerColor = page) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(getString(R.string.app_title), style = MaterialTheme.typography.titleMedium, color = navy)
                                if (profileSaved) Text(displayName, style = MaterialTheme.typography.bodyMedium, color = ink)
                            }
                            HorizontalDivider(color = androidx.compose.ui.graphics.Color(0xFFE2E8F0))
                            NavigationDrawerItem(
                                label = { Text(getString(R.string.about)) },
                                selected = false,
                                icon = { Text("ⓘ", color = teal) },
                                colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = page, unselectedTextColor = navy, unselectedIconColor = teal),
                                onClick = { aboutOpen = true; drawerScope.launch { drawerState.close() } }
                            )
                            NavigationDrawerItem(
                                label = { Text(if (profileSaved) getString(R.string.my_profile) else getString(R.string.configure_profile)) },
                                selected = false,
                                icon = { Text("●", color = teal) },
                                colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = page, unselectedTextColor = navy, unselectedIconColor = teal),
                                onClick = { profileSaved = false; profilePrefs.edit().putBoolean("profile_saved", false).apply(); drawerScope.launch { drawerState.close() } }
                            )
                        }
                    }
                ) {
                val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
                    if (results.values.all { it }) status = getString(R.string.permission_ok)
                    else status = getString(R.string.permission_missing)
                }
                val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (granted) startHost() else status = getString(R.string.notification_needed)
                }
                Surface(Modifier.fillMaxSize(), color = page) {
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(
                            Modifier.fillMaxWidth().weight(1f).then(if (!profileSaved) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                        // Configuration défilante, clavier et barre d’envoi hors de cette zone.
                        // En-tête de marque : profil et état du réseau local.
                        Card(
                            colors = CardDefaults.cardColors(containerColor = navy),
                            shape = MaterialTheme.shapes.large,
                            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = { drawerScope.launch { drawerState.open() } }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                                            Text("☰", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleLarge)
                                        }
                                        Text(getString(R.string.app_title), color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.headlineSmall, maxLines = 1, modifier = Modifier.weight(1f))
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    Surface(color = androidx.compose.ui.graphics.Color(0x3321C4A8), shape = MaterialTheme.shapes.small) {
                                        Text("●  LOCAL", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = androidx.compose.ui.graphics.Color(0xFFB8E9E1), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                Text(getString(R.string.nearby_tagline), color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        // Le profil disparaît une fois enregistré ; possibilité de le modifier ensuite.
                        if (!profileSaved) Card(
                            colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(getString(R.string.profile), color = teal, style = MaterialTheme.typography.labelMedium)
                                OutlinedTextField(
                                    value = name,
                                    onValueChange = {
                                        name = it
                                        nickname = it.ifBlank { getString(R.string.applicant) }
                                        profilePrefs.edit().putString("name", it).apply()
                                    },
                                    placeholder = { Text(getString(R.string.name_placeholder)) },
                                    singleLine = true,
                                    shape = MaterialTheme.shapes.medium,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        unfocusedContainerColor = androidx.compose.ui.graphics.Color(0xFFF8FAFC),
                                        focusedContainerColor = androidx.compose.ui.graphics.Color(0xFFF8FAFC),
                                        unfocusedBorderColor = androidx.compose.ui.graphics.Color(0xFFE2E8F0),
                                        focusedBorderColor = teal
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(getString(R.string.room_name), color = teal, style = MaterialTheme.typography.labelMedium)
                                OutlinedTextField(
                                    value = programName,
                                    onValueChange = {
                                        programName = it
                                        profilePrefs.edit().putString("room_name", it).apply()
                                    },
                                    placeholder = { Text(getString(R.string.room_placeholder)) },
                                    singleLine = true,
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = roomAccessCode,
                                    onValueChange = { value ->
                                        roomAccessCode = value.filter(Char::isDigit).take(4)
                                        profilePrefs.edit().putString("room_access_code", roomAccessCode).apply()
                                    },
                                    label = { Text(getString(R.string.access_code_optional)) },
                                    placeholder = { Text(getString(R.string.access_code_hint)) },
                                    singleLine = true,
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = programCode,
                                    onValueChange = {
                                        programCode = it
                                        profilePrefs.edit().putString("room_note", it).apply()
                                    },
                                    label = { Text(getString(R.string.organization_optional)) },
                                    placeholder = { Text(getString(R.string.organization_placeholder)) },
                                    singleLine = true,
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Button(
                                    onClick = {
                                        val cleanName = name.trim()
                                        if (cleanName.isNotEmpty()) {
                                            nickname = cleanName
                                            profilePrefs.edit().putString("name", cleanName).putBoolean("profile_saved", true).apply()
                                            profileSaved = true
                                        }
                                    },
                                    enabled = name.isNotBlank() && (roomAccessCode.isBlank() || roomAccessCode.length == 4),
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    shape = MaterialTheme.shapes.medium
                                ) { Text(getString(R.string.save_profile)) }
                            }
                        }

                        Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (isHost) getString(R.string.host_hint) else getString(R.string.host_connection_hint), style = MaterialTheme.typography.bodySmall, color = ink)
                                Button(
                                    onClick = {
                                        if (!profileSaved) status = getString(R.string.profile_prompt)
                                        else if (!hasPermissions()) permissionLauncher.launch(requiredPermissions())
                                        else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        else startHost()
                                    },
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    shape = MaterialTheme.shapes.medium,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                                ) { Text(getString(R.string.create_connection), maxLines = 1, style = MaterialTheme.typography.labelLarge) }
                                OutlinedButton(
                                    onClick = { if (roomAccessCode.isNotBlank() && roomAccessCode.length != 4) status = getString(R.string.access_code_length) else if (!profileSaved) status = getString(R.string.profile_prompt) else if (hasPermissions()) searchGroup() else permissionLauncher.launch(requiredPermissions()) },
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    shape = MaterialTheme.shapes.medium,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                                ) { Text(getString(R.string.search), maxLines = 1, style = MaterialTheme.typography.labelLarge) }
                            }
                        }

                        // Statut réseau lisible d’un coup d’œil.
                        Surface(color = androidx.compose.ui.graphics.Color.White, shape = MaterialTheme.shapes.medium) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("●", color = if (online) teal else ink)
                                Column(Modifier.weight(1f)) {
                                    Text(networkLabel, style = MaterialTheme.typography.labelLarge, color = ink)
                                    if (status != getString(R.string.pending_status) && !groupActive && !connected) {
                                        Text(status, style = MaterialTheme.typography.bodySmall, color = ink, maxLines = 1)
                                    }
                                }
                                Surface(color = if (online) mint else page, shape = MaterialTheme.shapes.small) {
                                    Text(if (connectedPeers.size == 1) getString(R.string.connected_one, connectedPeers.size) else getString(R.string.connected_many, connectedPeers.size), Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = if (online) teal else ink, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }

                        TextButton(onClick = { diagnosticOpen = true }) { Text(getString(R.string.conditions)) }

                        if (peers.any { it.key !in connectedPeers }) {
                            Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    Text(getString(R.string.available_connections), style = MaterialTheme.typography.labelMedium, color = teal)
                                    peers.toList().filter { it.first !in connectedPeers }.forEach { (id, label) ->
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(label, color = ink, modifier = Modifier.weight(1f))
                                            TextButton(onClick = { connect(id) }) { Text(getString(R.string.join)) }
                                        }
                                    }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(getString(R.string.discussion), style = MaterialTheme.typography.titleLarge, color = navy)
                            Text(if (messages.size == 1) getString(R.string.messages_one, messages.size) else getString(R.string.messages_many, messages.size), style = MaterialTheme.typography.labelMedium, color = ink)
                        }
                        Card(
                            Modifier.fillMaxWidth().then(if (profileSaved) Modifier.weight(1f) else Modifier.heightIn(min = 180.dp, max = 360.dp)),
                            colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            if (messages.isEmpty()) {
                                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                    Surface(color = mint, shape = androidx.compose.foundation.shape.CircleShape) {
                                        Text("☏", Modifier.padding(horizontal = 16.dp, vertical = 10.dp), color = teal, style = MaterialTheme.typography.headlineSmall)
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Text(getString(R.string.empty_chat), style = MaterialTheme.typography.titleMedium, color = navy)
                                    Text(getString(R.string.host_message_hint), style = MaterialTheme.typography.bodySmall, color = ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                }
                            } else {
                                LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(messages.size) { index ->
                                        val msg = messages[index]
                                        val dayLabel = remember(msg.time) { SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(msg.time)) }
                                        val todayLabel = remember { SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date()) }
                                        if (index == 0 || SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(messages[index - 1].time)) != dayLabel) {
                                            Text(if (dayLabel == todayLabel) "${getString(R.string.today)} · ${SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(msg.time))}" else SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(msg.time)), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = ink)
                                        }
                                        Column(Modifier.fillMaxWidth(), horizontalAlignment = if (msg.mine) androidx.compose.ui.Alignment.End else androidx.compose.ui.Alignment.Start) {
                                            val previousSameSender = index > 0 && messages[index - 1].name == msg.name && SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(messages[index - 1].time)) == dayLabel
                                            if (!previousSameSender) Text(msg.name, style = MaterialTheme.typography.labelSmall, color = ink, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                                            Surface(
                                                color = if (msg.mine) teal else mint,
                                                shape = androidx.compose.foundation.shape.RoundedCornerShape(
                                                    topStart = 18.dp, topEnd = 18.dp,
                                                    bottomEnd = if (msg.mine) 5.dp else 18.dp,
                                                    bottomStart = if (msg.mine) 18.dp else 5.dp
                                                ),
                                                modifier = Modifier.widthIn(max = 300.dp)
                                            ) {
                                                Text(msg.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = if (msg.mine) androidx.compose.ui.graphics.Color.White else navy)
                                            }
                                            val timeLabel = remember(msg.time) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.time)) }
                                            Text("$timeLabel  ·  ${msg.delivery}", style = MaterialTheme.typography.labelSmall, color = ink, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                                        }
                                    }
                                }
                            }
                        }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = input,
                                onValueChange = { input = it },
                                enabled = true,
                                placeholder = { Text(if (connectedPeers.isNotEmpty()) getString(R.string.send_placeholder) else getString(R.string.new_message)) },
                                modifier = Modifier.weight(1f),
                                maxLines = 3,
                                shape = MaterialTheme.shapes.large,
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedContainerColor = androidx.compose.ui.graphics.Color.White,
                                    focusedContainerColor = androidx.compose.ui.graphics.Color.White,
                                    unfocusedBorderColor = androidx.compose.ui.graphics.Color(0xFFE2E8F0),
                                    focusedBorderColor = teal
                                )
                            )
                            Button(
                                onClick = { if (connectedPeers.isNotEmpty()) { sendMessage(input); input = "" } else { status = getString(R.string.connect_before_send) } },
                                enabled = input.isNotBlank() && roomAuthenticated,
                                modifier = Modifier.size(52.dp),
                                shape = androidx.compose.foundation.shape.CircleShape,
                                contentPadding = PaddingValues(0.dp)
                            ) { Text("➤", style = MaterialTheme.typography.titleLarge) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(getString(R.string.stay_open), style = MaterialTheme.typography.labelLarge, color = ink)
                        }
                    }
                }
                if (diagnosticOpen) {
                    val missing = missingPermissions()
                    val btOk = bluetoothEnabled()
                    val locationOk = locationEnabled()
                    val playOk = playServicesAvailable()
                    AlertDialog(
                        containerColor = androidx.compose.ui.graphics.Color.White,
                        onDismissRequest = { diagnosticOpen = false },
                        title = { Text(getString(R.string.diagnostic)) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(getString(R.string.diagnostic_bluetooth, if (btOk) "✓" else "✗"))
                                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) Text(getString(R.string.diagnostic_location, if (locationOk) "✓" else "✗"))
                                Text(getString(R.string.diagnostic_permissions, if (missing.isEmpty()) "✓" else "✗", if (missing.isEmpty()) getString(R.string.permissions_granted_suffix) else getString(R.string.permissions_missing_suffix)))
                                Text(getString(R.string.diagnostic_play, if (playOk) "✓" else "✗"))
                                Text(getString(R.string.diagnostic_note), style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        confirmButton = {
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                if (!btOk) TextButton(onClick = { runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }) { Text(getString(R.string.bluetooth_settings)) }
                                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R && !locationOk) TextButton(onClick = { runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } }) { Text(getString(R.string.enable_location)) }
                                if (missing.isNotEmpty()) TextButton(onClick = { diagnosticOpen = false; permissionLauncher.launch(missing.toTypedArray()) }) { Text(getString(R.string.allow_permissions)) }
                                if (!playOk) TextButton(onClick = { runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:com.google.android.gms"))) } }) { Text(getString(R.string.play_settings)) }
                                TextButton(onClick = { diagnosticOpen = false }) { Text(getString(R.string.close)) }
                            }
                        }
                    )
                }
                if (aboutOpen) {
                    AlertDialog(
                        containerColor = androidx.compose.ui.graphics.Color.White,
                        titleContentColor = navy,
                        textContentColor = ink,
                        onDismissRequest = { aboutOpen = false },
                        confirmButton = { TextButton(onClick = { aboutOpen = false }) { Text(getString(R.string.close)) } },
                        title = { Text(getString(R.string.about_local)) },
                        text = { Text(getString(R.string.entry_approach)) }
                    )
                }
                }
            }
        }
    }

    private val hostReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val value = intent.getStringExtra(HostConnectionService.EXTRA_VALUE).orEmpty()
            when (intent.action) {
                HostConnectionService.ACTION_STATUS -> {
                    status = value
                    when (intent.getStringExtra(HostConnectionService.EXTRA_STATE)) {
                        HostConnectionService.STATE_ACTIVE -> { hostConnectionReady = true; if (isHost) roomAuthenticated = true }
                        HostConnectionService.STATE_FAILED, HostConnectionService.STATE_CLOSED -> {
                            hostConnectionReady = false
                            isHost = false
                            profilePreferences.edit().putBoolean("host_active", false).apply()
                        }
                    }
                }
                HostConnectionService.ACTION_PEERS -> {
                    val json = runCatching { JSONObject(value) }.getOrNull() ?: JSONObject()
                    val count = json.optInt("count")
                    val ids = json.optJSONArray("ids")
                    connectedPeers.clear()
                    if (ids != null) for (i in 0 until ids.length()) connectedPeers.add(ids.optString(i))
                    else repeat(count) { connectedPeers.add("service-peer-$it") }
                    if (count > 0) status = if (count == 1) getString(R.string.connected_one, count) else getString(R.string.connected_many, count)
                }
                HostConnectionService.ACTION_MESSAGE -> runCatching {
                    val obj = JSONObject(value)
                    messages.add(ChatMessage(obj.optString("name", getString(R.string.applicant)), obj.optString("text", ""), obj.optLong("time", System.currentTimeMillis()), delivery = getString(R.string.received)))
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(HostConnectionService.ACTION_STATUS)
            addAction(HostConnectionService.ACTION_PEERS)
            addAction(HostConnectionService.ACTION_MESSAGE)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(hostReceiver, filter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(hostReceiver, filter)
        isHost = getSharedPreferences("student_profile", MODE_PRIVATE).getBoolean("host_active", isHost)
        if (isHost) { hostConnectionReady = true; startService(Intent(this, HostConnectionService::class.java).setAction(HostConnectionService.ACTION_QUERY)) }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(hostReceiver) }
        super.onStop()
    }

    override fun onDestroy() {
        reconnectTimeout?.let(reconnectHandler::removeCallbacks)
        reconnectHandler.removeCallbacksAndMessages(null)
        if (!getSharedPreferences("student_profile", MODE_PRIVATE).getBoolean("host_active", false)) { client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints() }
        super.onDestroy()
    }
}
