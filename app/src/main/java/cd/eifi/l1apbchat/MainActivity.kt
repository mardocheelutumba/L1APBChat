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
private const val GROUP_NAME = "L1 AP B"

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
                    .setMessage("${info.endpointName} veut rejoindre L1 AP B.")
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
        val obj = JSONObject().put("id", UUID.randomUUID().toString()).put("name", "L1 AP B").put("text", "Bienvenue dans le groupe !")
        client.sendPayload(endpointId, Payload.fromBytes(obj.toString().toByteArray()))
    }

    private fun startHost() {
        if (!hasPermissions()) { status = "Autorise les permissions demandées pour continuer."; return }
        isHost = true
        status = "Création du groupe…"
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        client.startAdvertising(nickname, SERVICE_ID, connectionLifecycle, options)
            .addOnSuccessListener { status = "Groupe L1 AP B actif — garde cette application ouverte" }
            .addOnFailureListener { status = "Échec du démarrage : ${it.localizedMessage}" }
    }

    private fun searchGroup() {
        if (!hasPermissions()) { status = "Autorise les permissions demandées pour continuer."; return }
        isHost = false
        status = "Recherche du groupe L1 AP B…"
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
            MaterialTheme {
                var name by remember { mutableStateOf("") }
                var input by remember { mutableStateOf("") }
                val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
                    if (results.values.all { it }) status = "Permissions accordées. Choisis créer ou rejoindre le groupe."
                    else status = "Certaines permissions sont refusées : la connexion hors ligne ne pourra pas démarrer."
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("L1 AP B – Tchat local", style = MaterialTheme.typography.headlineSmall)
                        OutlinedTextField(value = name, onValueChange = { name = it; nickname = it.ifBlank { "Étudiant" } }, label = { Text("Ton nom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { if (hasPermissions()) startHost() else permissionLauncher.launch(requiredPermissions()) }) { Text("Créer le groupe") }
                            OutlinedButton(onClick = { if (hasPermissions()) searchGroup() else permissionLauncher.launch(requiredPermissions()) }) { Text("Rechercher") }
                        }
                        Text(status, style = MaterialTheme.typography.bodySmall)
                        if (peers.isNotEmpty()) {
                            Text("Appareils trouvés / connectés")
                            peers.toList().forEach { (id, label) ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(label)
                                    TextButton(onClick = { connect(id) }) { Text("Rejoindre") }
                                }
                            }
                        }
                        HorizontalDivider()
                        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                            items(messages) { Text(it, Modifier.padding(vertical = 5.dp)) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text("Message") }, modifier = Modifier.weight(1f))
                            Button(onClick = { sendMessage(input); input = "" }) { Text("Envoyer") }
                        }
                        Text("Prototype : garde l’application ouverte. Teste avec l’accord des membres.", style = MaterialTheme.typography.labelSmall)
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
