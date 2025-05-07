package com.horsenma.yourtv



import com.horsenma.yourtv.MainViewModel.Companion.CACHE_FILE_NAME
import com.horsenma.yourtv.MainViewModel.Companion.DEFAULT_CHANNELS_FILE
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.horsenma.yourtv.Utils.getUrls
import com.horsenma.yourtv.data.Global.gson
import com.horsenma.yourtv.data.Global.typeSourceList
import com.horsenma.yourtv.data.ReqSettings
import com.horsenma.yourtv.data.ReqSourceAdd
import com.horsenma.yourtv.data.ReqSources
import com.horsenma.yourtv.data.RespSettings
import com.horsenma.yourtv.data.Source
import com.horsenma.yourtv.requests.HttpClient
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets


class SimpleServer(private val context: Context, private val viewModel: MainViewModel) :
    NanoHTTPD(PORT) {
    private val handler = Handler(Looper.getMainLooper())

    init {
        try {
            start()
        } catch (e: Exception) {
            Log.e(TAG, "init", e)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/api/settings" -> handleSettings()
            "/api/sources" -> handleSources()
            "/api/import-text" -> handleImportText(session)
            "/api/import-uri" -> handleImportUri(session)
            "/api/proxy" -> handleProxy(session)
            "/api/epg" -> handleEPG(session)
            "/api/default-channel" -> handleDefaultChannel(session)
            "/api/remove-source" -> handleRemoveSource(session)
            else -> handleStaticContent()
        }
    }

    private fun handleSettings(): Response {
        val response: String
        try {
            val file = File(context.filesDir, CACHE_FILE_NAME)
            var str = if (file.exists()) {
                file.readText()
            } else {
                ""
            }
            if (str.isEmpty()) {
                str = context.resources.openRawResource(DEFAULT_CHANNELS_FILE).bufferedReader()
                    .use { it.readText() }
            }

            var history = mutableListOf<Source>()

            if (!SP.sources.isNullOrEmpty()) {
                try {
                    val sources: List<Source> = gson.fromJson(SP.sources!!, typeSourceList)
                    history = sources.toMutableList()
                } catch (e: Exception) {
                    e.printStackTrace()
                    SP.sources = SP.DEFAULT_SOURCES
                }
            }

            val respSettings = RespSettings(
                channelUri = SP.configUrl ?: "",
                channelText = str,
                channelDefault = SP.channel,
                proxy = SP.proxy ?: "",
                epg = SP.epg ?: "",
                history = history
            )
            response = gson.toJson(respSettings) ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "handleSettings", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", response)
    }

    suspend fun fetchSources(url: String): String {
        val urls = getUrls(url)

        var sources = ""
        var success = false
        for (u in urls) {
            Log.i(TAG, "request $u")
            withContext(Dispatchers.IO) {
                try {
                    val request = okhttp3.Request.Builder().url(u).build()
                    val response = HttpClient.okHttpClient.newCall(request).execute()

                    if (response.isSuccessful) {
                        sources = response.bodyAlias()?.string() ?: ""
                        success = true
                    } else {
                        Log.e(TAG, "Request status ${response.codeAlias()}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "fetchSources", e)
                }
            }

            if (success) break
        }

        return sources
    }

    private fun handleSources(): Response {
        val response = runBlocking(Dispatchers.IO) {
            val externalSources = fetchSources("https://url.horsenma.net/yourtvsources")
            val localSources = if (!SP.sources.isNullOrEmpty()) {
                try {
                    val sources = gson.fromJson(SP.sources, typeSourceList) as? List<Source>
                    sources?.map { it.uri }?.joinToString("\n") ?: ""
                } catch (e: Exception) {
                    ""
                }
            } else {
                ""
            }
            "$externalSources\n$localSources".trim()
        }
        try {
            val decoded = SourceDecoder.decodeHexSource(response) ?: response
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                decoded
            )
        } catch (e: Exception) {
            Log.e(TAG, "解碼源失敗: ${e.message}")
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                response
            )
        }
    }

    private fun handleImportText(session: IHTTPSession): Response {
        R.string.start_config_channel.showToast()
        val response = ""
        try {
            readBody(session)?.let {
                handler.post {
                    viewModel.tryStr2Channels(it, null, "")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleImportText", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", response)
    }

    private fun handleImportUri(session: IHTTPSession): Response {
        Log.d(TAG, "Received /api/import-uri request: method=${session.method}, uri=${session.uri}")
        R.string.start_config_channel.showToast()
        val response = ""
        try {
            val body = readBody(session)
            Log.d(TAG, "Request body: $body")
            if (body.isNullOrEmpty()) {
                Log.e(TAG, "Request body is null or empty")
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    MIME_PLAINTEXT,
                    "Request body is empty"
                )
            }
            val req = gson.fromJson(body, ReqSourceAdd::class.java)
            if (req == null) {
                Log.e(TAG, "Failed to parse request body: $body")
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    MIME_PLAINTEXT,
                    "Invalid request body"
                )
            }
            Log.d(TAG, "Parsed request: id=${req.id}, uri=${req.uri}")
            val uri = Uri.parse(req.uri)
            if (uri.scheme.isNullOrEmpty()) {
                Log.e(TAG, "Invalid URI: ${req.uri}")
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    MIME_PLAINTEXT,
                    "Invalid URI: ${req.uri}"
                )
            }
            handler.post {
                Log.d(TAG, "Calling importFromUri: uri=$uri, id=${req.id}")
                viewModel.importFromUri(uri, req.id)
                R.string.source_update_success.showToast()
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleImportUri error: ${e.message}", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Error processing request: ${e.message}"
            )
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", response)
    }

    private fun handleProxy(session: IHTTPSession): Response {
        try {
            readBody(session)?.let {
                handler.post {
                    val req = gson.fromJson(it, ReqSettings::class.java)
                    if (req.proxy != null) {
                        SP.proxy = req.proxy
                        R.string.default_proxy_set_success.showToast()
                        Log.i(TAG, "set proxy success")
                    } else {
                        R.string.default_proxy_set_failure.showToast()
                        Log.i(TAG, "set proxy failure")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleProxy", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        val response = ""
        return newFixedLengthResponse(Response.Status.OK, "text/plain", response)
    }

    private fun handleEPG(session: IHTTPSession): Response {
        try {
            readBody(session)?.let {
                handler.post {
                    val req = gson.fromJson(it, ReqSettings::class.java)
                    if (req.epg != null) {
                        SP.epg = req.epg
                        viewModel.updateEPG()
                        R.string.default_epg_set_success.showToast()
                    } else {
                        R.string.default_epg_set_failure.showToast()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleEPG", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        val response = ""
        return newFixedLengthResponse(Response.Status.OK, "text/plain", response)
    }

    private fun handleDefaultChannel(session: IHTTPSession): Response {
        R.string.start_set_default_channel.showToast()
        val response = ""
        try {
            readBody(session)?.let {
                handler.post {
                    val req = gson.fromJson(it, ReqSettings::class.java)
                    if (req.channel != null && req.channel > -1) {
                        SP.channel = req.channel
                        R.string.default_channel_set_success.showToast()
                    } else {
                        R.string.default_channel_set_failure.showToast()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleDefaultChannel", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", response)
    }

    private fun handleRemoveSource(session: IHTTPSession): Response {
        val response = ""
        try {
            readBody(session)?.let {
                handler.post {
                    val req = gson.fromJson(it, ReqSources::class.java)
                    Log.i(TAG, "req $req")
                    if (req.sourceId.isNotEmpty()) {
                        val res = viewModel.sources.removeSource(req.sourceId)
                        if (res) {
                            Log.i(TAG, "remove source success ${req.sourceId}")
                        } else {
                            Log.i(TAG, "remove source failure ${req.sourceId}")
                        }
                    } else {
                        Log.i(TAG, "remove source failure, sourceId is empty")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleRemoveSource", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", response)
    }

    private fun readBody(session: IHTTPSession): String? {
        val map = HashMap<String, String>()
        session.parseBody(map)
        return map["postData"]
    }

    private fun handleStaticContent(): Response {
        val html = loadHtmlFromResource(R.raw.index)
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun loadHtmlFromResource(resourceId: Int): String {
        val inputStream = context.resources.openRawResource(resourceId)
        return inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    companion object {
        const val TAG = "SimpleServer"
        const val PORT = 34567
    }
}