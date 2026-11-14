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
            val teal = androidx.compose.ui.graphics.Color(0xFF1B9A91)
            val page = androidx.compose.ui.graphics.Color(0xFFF3F6FA)
            MaterialTheme(colorScheme = lightColorScheme(primary = teal, secondary = navy, background = page, surface = androidx.compose.ui.graphics.Color.White)) {
                var name by remember { mutableStateOf("") }
                var input by remember { mutableStateOf("") }
                val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
                    if (results.values.all { it }) status = "Permissions accordées. Choisis créer ou rejoindre le groupe."
                    else status = "Certaines permissions sont refusées : la connexion hors ligne ne pourra pas démarrer."
                }
                Surface(Modifier.fillMaxSize(), color = page) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Card(colors = CardDefaults.cardColors(containerColor = navy), shape = MaterialTheme.shapes.large) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("EIFI  •  CAMPUS", color = androidx.compose.ui.graphics.Color(0xFF9EDDD6), style = MaterialTheme.typography.labelMedium)
                                Text("Échanges de proximité", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.headlineSmall)
                                Text("Messagerie locale — sans Internet", color = androidx.compose.ui.graphics.Color(0xFFD4DFEE), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
                            Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("TON PROFIL", color = teal, style = MaterialTheme.typography.labelLarge)
                                OutlinedTextField(value = name, onValueChange = { name = it; nickname = it.ifBlank { "Étudiant" } }, label = { Text("Ton nom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                Text("GROUPE ACTUEL", color = teal, style = MaterialTheme.typography.labelLarge)
                                Text("L1 Analyse et Programmation B", style = MaterialTheme.typography.titleMedium, color = navy)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { if (hasPermissions()) startHost() else permissionLauncher.launch(requiredPermissions()) }, modifier = Modifier.weight(1f)) { Text("Créer") }
                                    OutlinedButton(onClick = { if (hasPermissions()) searchGroup() else permissionLauncher.launch(requiredPermissions()) }, modifier = Modifier.weight(1f)) { Text("Rechercher") }
                                }
                            }
                        }
                        Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
                            Row(Modifier.fillMaxWidth().padding(13.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(if (status.startsWith("Connecté")) "●" else "○", color = if (status.startsWith("Connecté")) teal else androidx.compose.ui.graphics.Color.Gray)
                                Column {
                                    Text(if (status.startsWith("Connecté")) "Groupe connecté" else "État de la connexion", style = MaterialTheme.typography.titleSmall, color = navy)
                                    Text(status, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (peers.isNotEmpty()) {
                            Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Text("Appareils à proximité", style = MaterialTheme.typography.titleSmall, color = navy)
                                    peers.toList().forEach { (id, label) ->
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(label, Modifier.padding(top = 10.dp))
                                            TextButton(onClick = { connect(id) }) { Text("Rejoindre") }
                                        }
                                    }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Discussion", style = MaterialTheme.typography.titleMedium, color = navy)
                            Text("${messages.size} message(s)", style = MaterialTheme.typography.labelMedium, color = teal)
                        }
                        Card(Modifier.weight(1f).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White)) {
                            if (messages.isEmpty()) {
                                Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.Center) {
                                    Text("Bienvenue dans le groupe", style = MaterialTheme.typography.titleSmall, color = navy)
                                    Text("Les messages apparaîtront ici quand vous serez connectés.", style = MaterialTheme.typography.bodySmall)
                                }
                            } else {
                                LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(messages) { msg ->
                                        Surface(color = androidx.compose.ui.graphics.Color(0xFFEAF4F3), shape = MaterialTheme.shapes.medium) {
                                            Text(msg, Modifier.fillMaxWidth().padding(12.dp), color = navy)
                                        }
                                    }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text("Écrire un message…") }, modifier = Modifier.weight(1f), maxLines = 3)
                            Button(onClick = { sendMessage(input); input = "" }, modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically)) { Text("Envoyer") }
                        }
                        Text("Prototype de test • l’hôte doit garder l’application ouverte", style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color.Gray)
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
