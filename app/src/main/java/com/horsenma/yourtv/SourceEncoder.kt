package com.horsenma.yourtv

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileWriter
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object SourceEncoder {
    // 根据输入类型获取 JSON 内容
    @Throws(IOException::class)
    fun getJsonContent(source: String): String {
        return if (source.startsWith("http://") || source.startsWith("https://")) {
            fetchJsonFromUrl(source)
        } else {
            readJsonFromFile(source)
        }
    }

    // 从 URL 获取 JSON
    @Throws(IOException::class)
    fun fetchJsonFromUrl(urlString: String?): String {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        val responseCode = conn.responseCode
        if (responseCode != 200) {
            throw IOException("HTTP 请求失败，状态码: $responseCode")
        }
        try {
            conn.inputStream.use { inputStream ->
                ByteArrayOutputStream().use { baos ->
                    val buffer = ByteArray(1024)
                    var bytesRead: Int
                    while ((inputStream.read(buffer).also { bytesRead = it }) != -1) {
                        baos.write(buffer, 0, bytesRead)
                    }
                    return baos.toString(StandardCharsets.UTF_8.name())
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    // 从本地文件读取 JSON
    @Throws(IOException::class)
    fun readJsonFromFile(filePath: String?): String {
        val jsonContent = StringBuilder()
        BufferedReader(
            InputStreamReader(FileInputStream(filePath), StandardCharsets.UTF_8)
        ).use { reader ->
            var line: String?
            while ((reader.readLine().also { line = it }) != null) {
                jsonContent.append(line).append("\n")
            }
        }
        return jsonContent.toString()
    }

    // 保存加密内容到文件
    @Throws(IOException::class)
    fun saveToFile(content: String?, filePath: String?) {
        FileWriter(filePath).use { writer ->
            writer.write(content)
        }
    }

    // 加密 JSON 数据
    @Throws(Exception::class)
    fun encodeJsonSource(json: String): String {
        val keyStr = "142cc6" // 6 字节
        val ivStr = "4aa31829da045" // 13 字节

        val keyBytes =
            rightPadding(keyStr.toByteArray(StandardCharsets.UTF_8), '0'.code.toByte(), 16)
        val ivBytes = rightPadding(ivStr.toByteArray(StandardCharsets.UTF_8), '0'.code.toByte(), 16)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(ivBytes)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        val encryptedBytes = cipher.doFinal(json.toByteArray(StandardCharsets.UTF_8))
        val encryptedHex = bytesToHex(encryptedBytes)

        val keyHex = bytesToHex(keyStr.toByteArray(StandardCharsets.UTF_8))
        val prefix = "2423" + keyHex + "2324"

        val ivHex = bytesToHex(ivStr.toByteArray(StandardCharsets.UTF_8))
        val suffix = ivHex

        return prefix + encryptedHex + suffix
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

    // 将字节数组转换为十六进制字符串
    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}