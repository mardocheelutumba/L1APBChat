package cd.eifi.l1apbchat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.android.gms.tasks.Task
import org.json.JSONObject
import java.util.UUID

private const val SERVICE_ID = "cd.eifi.l1apbchat.localchat"
private const val GROUP_NAME = "L1 Analyse et Programmation B"

class MainActivity : ComponentActivity() {
    private val client by lazy { Nearby.getConnectionsClient(this) }
    private val peers = mutableStateMapOf<String, String>() // endpoint id -> display name
    private val messages = mutableStateListOf<String>()
    private var status by mutableStateOf("Hors ligne")
    private var isHost = false
    private var nickname = "Étudiant"
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
            // Le prototype n'accepte que des membres confirmés par l'utilisateur.
            runOnUiThread {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Demande de connexion")
                    .setMessage("${info.endpointName} veut rejoindre le groupe $GROUP_NAME.")
                    .setNegativeButton("Refuser") { _, _ -> client.rejectConnection(id) }
                    .setPositiveButton("Accepter") { _, _ -> client.acceptConnection(id, payloadCallback) }
                    .show()
            }
        }
        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            runOnUiThread {
                if (result.status.isSuccess) {
                    peers[id] = peers[id] ?: "Membre connecté"
                    status = "Connecté : ${peers.size} appareil(s)"
                    if (isHost) sendSystemWelcome(id)
                } else {
                    peers.remove(id)
                    status = "Connexion refusée ou interrompue"
                }
            }
        }
        override fun onDisconnected(id: String) {
            runOnUiThread {
                peers.remove(id)
                status = "Déconnecté — ${peers.size} appareil(s) connecté(s)"
            }
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            peers[id] = info.endpointName
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
                val text = "${obj.optString("name", "Étudiant")}: ${obj.optString("text", "")}" 
                runOnUiThread { messages.add(text) }
                // Le téléphone hôte relaie le message du membre aux autres appareils.
                if (isHost) {
                    val bytes = payload.asBytes() ?: return
                    peers.keys.filter { it != id }.forEach { client.sendPayload(it, Payload.fromBytes(bytes)) }
                }
            } catch (_: Exception) { /* message invalide : on l'ignore */ }
        }
        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) = Unit
    }

    private fun sendSystemWelcome(endpointId: String) {
        val obj = JSONObject().put("id", UUID.randomUUID().toString()).put("name", "EIFI").put("text", "Bienvenue dans $GROUP_NAME !")
        client.sendPayload(endpointId, Payload.fromBytes(obj.toString().toByteArray()))
    }

    private fun startHost() {
        if (!hasPermissions()) { status = "Autorise les permissions demandées pour continuer."; return }
        isHost = true
        status = "Création du groupe…"
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        client.startAdvertising(nickname, SERVICE_ID, connectionLifecycle, options)
            .addOnSuccessListener { status = "Groupe $GROUP_NAME actif — garde cette application ouverte" }
            .addOnFailureListener { status = "Échec du démarrage : ${it.localizedMessage}" }
    }

    private fun searchGroup() {
        if (!hasPermissions()) { status = "Autorise les permissions demandées pour continuer."; return }
        isHost = false
        status = "Recherche du groupe $GROUP_NAME…"
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        client.startDiscovery(SERVICE_ID, discovery, options)
            .addOnFailureListener { status = "Échec de la recherche : ${it.localizedMessage}" }
    }

    private fun connect(id: String) {
        client.requestConnection(nickname, id, connectionLifecycle)
            .addOnFailureListener { status = "Connexion impossible : ${it.localizedMessage}" }
    }

    private fun sendMessage(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val obj = JSONObject().put("id", UUID.randomUUID().toString()).put("name", nickname).put("text", clean)
        val bytes = Payload.fromBytes(obj.toString().toByteArray())
        messages.add("Moi : $clean")
        if (isHost) peers.keys.forEach { client.sendPayload(it, bytes) }
        else peers.keys.firstOrNull()?.let { client.sendPayload(it, bytes) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val navy = androidx.compose.ui.graphics.Color(0xFF10233F)
            val teal = androidx.compose.ui.graphics.Color(0xFF16877F)
            val mint = androidx.compose.ui.graphics.Color(0xFFEAF6F3)
            val page = androidx.compose.ui.graphics.Color(0xFFF4F7FA)
            val ink = androidx.compose.ui.graphics.Color(0xFF1D2B3A)
            val muted = androidx.compose.ui.graphics.Color(0xFF718096)
            val online = status.startsWith("Connecté") || status.contains("actif")
            MaterialTheme(
                colorScheme = lightColorScheme(primary = teal, secondary = navy, background = page, surface = androidx.compose.ui.graphics.Color.White),
                shapes = Shapes(
                    extraSmall = MaterialTheme.shapes.extraSmall,
                    small = MaterialTheme.shapes.small,
                    medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                    large = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
                    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(30.dp)
                )
            ) {
                var name by remember { mutableStateOf("") }
                var input by remember { mutableStateOf("") }
                val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
                    if (results.values.all { it }) status = "Permissions accordées. Choisis créer ou rejoindre le groupe."
                    else status = "Certaines permissions sont refusées : la connexion hors ligne ne pourra pas démarrer."
                }
                Surface(Modifier.fillMaxSize(), color = page) {
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(
                            Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                        // En-tête de marque : identité campus et état du réseau local.
                        Card(
                            colors = CardDefaults.cardColors(containerColor = navy),
                            shape = MaterialTheme.shapes.large,
                            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Text("EIFI  /  CAMPUS", color = androidx.compose.ui.graphics.Color(0xFFB8E9E1), style = MaterialTheme.typography.labelLarge)
                                    Surface(color = androidx.compose.ui.graphics.Color(0x3321C4A8), shape = MaterialTheme.shapes.small) {
                                        Text("●  LOCAL", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = androidx.compose.ui.graphics.Color(0xFFB8E9E1), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                Text("La vie du campus,\nà proximité.", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.headlineSmall)
                                Text("Échangez sans connexion Internet", color = androidx.compose.ui.graphics.Color(0xFFD5E0EC), style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        // Carte de profil et de groupe, plus compacte pour laisser la place à la discussion.
                        Card(
                            colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Surface(color = mint, shape = androidx.compose.foundation.shape.CircleShape) {
                                        Text("É", Modifier.padding(horizontal = 13.dp, vertical = 9.dp), color = teal, style = MaterialTheme.typography.titleMedium)
                                    }
                                    OutlinedTextField(
                                        value = name,
                                        onValueChange = { name = it; nickname = it.ifBlank { "Étudiant" } },
                                        label = { Text("Ton nom dans le groupe") },
                                        singleLine = true,
                                        shape = MaterialTheme.shapes.medium,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                HorizontalDivider(color = page)
                                Text("TON GROUPE", color = teal, style = MaterialTheme.typography.labelMedium)
                                Text("L1 Analyse et Programmation B", style = MaterialTheme.typography.titleMedium, color = navy)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                    Button(
                                        onClick = { if (hasPermissions()) startHost() else permissionLauncher.launch(requiredPermissions()) },
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        shape = MaterialTheme.shapes.medium
                                    ) { Text("Créer un groupe") }
                                    OutlinedButton(
                                        onClick = { if (hasPermissions()) searchGroup() else permissionLauncher.launch(requiredPermissions()) },
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        shape = MaterialTheme.shapes.medium
                                    ) { Text("Rechercher") }
                                }
                            }
                        }

                        // Statut réseau lisible d’un coup d’œil.
                        Surface(color = if (online) mint else androidx.compose.ui.graphics.Color.White, shape = MaterialTheme.shapes.medium) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("●", color = if (online) teal else muted)
                                Column(Modifier.weight(1f)) {
                                    Text(if (online) "Réseau de proximité actif" else "Prêt à se connecter", style = MaterialTheme.typography.labelLarge, color = ink)
                                    Text(status, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 2)
                                }
                                Text("${peers.size}", color = teal, style = MaterialTheme.typography.titleMedium)
                            }
                        }

                        if (peers.isNotEmpty()) {
                            Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    Text("APPAREILS À PROXIMITÉ", style = MaterialTheme.typography.labelMedium, color = teal)
                                    peers.toList().forEach { (id, label) ->
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(label, color = ink, modifier = Modifier.weight(1f))
                                            TextButton(onClick = { connect(id) }) { Text("Rejoindre") }
                                        }
                                    }
                                }
                            }
                        }

                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("Discussion", style = MaterialTheme.typography.titleLarge, color = navy)
                            Text("${messages.size} message(s)", style = MaterialTheme.typography.labelMedium, color = muted)
                        }
                        Card(
                            Modifier.weight(1f).fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            if (messages.isEmpty()) {
                                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                    Surface(color = mint, shape = androidx.compose.foundation.shape.CircleShape) {
                                        Text("…", Modifier.padding(horizontal = 19.dp, vertical = 9.dp), color = teal, style = MaterialTheme.typography.headlineMedium)
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    Text("Votre conversation commence ici", style = MaterialTheme.typography.titleMedium, color = navy)
                                    Text("Crée ou rejoins le groupe pour échanger avec tes camarades à proximité.", style = MaterialTheme.typography.bodySmall, color = muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                }
                            } else {
                                LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(messages) { msg ->
                                        val mine = msg.startsWith("Moi : ")
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                                            Surface(
                                                color = if (mine) teal else mint,
                                                shape = androidx.compose.foundation.shape.RoundedCornerShape(
                                                    topStart = 18.dp, topEnd = 18.dp,
                                                    bottomEnd = if (mine) 5.dp else 18.dp,
                                                    bottomStart = if (mine) 18.dp else 5.dp
                                                ),
                                                modifier = Modifier.widthIn(max = 300.dp)
                                            ) {
                                                Text(msg, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = if (mine) androidx.compose.ui.graphics.Color.White else navy)
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
                                placeholder = { Text("Écrire un message…") },
                                modifier = Modifier.weight(1f),
                                maxLines = 3,
                                shape = MaterialTheme.shapes.large
                            )
                            Button(
                                onClick = { sendMessage(input); input = "" },
                                enabled = input.isNotBlank(),
                                modifier = Modifier.height(54.dp),
                                shape = MaterialTheme.shapes.large
                            ) { Text("Envoyer") }
                        }
                        Text("L’hôte doit garder l’application ouverte pendant les échanges.", modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = muted)
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
