package com.catkiss.screenmate

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Config(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secrets = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    var visionModel: String
        get() = prefs.getString("visionModel", "")!!
        set(v) { prefs.edit().putString("visionModel", v).apply() }
    var relayUrl: String
        get() = prefs.getString("relayUrl", "https://wy.aiwangyou.cc/v1/chat/completions")!!
        set(v) { prefs.edit().putString("relayUrl", v).apply() }
    var relayModel: String
        get() = prefs.getString("relayModel", "")!!
        set(v) { prefs.edit().putString("relayModel", v).apply() }
    var deepUrl: String
        get() = prefs.getString("deepUrl", "https://api.deepseek.com/chat/completions")!!
        set(v) { prefs.edit().putString("deepUrl", v).apply() }
    var deepModel: String
        get() = prefs.getString("deepModel", "deepseek-chat")!!
        set(v) { prefs.edit().putString("deepModel", v).apply() }
    var persona: String
        get() = prefs.getString("persona", "你是陪我看故事的伙伴，温柔、自然、有自己的感受。用简短中文交流，别反复追问。")!!
        set(v) { prefs.edit().putString("persona", v.take(8000)).apply() }
    var intervalSeconds: Int
        get() = prefs.getInt("interval", 15).coerceIn(10, 120)
        set(v) { prefs.edit().putInt("interval", v.coerceIn(10, 120)).apply() }
    var commentSeconds: Int
        get() = prefs.getInt("comments", 90).coerceIn(30, 600)
        set(v) { prefs.edit().putInt("comments", v.coerceIn(30, 600)).apply() }
    var left: Boolean
        get() = prefs.getBoolean("left", false)
        set(v) { prefs.edit().putBoolean("left", v).apply() }
    var flip: Boolean
        get() = prefs.getBoolean("flip", false)
        set(v) { prefs.edit().putBoolean("flip", v).apply() }
    var dailyCap: Int
        get() = prefs.getInt("cap", 200).coerceIn(1, 5000)
        set(v) { prefs.edit().putInt("cap", v.coerceIn(1, 5000)).apply() }
    var dialogueTop: Int
        get() = prefs.getInt("dialogueTop", 55).coerceIn(0, 90)
        set(v) { prefs.edit().putInt("dialogueTop", v.coerceIn(0, 90)).apply() }
    var dialogueBottom: Int
        get() = prefs.getInt("dialogueBottom", 98).coerceIn(10, 100)
        set(v) { prefs.edit().putInt("dialogueBottom", v.coerceIn(10, 100)).apply() }
    fun petPosition(landscape: Boolean): Pair<Float, Float>? {
        val prefix = if(landscape) "petLandscape" else "petPortrait"
        if(!prefs.contains(prefix+"X")) return null
        return prefs.getFloat(prefix+"X", .9f) to prefs.getFloat(prefix+"Y", .9f)
    }
    fun savePetPosition(landscape: Boolean, x: Float, y: Float) {
        val prefix = if(landscape) "petLandscape" else "petPortrait"
        prefs.edit().putFloat(prefix+"X",x.coerceIn(0f,1f)).putFloat(prefix+"Y",y.coerceIn(0f,1f)).apply()
    }
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("screenmate.keys", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("screenmate.keys", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    @Synchronized fun secret(name: String): String {
        val stored = secrets.getString(name, null) ?: return ""
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }.getOrDefault("")
    }
    @Synchronized fun setSecret(name: String, value: String) {
        if (value.isBlank()) { secrets.edit().remove(name).commit(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(value.trim().toByteArray())
        check(secrets.edit().putString(name, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit())
    }
    fun ready(): String? = when {
        secret("vision").isBlank() -> "请填写官方 Gemini API Key"
        !Endpoints.model(visionModel) -> "请读取并选择可用的官方识图模型"
        secret("relay").isBlank() || relayModel.isBlank() -> "请填写第二通道 Key 和模型名"
        !Endpoints.validate(relayUrl) -> "第二通道需填写不带凭据的完整 HTTPS 接口地址"
        secret("deep").isBlank() -> "请填写 DeepSeek Key，用于整理记忆和回复失败兜底"
        !Endpoints.validate(deepUrl) || deepModel.isBlank() -> "请检查 DeepSeek 地址和模型"
        else -> null
    }
}
