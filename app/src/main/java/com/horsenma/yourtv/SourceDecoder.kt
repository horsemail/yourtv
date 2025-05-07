package com.horsenma.yourtv

import java.io.BufferedReader
import java.io.FileInputStream
import java.io.FileWriter
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object SourceDecoder {
    // 从 URL 或本地文件获取十六进制源内容
    @Throws(IOException::class)
    fun fetchHexSource(urlString: String?): String {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "okhttp/3.15")
        conn.setRequestProperty(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9"
        )

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            throw IOException("HTTP 请求失败，状态码: $responseCode")
        }

        try {
            BufferedReader(
                InputStreamReader(
                    conn.inputStream,
                    StandardCharsets.UTF_8
                )
            ).use { reader ->
                val content = StringBuilder()
                var line: String?
                while ((reader.readLine().also { line = it }) != null) {
                    content.append(line)
                }
                return content.toString().trim { it <= ' ' }
            }
        } finally {
            conn.disconnect()
        }
    }

    // 读取本地文件内容
    @Throws(IOException::class)
    fun readLocalFile(filePath: String?): String {
        val content = StringBuilder()
        BufferedReader(
            InputStreamReader(FileInputStream(filePath), StandardCharsets.UTF_8)
        ).use { reader ->
            var line: String?
            while ((reader.readLine().also { line = it }) != null) {
                content.append(line)
            }
        }
        return content.toString().trim { it <= ' ' }
    }

    // 解码十六进制源为 JSON
    @Throws(Exception::class)
    fun decodeHexSource(hexSource: String?): String? {
        if (hexSource == null || hexSource.isEmpty()) {
            return null
        }

        if (isJson(hexSource)) {
            return hexSource
        }

        val content: String = hexSource

        if (content.startsWith("2423")) {
            val startIndex = content.indexOf("2324") + 4
            val endIndex = content.length - 26
            val dataHex = content.substring(startIndex, endIndex)

            val contentBytes = hexToBytes(content)
            val contentStr =
                String(contentBytes, StandardCharsets.UTF_8).lowercase(Locale.getDefault())

            val keyStart = contentStr.indexOf("$#") + 2
            val keyEnd = contentStr.indexOf("#$")
            val keyStr = contentStr.substring(keyStart, keyEnd)
            val keyBytes =
                rightPadding(keyStr.toByteArray(StandardCharsets.UTF_8), '0'.code.toByte(), 16)

            val ivStr = contentStr.substring(contentStr.length - 13)
            val ivBytes =
                rightPadding(ivStr.toByteArray(StandardCharsets.UTF_8), '0'.code.toByte(), 16)

            val dataBytes = hexToBytes(dataHex)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(ivBytes)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decryptedBytes = cipher.doFinal(dataBytes)

            return String(decryptedBytes, StandardCharsets.UTF_8)
        }

        return content
    }

    // 将解码结果保存到 JSON 文件
    @Throws(IOException::class)
    fun saveToJsonFile(jsonContent: String, fileName: String?) {
        if (jsonContent == null) {
            throw IOException("解码结果为空，无法保存")
        }
        FileWriter(fileName).use { writer ->
            writer.write(jsonContent)
        }
    }

    // 将十六进制字符串转换为字节数组
    private fun hexToBytes(hex: String): ByteArray {
        var hex = hex
        if (hex.length % 2 != 0) {
            hex = hex + "0"
        }
        val bytes = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            bytes[i / 2] = hex.substring(i, i + 2).toInt(16).toByte()
            i += 2
        }
        return bytes
    }

    // 右填充字节数组
    private fun rightPadding(input: ByteArray, padByte: Byte, length: Int): ByteArray {
        if (input.size >= length) {
            val result = ByteArray(length)
            System.arraycopy(input, 0, result, 0, length)
            return result
        }
        val padded = ByteArray(length)
        System.arraycopy(input, 0, padded, 0, input.size)
        for (i in input.size..<length) {
            padded[i] = padByte
        }
        return padded
    }

    // 检查字符串是否为 JSON 格式
    private fun isJson(str: String): Boolean {
        return str.trim { it <= ' ' }.startsWith("{") && str.trim { it <= ' ' }.endsWith("}")
    }
}