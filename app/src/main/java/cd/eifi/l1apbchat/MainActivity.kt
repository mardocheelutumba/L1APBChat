package cd.eifi.l1apbchat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import com.google.android.gms.nearby.connection.*
import com.google.android.gms.tasks.Task
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

private const val SERVICE_ID = "cd.eifi.l1apbchat.localchat"
private data class ChatMessage(val name: String, val text: String, val time: Long, val mine: Boolean = false, val delivery: String = "")

class MainActivity : ComponentActivity() {
    private val client by lazy { Nearby.getConnectionsClient(this) }
    private val peers = mutableStateMapOf<String, String>() // nearby endpoint id -> display name
    private val connectedPeers = mutableStateListOf<String>()
    private val messages = mutableStateListOf<ChatMessage>()
    private var status by mutableStateOf("En attente d’une connexion locale")
    private var isHost = false
    private var lastHostName: String? = null
    private var reconnecting = false
    private var reconnectAttempts = 0
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectTimeout: Runnable? = null
    private val profilePreferences by lazy { getSharedPreferences("student_profile", MODE_PRIVATE) }
    private var nickname by mutableStateOf("Étudiant")
    private var programName by mutableStateOf("L1 Analyse et Programmation B")
    private var programCode by mutableStateOf("AP/B")
    private val displayName: String get() = "${nickname.trim().ifBlank { "Étudiant" }} · $programCode"

    private fun serviceIdFor(group: String) = SERVICE_ID
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
            if (!isHost) {
                lastHostName = info.endpointName
                profilePreferences.edit().putString("last_host_name", info.endpointName).apply()
            }
            // Le prototype n'accepte que des membres confirmés par l'utilisateur.
            runOnUiThread {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Demande de connexion")
                    .setMessage("${info.endpointName} veut rejoindre ta connexion $programCode.")
                    .setNegativeButton("Refuser") { _, _ -> client.rejectConnection(id) }
                    .setPositiveButton("Accepter") { _, _ -> client.acceptConnection(id, payloadCallback) }
                    .show()
            }
        }
        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            runOnUiThread {
                if (result.status.isSuccess) {
                    if (isHost) peers[id] = peers[id] ?: "Membre connecté"
                    if (id !in connectedPeers) connectedPeers.add(id)
                    reconnecting = false
                    reconnectAttempts = 0
                    reconnectTimeout?.let(reconnectHandler::removeCallbacks)
                    status = "Connecté à ${connectedPeers.size} appareil(s)"
                    if (isHost) sendSystemWelcome(id)
                } else {
                    peers.remove(id)
                    if (reconnecting) finishReconnectAttempt() else status = "Connexion refusée ou interrompue"
                }
            }
        }
        override fun onDisconnected(id: String) {
            runOnUiThread {
                peers.remove(id)
                connectedPeers.remove(id)
                if (connectedPeers.isEmpty()) {
                    status = "Connexion interrompue"
                    if (!isHost) beginReconnect()
                } else status = "Connecté à ${connectedPeers.size} appareil(s)"
            }
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            peers[id] = info.endpointName
            if (reconnecting && info.endpointName == lastHostName && connectedPeers.isEmpty()) {
                reconnecting = false
                reconnectTimeout?.let(reconnectHandler::removeCallbacks)
                client.stopDiscovery()
                reconnecting = true
                client.requestConnection(displayName, id, connectionLifecycle)
                    .addOnFailureListener { finishReconnectAttempt() }
                reconnectTimeout = Runnable { if (reconnecting && connectedPeers.isEmpty()) finishReconnectAttempt() }
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
                val mid = obj.optString("id")
                if (mid.isBlank() || !receivedIds.add(mid)) return
                val chatMessage = ChatMessage(
                    name = obj.optString("name", "Étudiant"),
                    text = obj.optString("text", ""),
                    time = obj.optLong("time", System.currentTimeMillis()),
                    delivery = "Reçu"
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

    private fun sendSystemWelcome(endpointId: String) {
        val obj = JSONObject().put("id", UUID.randomUUID().toString()).put("name", "ESPACE ÉTUDIANT").put("text", "Bienvenue dans $programName !").put("time", System.currentTimeMillis())
        client.sendPayload(endpointId, Payload.fromBytes(obj.toString().toByteArray()))
    }

    private fun startHost() {
        if (!hasPermissions()) { status = "Autorise les permissions demandées pour continuer."; return }
        isHost = true
        status = "Création du groupe…"
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        client.startAdvertising(displayName, serviceIdFor(programName), connectionLifecycle, options)
            .addOnSuccessListener { status = "Ta connexion est prête — garde l’application ouverte" }
            .addOnFailureListener { status = "Échec du démarrage : ${it.localizedMessage}" }
    }

    private fun searchGroup() {
        reconnecting = false
        reconnectTimeout?.let(reconnectHandler::removeCallbacks)
        reconnectAttempts = 0
        if (!hasPermissions()) { status = "Autorise les permissions demandées pour continuer."; return }
        isHost = false
        status = "Recherche de la connexion $programCode…"
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        client.startDiscovery(serviceIdFor(programName), discovery, options)
            .addOnFailureListener { status = "Échec de la recherche : ${it.localizedMessage}" }
    }

    private fun connect(id: String) {
        reconnecting = false
        reconnectTimeout?.let(reconnectHandler::removeCallbacks)
        client.requestConnection(displayName, id, connectionLifecycle)
            .addOnFailureListener { status = "Connexion impossible : ${it.localizedMessage}" }
    }

    private fun beginReconnect() {
        if (isHost || lastHostName.isNullOrBlank() || reconnecting) return
        reconnecting = true
        reconnectAttempts = 0
        runReconnectAttempt()
    }

    private fun runReconnectAttempt() {
        if (!reconnecting || isHost || connectedPeers.isNotEmpty()) return
        if (reconnectAttempts >= 3) {
            reconnecting = false
            status = "Hôte introuvable après 3 essais. Recherche manuelle possible."
            return
        }
        reconnectAttempts++
        status = "Reconnexion : recherche de l’hôte (${reconnectAttempts}/3)…"
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        client.startDiscovery(serviceIdFor(programName), discovery, options)
            .addOnFailureListener { finishReconnectAttempt() }
        reconnectTimeout = Runnable {
            if (reconnecting && connectedPeers.isEmpty()) {
                client.stopDiscovery()
                finishReconnectAttempt()
            }
        }
        reconnectHandler.postDelayed(reconnectTimeout!!, 8_000)
    }

    private fun finishReconnectAttempt() {
        reconnectTimeout?.let(reconnectHandler::removeCallbacks)
        if (!reconnecting || connectedPeers.isNotEmpty()) return
        if (reconnectAttempts >= 3) {
            reconnecting = false
            status = "Hôte introuvable après 3 essais. Recherche manuelle possible."
        } else {
            reconnectHandler.postDelayed({ runReconnectAttempt() }, 1_000)
        }
    }

    private fun sendMessage(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (connectedPeers.isEmpty()) { status = "Connecte-toi à un appareil avant d’envoyer un message."; return }
        val sentAt = System.currentTimeMillis()
        val obj = JSONObject().put("id", UUID.randomUUID().toString()).put("name", displayName).put("text", clean).put("time", sentAt)
        val bytes = Payload.fromBytes(obj.toString().toByteArray())
        messages.add(ChatMessage(displayName, clean, sentAt, mine = true, delivery = "Envoi tenté"))
        if (isHost) connectedPeers.forEach { client.sendPayload(it, bytes) }
        else connectedPeers.firstOrNull()?.let { client.sendPayload(it, bytes) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profilePrefs = getSharedPreferences("student_profile", MODE_PRIVATE)
        programName = profilePrefs.getString("filiere", "L1 Analyse et Programmation B") ?: "L1 Analyse et Programmation B"
        programCode = profilePrefs.getString("filiere_code", "AP/B") ?: "AP/B"
        nickname = profilePrefs.getString("name", "")?.ifBlank { "Étudiant" } ?: "Étudiant"
        lastHostName = profilePrefs.getString("last_host_name", null)
        setContent {
            val navy = androidx.compose.ui.graphics.Color(0xFF10233F)
            val teal = androidx.compose.ui.graphics.Color(0xFF16877F)
            val mint = androidx.compose.ui.graphics.Color(0xFFEAF6F3)
            val page = androidx.compose.ui.graphics.Color(0xFFF4F7FA)
            val ink = androidx.compose.ui.graphics.Color(0xFF1D2B3A)
            val connected = status.startsWith("Connecté")
            val groupActive = status.contains("connexion est prête") || status.contains("groupe actif")
            val searching = status.startsWith("Recherche")
            val online = connected || groupActive
            val networkLabel = when {
                connected -> "Connecté"
                groupActive -> "Connexion prête"
                searching -> "Recherche en cours"
                else -> "En attente d’une connexion locale"
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
                var addFiliereDialog by rememberSaveable { mutableStateOf(false) }
                var customFiliereName by rememberSaveable { mutableStateOf("") }
                var customFiliereCode by rememberSaveable { mutableStateOf("") }
                var filiereError by rememberSaveable { mutableStateOf("") }
                val customFilieres = remember {
                    mutableStateListOf<Triple<String, String, String>>().apply {
                        val stored = profilePrefs.getString("custom_filieres", "[]") ?: "[]"
                        try {
                            val array = JSONArray(stored)
                            for (i in 0 until array.length()) {
                                val item = array.getJSONObject(i)
                                val label = item.optString("name")
                                val code = item.optString("code")
                                if (label.isNotBlank() && code.isNotBlank()) add(Triple(label, code, code))
                            }
                        } catch (_: Exception) { }
                    }
                }
                val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                val drawerScope = rememberCoroutineScope()
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet(drawerContainerColor = page) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("ESPACE ÉTUDIANT", style = MaterialTheme.typography.titleMedium, color = navy)
                                if (profileSaved) Text(displayName, style = MaterialTheme.typography.bodyMedium, color = ink)
                            }
                            HorizontalDivider(color = androidx.compose.ui.graphics.Color(0xFFE2E8F0))
                            NavigationDrawerItem(
                                label = { Text("À propos") },
                                selected = false,
                                icon = { Text("ⓘ", color = teal) },
                                colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = page, unselectedTextColor = navy, unselectedIconColor = teal),
                                onClick = { aboutOpen = true; drawerScope.launch { drawerState.close() } }
                            )
                            NavigationDrawerItem(
                                label = { Text(if (profileSaved) "Mon profil" else "Configurer mon profil") },
                                selected = false,
                                icon = { Text("●", color = teal) },
                                colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = page, unselectedTextColor = navy, unselectedIconColor = teal),
                                onClick = { profileSaved = false; profilePrefs.edit().putBoolean("profile_saved", false).apply(); drawerScope.launch { drawerState.close() } }
                            )
                        }
                    }
                ) {
                val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
                    if (results.values.all { it }) status = "Permissions accordées. Tu peux créer ou rechercher une connexion."
                    else status = "Certaines permissions sont refusées : la connexion hors ligne ne pourra pas démarrer."
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
                        // En-tête de marque : identité campus et état du réseau local.
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
                                        Text("ESPACE ÉTUDIANT", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.headlineSmall, maxLines = 1, modifier = Modifier.weight(1f))
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    Surface(color = androidx.compose.ui.graphics.Color(0x3321C4A8), shape = MaterialTheme.shapes.small) {
                                        Text("●  LOCAL", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = androidx.compose.ui.graphics.Color(0xFFB8E9E1), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                Text("Connecte-toi et échange à proximité.", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        // Le profil disparaît une fois enregistré ; possibilité de le modifier ensuite.
                        if (!profileSaved) Card(
                            colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("IDENTITÉ ÉTUDIANTE", color = teal, style = MaterialTheme.typography.labelMedium)
                                OutlinedTextField(
                                    value = name,
                                    onValueChange = {
                                        name = it
                                        nickname = it.ifBlank { "Étudiant" }
                                        profilePrefs.edit().putString("name", it).apply()
                                    },
                                    placeholder = { Text("Nlm") },
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
                                Text("FILIÈRE", color = teal, style = MaterialTheme.typography.labelMedium)
                                var filiereMenu by remember { mutableStateOf(false) }
                                val filieres = listOf(
                                    Triple("L1 Analyse et Programmation A", "AP/A", "AP/A"),
                                    Triple("L1 Analyse et Programmation B", "AP/B", "AP/B"),
                                    Triple("L1 Réseau C", "R/C", "R/C"),
                                    Triple("L1 Réseau D", "R/D", "R/D")
                                ) + customFilieres.toList()
                                val selectedFiliere = filieres.firstOrNull { it.first == programName } ?: filieres[1]
                                Box {
                                    OutlinedButton(onClick = { filiereMenu = true }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                                        Text("${selectedFiliere.first}  ·  ${selectedFiliere.second}     ▾")
                                    }
                                    DropdownMenu(expanded = filiereMenu, onDismissRequest = { filiereMenu = false }) {
                                        filieres.forEach { (label, abbreviation, code) ->
                                            DropdownMenuItem(
                                                text = { Text("$label  ·  $abbreviation") },
                                                onClick = {
                                                    programName = label
                                                    programCode = code
                                                    profilePrefs.edit().putString("filiere", label).putString("filiere_code", code).apply()
                                                    filiereMenu = false
                                                }
                                            )
                                        }
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text("+ Ajouter une filière") },
                                            onClick = {
                                                filiereMenu = false
                                                customFiliereName = ""
                                                customFiliereCode = ""
                                                filiereError = ""
                                                addFiliereDialog = true
                                            }
                                        )
                                    }
                                }
                                if (addFiliereDialog) {
                                    AlertDialog(
                                        containerColor = androidx.compose.ui.graphics.Color.White,
                                        titleContentColor = navy,
                                        textContentColor = ink,
                                        onDismissRequest = { addFiliereDialog = false },
                                        title = { Text("Ajouter une filière") },
                                        text = {
                                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                OutlinedTextField(
                                                    value = customFiliereName,
                                                    onValueChange = { customFiliereName = it },
                                                    label = { Text("Nom de la filière") },
                                                    singleLine = true
                                                )
                                                OutlinedTextField(
                                                    value = customFiliereCode,
                                                    onValueChange = { customFiliereCode = it },
                                                    label = { Text("Abréviation (ex. AP/C)") },
                                                    singleLine = true
                                                )
                                                if (filiereError.isNotBlank()) Text(filiereError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                            }
                                        },
                                        confirmButton = {
                                            TextButton(
                                                enabled = customFiliereName.isNotBlank() && customFiliereCode.isNotBlank(),
                                                onClick = {
                                                    val label = customFiliereName.trim()
                                                    val code = customFiliereCode.trim()
                                                    if (filieres.none { it.first.equals(label, true) || it.second.equals(code, true) }) {
                                                        customFilieres.add(Triple(label, code, code))
                                                        val array = JSONArray()
                                                        customFilieres.forEach { item ->
                                                            array.put(JSONObject().put("name", item.first).put("code", item.second))
                                                        }
                                                        profilePrefs.edit().putString("custom_filieres", array.toString()).apply()
                                                        programName = label
                                                        programCode = code
                                                        profilePrefs.edit().putString("filiere", label).putString("filiere_code", code).apply()
                                                        addFiliereDialog = false
                                                    } else {
                                                        filiereError = "Ce nom ou cette abréviation existe déjà. Choisis une autre valeur."
                                                    }
                                                }
                                            ) { Text("Ajouter") }
                                        },
                                        dismissButton = { TextButton(onClick = { addFiliereDialog = false }) { Text("Annuler") } }
                                    )
                                }
                                Button(
                                    onClick = {
                                        val cleanName = name.trim()
                                        if (cleanName.isNotEmpty()) {
                                            nickname = cleanName
                                            profilePrefs.edit().putString("name", cleanName).putBoolean("profile_saved", true).apply()
                                            profileSaved = true
                                        }
                                    },
                                    enabled = name.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    shape = MaterialTheme.shapes.medium
                                ) { Text("Enregistrer mon profil") }
                            }
                        }

                        Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (isHost) "Tu héberges la connexion : reste à proximité." else "1. Crée une connexion sur un téléphone.  2. Recherche-la ici pour la rejoindre.", style = MaterialTheme.typography.bodySmall, color = ink)
                                Button(
                                    onClick = { if (!profileSaved) status = "Enregistre d’abord ton identité étudiante." else if (hasPermissions()) startHost() else permissionLauncher.launch(requiredPermissions()) },
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    shape = MaterialTheme.shapes.medium,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                                ) { Text("Créer une connexion", maxLines = 1, style = MaterialTheme.typography.labelLarge) }
                                OutlinedButton(
                                    onClick = { if (!profileSaved) status = "Enregistre d’abord ton identité étudiante." else if (hasPermissions()) searchGroup() else permissionLauncher.launch(requiredPermissions()) },
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    shape = MaterialTheme.shapes.medium,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                                ) { Text("Rechercher", maxLines = 1, style = MaterialTheme.typography.labelLarge) }
                            }
                        }

                        // Statut réseau lisible d’un coup d’œil.
                        Surface(color = androidx.compose.ui.graphics.Color.White, shape = MaterialTheme.shapes.medium) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("●", color = if (online) teal else ink)
                                Column(Modifier.weight(1f)) {
                                    Text(networkLabel, style = MaterialTheme.typography.labelLarge, color = ink)
                                    if (status != "En attente d’une connexion locale" && !groupActive && !connected) {
                                        Text(status, style = MaterialTheme.typography.bodySmall, color = ink, maxLines = 1)
                                    }
                                }
                                Surface(color = if (online) mint else page, shape = MaterialTheme.shapes.small) {
                                    Text("${connectedPeers.size} connecté${if (connectedPeers.size == 1) "" else "s"}", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = if (online) teal else ink, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }

                        if (peers.any { it.key !in connectedPeers }) {
                            Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    Text("CONNEXIONS DISPONIBLES", style = MaterialTheme.typography.labelMedium, color = teal)
                                    peers.toList().filter { it.first !in connectedPeers }.forEach { (id, label) ->
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(label, color = ink, modifier = Modifier.weight(1f))
                                            TextButton(onClick = { connect(id) }) { Text("Rejoindre") }
                                        }
                                    }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("Discussion", style = MaterialTheme.typography.titleLarge, color = navy)
                            Text("${messages.size} message${if (messages.size == 1) "" else "s"}", style = MaterialTheme.typography.labelMedium, color = ink)
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
                                    Text("Aucune discussion pour le moment", style = MaterialTheme.typography.titleMedium, color = navy)
                                    Text("Les messages de tes camarades apparaîtront ici dès que tu auras rejoint une connexion.", style = MaterialTheme.typography.bodySmall, color = ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                }
                            } else {
                                LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(messages.size) { index ->
                                        val msg = messages[index]
                                        val dayLabel = remember(msg.time) { SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(msg.time)) }
                                        val todayLabel = remember { SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date()) }
                                        if (index == 0 || SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(messages[index - 1].time)) != dayLabel) {
                                            Text(if (dayLabel == todayLabel) "Aujourd’hui · ${SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(msg.time))}" else SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(msg.time)), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = ink)
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
                                placeholder = { Text(if (connectedPeers.isNotEmpty()) "Écrire un message…" else "Connecte-toi pour envoyer") },
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
                                onClick = { if (connectedPeers.isNotEmpty()) { sendMessage(input); input = "" } else { status = "Connecte-toi à un appareil avant d’envoyer un message." } },
                                enabled = input.isNotBlank(),
                                modifier = Modifier.size(52.dp),
                                shape = androidx.compose.foundation.shape.CircleShape,
                                contentPadding = PaddingValues(0.dp)
                            ) { Text("➤", style = MaterialTheme.typography.titleLarge) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("Garde l’app ouverte pour échanger", style = MaterialTheme.typography.labelLarge, color = ink)
                        }
                    }
                }
                if (aboutOpen) {
                    AlertDialog(
                        containerColor = androidx.compose.ui.graphics.Color.White,
                        titleContentColor = navy,
                        textContentColor = ink,
                        onDismissRequest = { aboutOpen = false },
                        confirmButton = { TextButton(onClick = { aboutOpen = false }) { Text("Fermer") } },
                        title = { Text("À propos d’Espace Étudiant") },
                        text = { Text("Cette application permet aux étudiants de se retrouver et d’échanger des messages à proximité sans Internet. Pour démarrer, une personne crée une connexion ; les autres la recherchent et demandent à la rejoindre. L’hôte doit rester proche et garder l’application ouverte. Le profil et les filières ajoutées sont enregistrés sur chaque téléphone. L’historique partagé, les messages privés, les vocaux et les appels ne sont pas encore disponibles.") }
                    )
                }
                }
            }
        }
    }

    override fun onDestroy() {
        client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints()
        super.onDestroy()
    }
}
