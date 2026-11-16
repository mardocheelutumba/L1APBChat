package cd.eifi.l1apbchat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import android.content.pm.ServiceInfo
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import org.json.JSONObject
import java.util.UUID

class HostConnectionService : Service() {
    private val client by lazy { Nearby.getConnectionsClient(this) }
    private val peers = linkedMapOf<String, String>()
    private val pending = linkedSetOf<String>()
    private var displayName = "Participant"
    private var roomName = "Espace local"
    private var profileCode = ""
    private var hostState = STATE_ACTIVE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startHost(intent)
            ACTION_STOP -> stopSelf()
            ACTION_ACCEPT -> accept(intent.getStringExtra(EXTRA_ENDPOINT).orEmpty())
            ACTION_REJECT -> reject(intent.getStringExtra(EXTRA_ENDPOINT).orEmpty())
            ACTION_SEND -> relayFromHost(intent.getStringExtra(EXTRA_MESSAGE).orEmpty())
            ACTION_QUERY -> {
                emitStatus(getString(R.string.host_status_ready), hostState)
                emitPeerState()
            }
        }
        return START_STICKY
    }

    private fun startHost(intent: Intent) {
        displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: displayName
        roomName = intent.getStringExtra(EXTRA_ROOM) ?: roomName
        profileCode = intent.getStringExtra(EXTRA_CODE).orEmpty()
        getSharedPreferences("student_profile", MODE_PRIVATE).edit().putBoolean("host_active", true).apply()
        createChannel()
        val notification = notification(getString(R.string.host_notification_title), getString(R.string.host_notification_text))
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else startForeground(NOTIFICATION_ID, notification)
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        client.startAdvertising(displayName, serviceIdFor(roomName), lifecycle, options)
            .addOnSuccessListener { hostState = STATE_ACTIVE; emitStatus(getString(R.string.host_status_ready), hostState) }
            .addOnFailureListener { hostState = STATE_FAILED; getSharedPreferences("student_profile", MODE_PRIVATE).edit().putBoolean("host_active", false).apply(); emitStatus(getString(R.string.host_status_failed, it.localizedMessage ?: getString(R.string.nearby_error)), hostState) }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            pending.add(id)
            val accept = actionIntent(ACTION_ACCEPT, id, 1)
            val reject = actionIntent(ACTION_REJECT, id, 2)
            val n = NotificationCompat.Builder(this@HostConnectionService, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(getString(R.string.host_join_request))
                .setContentText(info.endpointName)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setAutoCancel(true)
                .addAction(0, getString(R.string.accept), accept)
                .addAction(0, getString(R.string.reject), reject)
                .build()
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID + 1, n)
        }
        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                peers[id] = "Participant connecté"
                emitPeerState()
                emitStatus(getString(R.string.host_status_connected, peers.size), STATE_ACTIVE)
                val welcome = JSONObject().put("id", UUID.randomUUID().toString()).put("name", "Espace Étudiant").put("text", "Bienvenue dans $roomName !").put("time", System.currentTimeMillis())
                client.sendPayload(id, Payload.fromBytes(welcome.toString().toByteArray()))
            } else {
                peers.remove(id)
                emitPeerState()
                emitStatus(getString(R.string.host_status_failed_generic), STATE_ACTIVE)
            }
        }
        override fun onDisconnected(id: String) {
            peers.remove(id)
            emitPeerState()
            emitStatus(getString(R.string.host_status_disconnected), STATE_ACTIVE)
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val raw = bytes.toString(Charsets.UTF_8)
            val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return
            if (obj.optString("id").isBlank()) return
            emit(ACTION_MESSAGE, raw)
            peers.keys.filter { it != id }.forEach { client.sendPayload(it, Payload.fromBytes(bytes)) }
        }
        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) = Unit
    }

    private fun accept(id: String) {
        if (id.isNotBlank() && pending.remove(id)) client.acceptConnection(id, payloads)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID + 1)
    }
    private fun reject(id: String) {
        if (id.isNotBlank() && pending.remove(id)) client.rejectConnection(id)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID + 1)
    }
    private fun relayFromHost(raw: String) {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        peers.keys.forEach { client.sendPayload(it, Payload.fromBytes(bytes)) }
    }

    private fun emitPeerState(): Unit {
        val ids = org.json.JSONArray()
        peers.keys.forEach { ids.put(it) }
        emit(ACTION_PEERS, JSONObject().put("count", peers.size).put("ids", ids).toString())
    }
    private fun emit(action: String, value: String) {
        sendBroadcast(Intent(action).setPackage(packageName).putExtra(EXTRA_VALUE, value))
    }
    private fun emitStatus(value: String, state: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_VALUE, value).putExtra(EXTRA_STATE, state))
    }
    private fun actionIntent(action: String, endpoint: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, HostConnectionService::class.java).setAction(action).putExtra(EXTRA_ENDPOINT, endpoint)
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
    }
    private fun notification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val stop = actionIntent(ACTION_STOP, "", 3)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title).setContentText(text).setContentIntent(open)
            .setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.host_close_room), stop).build()
    }
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.host_channel_name), NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
    override fun onDestroy() {
        client.stopAdvertising(); client.stopAllEndpoints()
        getSharedPreferences("student_profile", MODE_PRIVATE).edit().putBoolean("host_active", false).apply()
        emitStatus(getString(R.string.host_status_closed), STATE_CLOSED)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID + 1)
        super.onDestroy()
    }
    companion object {
        const val SERVICE_ID = "cd.localchat.app.localchat"
        private fun serviceIdFor(room: String): String {
            val normalized = room.trim().lowercase(java.util.Locale.ROOT).ifBlank { "salon ouvert" }
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
                .take(12).joinToString("") { "%02x".format(it) }
            return "cd.localchat.app.r$hash"
        }
        const val ACTION_START = "cd.localchat.app.HOST_START"
        const val ACTION_STOP = "cd.localchat.app.HOST_STOP"
        const val ACTION_ACCEPT = "cd.localchat.app.HOST_ACCEPT"
        const val ACTION_REJECT = "cd.localchat.app.HOST_REJECT"
        const val ACTION_SEND = "cd.localchat.app.HOST_SEND"
        const val ACTION_QUERY = "cd.localchat.app.HOST_QUERY"
        const val ACTION_STATUS = "cd.localchat.app.HOST_STATUS"
        const val ACTION_PEERS = "cd.localchat.app.HOST_PEERS"
        const val ACTION_MESSAGE = "cd.localchat.app.HOST_MESSAGE"
        const val EXTRA_ENDPOINT = "endpoint"
        const val EXTRA_DISPLAY_NAME = "display_name"
        const val EXTRA_ROOM = "room_name"
        const val EXTRA_CODE = "profile_code"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_VALUE = "value"
        const val EXTRA_STATE = "state"
        const val STATE_ACTIVE = "active"
        const val STATE_FAILED = "failed"
        const val STATE_CLOSED = "closed"
        private const val CHANNEL_ID = "host_connection"
        private const val NOTIFICATION_ID = 7301
        fun start(context: android.content.Context, name: String, room: String, code: String) {
            val intent = Intent(context, HostConnectionService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_DISPLAY_NAME, name).putExtra(EXTRA_ROOM, room).putExtra(EXTRA_CODE, code)
            ContextCompat.startForegroundService(context, intent)
        }
        fun stop(context: android.content.Context) {
            context.startService(Intent(context, HostConnectionService::class.java).setAction(ACTION_STOP))
        }
        fun send(context: android.content.Context, rawMessage: String) {
            context.startService(Intent(context, HostConnectionService::class.java).setAction(ACTION_SEND).putExtra(EXTRA_MESSAGE, rawMessage))
        }
    }
}
