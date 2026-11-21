package de.foldki.app.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * "S3-Sicherung": chats, own instructions, saved answers and the non-secret settings are packed, encrypted on the phone
 * (AES-256-GCM, key from the user's password via PBKDF2) and uploaded to the user's own S3-compatible storage
 * (AWS Signature V4, path style). Keys and the password never leave the phone; the storage only sees encrypted bytes.
 */
object S3Backup {
    private const val MAGIC = "FKS3v1"
    private const val ITER = 120_000

    fun pack(context: Context, s: Settings): ByteArray {
        val o = JSONObject()
        File(context.filesDir, "chats.json").takeIf { it.exists() }?.let { o.put("chats", it.readText()) }
        File(context.filesDir, "saved.json").takeIf { it.exists() }?.let { o.put("saved", it.readText()) }
        o.put("instructions", Instruction.listToJson(s.instructions))
        o.put("systemPrompt", s.systemPrompt)
        o.put("time", System.currentTimeMillis())
        return o.toString().toByteArray()
    }

    fun encrypt(plain: ByteArray, password: String): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val key = SecretKeySpec(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray(), salt, ITER, 256)).encoded, "AES",
        )
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return MAGIC.toByteArray() + salt + iv + c.doFinal(plain)
    }

    /** Uploads [body] as "<prefix>/foldki-sicherung.bin". Throws with a German message on errors. */
    fun upload(s: Settings, body: ByteArray) {
        val endpoint = s.s3Endpoint.trim().removeSuffix("/").ifBlank { "https://s3.amazonaws.com" }
        val key = listOf(s.s3Prefix.trim().trim('/'), "foldki-sicherung.bin").filter { it.isNotEmpty() }.joinToString("/")
        val path = "/" + enc(s.s3Bucket.trim()) + "/" + key.split('/').joinToString("/") { enc(it) }
        val url = URL(endpoint + path)
        val host = url.host + if (url.port > 0 && url.port != url.defaultPort) ":${url.port}" else ""
        val now = java.util.Date()
        val amzDate = fmt("yyyyMMdd'T'HHmmss'Z'", now)
        val day = fmt("yyyyMMdd", now)
        val region = s.s3Region.trim().ifBlank { "us-east-1" }
        val hash = hex(sha256(body))
        val canonical = "PUT\n$path\n\nhost:$host\nx-amz-content-sha256:$hash\nx-amz-date:$amzDate\n\nhost;x-amz-content-sha256;x-amz-date\n$hash"
        val scope = "$day/$region/s3/aws4_request"
        val toSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${hex(sha256(canonical.toByteArray()))}"
        var k = hmac(("AWS4" + s.s3SecretKey.trim()).toByteArray(), day)
        k = hmac(k, region); k = hmac(k, "s3"); k = hmac(k, "aws4_request")
        val sig = hex(hmac(k, toSign))
        val c = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"; doOutput = true; connectTimeout = 15_000; readTimeout = 60_000
            setRequestProperty("Host", host)
            setRequestProperty("x-amz-date", amzDate)
            setRequestProperty("x-amz-content-sha256", hash)
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("Authorization", "AWS4-HMAC-SHA256 Credential=${s.s3AccessKey.trim()}/$scope, SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=$sig")
            setFixedLengthStreamingMode(body.size)
        }
        try {
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            if (code !in 200..299) {
                val msg = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                val reason = Regex("<Code>(.*?)</Code>").find(msg)?.groupValues?.get(1) ?: msg.take(200)
                throw IllegalStateException("S3 antwortet mit Fehler $code: $reason")
            }
        } catch (e: java.net.UnknownHostException) {
            throw IllegalStateException("S3-Adresse nicht gefunden (${url.host}).")
        } finally { c.disconnect() }
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%7E", "~")
    private fun fmt(p: String, d: java.util.Date) = java.text.SimpleDateFormat(p, java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(d)
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun hmac(key: ByteArray, data: String) = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray())
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
