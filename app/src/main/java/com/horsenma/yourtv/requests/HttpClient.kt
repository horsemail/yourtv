package com.horsenma.yourtv.requests

import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import com.horsenma.yourtv.SP
import com.horsenma.yourtv.Utils.formatUrl
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.util.concurrent.TimeUnit
import androidx.core.net.toUri

object HttpClient {
    const val TAG = "HttpClient"

    private val clientCache = mutableMapOf<String?, OkHttpClient>()

    val okHttpClient: OkHttpClient by lazy {
        getClientWithProxy()
    }

    internal val builder: OkHttpClient.Builder by lazy {
        createBuilder()
    }

    private fun getClientWithProxy(): OkHttpClient {
        clientCache[SP.proxy]?.let {
            return it
        }

        if (!SP.proxy.isNullOrEmpty()) {
            try {
                val proxyUri = formatUrl(SP.proxy!!).toUri()
                val proxyType = when (proxyUri.scheme) {
                    "http", "https" -> Proxy.Type.HTTP
                    "socks", "socks5" -> Proxy.Type.SOCKS
                    else -> null
                }
                proxyType?.let {
                    builder.proxy(Proxy(it, InetSocketAddress(proxyUri.host, proxyUri.port)))
                }
                Log.i(TAG, "apply proxy $proxyUri")
            } catch (e: Exception) {
                Log.e(TAG, "getClientWithProxy", e)
            }
        }

        val client = builder.build()
        clientCache[SP.proxy] = client
        return client
    }

    private fun createBuilder(): OkHttpClient.Builder {
        val trustManager = @SuppressLint("CustomX509TrustManager")
        object : X509TrustManager {
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkClientTrusted(
                chain: Array<out java.security.cert.X509Certificate>?,
                authType: String?
            ) {
            }

            @SuppressLint("TrustAllX509TrustManager")
            override fun checkServerTrusted(
                chain: Array<out java.security.cert.X509Certificate>?,
                authType: String?
            ) {
            }

            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> {
                return emptyArray()
            }
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), java.security.SecureRandom())

        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS) // 添加 30 秒超时
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .connectionSpecs(listOf(ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
            .dns(DnsCache())
            .apply { enableTls12OnPreLollipop() }
    }

    private fun OkHttpClient.Builder.enableTls12OnPreLollipop() {
        if (Build.VERSION.SDK_INT < 22) {
            try {
                val sslContext = SSLContext.getInstance("TLSv1.2")
                sslContext.init(null, null, java.security.SecureRandom())

                val trustManagerFactory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm()
                )
                trustManagerFactory.init(null as KeyStore?)
                val trustManagers = trustManagerFactory.trustManagers
                val trustManager = trustManagers[0] as X509TrustManager

                sslSocketFactory(Tls12SocketFactory(sslContext.socketFactory), trustManager)
                connectionSpecs( listOf(
                    ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                        .tlsVersions(TlsVersion.TLS_1_2)
                        .build(),
                    ConnectionSpec.COMPATIBLE_TLS,
                    ConnectionSpec.CLEARTEXT
                )
                )
            } catch (e: Exception) {
                Log.e(TAG, "enableTls12OnPreLollipop", e)
            }
        }
    }
}