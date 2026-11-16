package app.fandai.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class GatewayService : Service() {
    private var server: NanoHTTPD? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("反代 AI API")
                .setContentText("本地网关正在运行")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("反代 AI API")
                .setContentText("本地网关正在运行")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .build()
        }
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        server?.stop()
        startGateway()
        return START_NOT_STICKY
    }

    private fun startGateway() {
        val config = GatewayConfig.load(this)
        val host = if (config.lanEnabled) "0.0.0.0" else "127.0.0.1"
        server = object : NanoHTTPD(host, PORT) {
            override fun serve(session: IHTTPSession): Response {
                if (session.uri == "/health") {
                    return json(Response.Status.OK, """{"status":"ok","service":"fandai-ai-api"}""")
                }
                if (!authorized(session, config.gatewayKey)) {
                    return error(Response.Status.UNAUTHORIZED, "invalid_gateway_key", "Missing or invalid gateway API key")
                }
                return try {
                    when {
                        session.method == Method.GET && session.uri == "/v1/models" -> models(config)
                        session.method == Method.POST && session.uri == "/v1/chat/completions" ->
                            chatCompletions(session, config)
                        session.uri == "/proxy" || session.uri.startsWith("/proxy/") ->
                            proxyRequest(session, config)
                        else -> error(Response.Status.NOT_FOUND, "not_found", "Route not found")
                    }
                } catch (e: IllegalArgumentException) {
                    error(Response.Status.BAD_REQUEST, "invalid_request", e.message ?: "Invalid request")
                } catch (e: Exception) {
                    error(Response.Status.INTERNAL_ERROR, "gateway_error", e.message ?: "Gateway error")
                }
            }
        }.also { it.start(SOCKET_READ_TIMEOUT, false) }
    }

    private fun authorized(session: IHTTPSession, expected: String): Boolean {
        val header = session.headers["authorization"] ?: return false
        val prefix = "Bearer "
        if (!header.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) return false
        val supplied = header.substring(prefix.length).trim()
        if (supplied.isBlank()) return false
        return MessageDigest.isEqual(
            supplied.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8)
        )
    }

    private fun models(config: GatewayConfig): Response {
        val data = JSONArray()
        config.models.keys.sorted().forEach { name ->
            data.put(JSONObject().put("id", name).put("object", "model").put("created", 0).put("owned_by", "fandai"))
        }
        return json(Response.Status.OK, JSONObject().put("object", "list").put("data", data).toString())
    }

    private fun chatCompletions(session: IHTTPSession, config: GatewayConfig): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val raw = files["postData"] ?: throw IllegalArgumentException("Request body is empty")
        val payload = JSONObject(raw)
        val publicModel = payload.optString("model")
        val route = config.models[publicModel]
            ?: throw IllegalArgumentException("Unknown model: $publicModel")
        val provider = config.providers[route.provider]
            ?: throw IllegalArgumentException("Provider is not configured: ${route.provider}")
        payload.put("model", route.upstreamModel)

        val mediaType = (session.headers["content-type"] ?: "application/json").toMediaTypeOrNull()
        val requestBody = payload.toString().toRequestBody(mediaType)
        val upstream = Request.Builder()
            .url(provider.baseUrl + "/chat/completions")
            .header("Authorization", "Bearer ${provider.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", session.headers["accept"] ?: "application/json")
            .post(requestBody)
            .build()

        val upstreamResponse = client.newCall(upstream).execute()
        val status = Response.Status.lookup(upstreamResponse.code) ?: Response.Status.INTERNAL_ERROR
        val body = upstreamResponse.body
        if (body == null) {
            upstreamResponse.close()
            return error(Response.Status.INTERNAL_ERROR, "empty_upstream_response", "Upstream returned an empty response")
        }
        val contentType = upstreamResponse.header("Content-Type") ?: "application/json"
        return if (contentType.contains("text/event-stream", ignoreCase = true)) {
            NanoHTTPD.newChunkedResponse(status, contentType, ClosingInputStream(body.byteStream()) {
                upstreamResponse.close()
            })
        } else {
            val text = body.string()
            upstreamResponse.close()
            NanoHTTPD.newFixedLengthResponse(status, contentType, text)
        }
    }

    /**
     * Explicit reverse-proxy route:
     * /proxy/{provider}/{upstream-path}?query
     *
     * The configured provider base URL is the only upstream host. Incoming
     * Authorization is replaced with the provider's configured API key.
     */
    private fun proxyRequest(session: IHTTPSession, config: GatewayConfig): Response {
        val route = session.uri.removePrefix("/proxy").trimStart('/')
        val providerName = route.substringBefore('/')
        require(providerName.isNotBlank()) { "Proxy route must include a provider name" }
        val provider = config.providers[providerName]
            ?: throw IllegalArgumentException("Unknown provider: $providerName")
        val upstreamPath = route.substringAfter('/', "")
        val target = buildString {
            append(provider.baseUrl.trimEnd('/'))
            if (upstreamPath.isNotEmpty()) {
                append('/')
                append(upstreamPath)
            }
            session.queryParameterString.takeIf { it.isNotBlank() }?.let {
                append('?')
                append(it)
            }
        }

        val method = session.method.name
        val contentType = session.headers["content-type"] ?: "application/octet-stream"
        val requestBody = if (method == "GET" || method == "HEAD") {
            null
        } else {
            val files = HashMap<String, String>()
            session.parseBody(files)
            (files["postData"] ?: "").toRequestBody(contentType.toMediaTypeOrNull())
        }

        val builder = Request.Builder().url(target)
        session.headers.forEach { (name, value) ->
            val normalized = name.lowercase()
            if (normalized !in REQUEST_HOP_BY_HOP_HEADERS &&
                normalized !in setOf("host", "content-length", "authorization", "proxy-authorization", "accept-encoding")
            ) {
                builder.header(name, value)
            }
        }
        builder.header("Authorization", "Bearer ${provider.apiKey}")
        builder.method(method, requestBody)

        val upstreamResponse = client.newCall(builder.build()).execute()
        val body = upstreamResponse.body
        val status = Response.Status.lookup(upstreamResponse.code) ?: Response.Status.BAD_GATEWAY
        if (body == null) {
            upstreamResponse.close()
            return NanoHTTPD.newFixedLengthResponse(status, "application/octet-stream", "")
        }

        val responseType = upstreamResponse.header("Content-Type") ?: "application/octet-stream"
        val response = NanoHTTPD.newChunkedResponse(
            status,
            responseType,
            ClosingInputStream(body.byteStream()) { upstreamResponse.close() }
        )
        for (index in 0 until upstreamResponse.headers.size) {
            val name = upstreamResponse.headers.name(index)
            val normalized = name.lowercase()
            if (normalized !in RESPONSE_HOP_BY_HOP_HEADERS &&
                normalized !in setOf("content-length", "content-encoding")
            ) {
                response.addHeader(name, upstreamResponse.headers.value(index))
            }
        }
        return response
    }

    private fun json(status: Response.IStatus, body: String): Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", body)

    private fun error(status: Response.IStatus, type: String, message: String): Response {
        val body = JSONObject().put(
            "error",
            JSONObject().put("message", message).put("type", type)
        )
        return json(status, body.toString())
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        client.dispatcher.cancelAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "API 网关", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private class ClosingInputStream(
        input: InputStream,
        private val onClose: () -> Unit
    ) : FilterInputStream(input) {
        override fun close() {
            try { super.close() } finally { onClose() }
        }
    }

    companion object {
        private const val PORT = 8765
        private const val SOCKET_READ_TIMEOUT = 5_000
        private const val CHANNEL_ID = "gateway"
        private const val NOTIFICATION_ID = 8765

        private val REQUEST_HOP_BY_HOP_HEADERS = setOf(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade"
        )
        private val RESPONSE_HOP_BY_HOP_HEADERS = setOf(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade"
        )
    }
}
