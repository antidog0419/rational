// utils/SecurePrefs.kt
// 轻量密钥落盘保护：用 Android Keystore 的 AES-256-GCM 加密敏感串（当前用于 DeepSeek API Key），
// 密文带 "enc:" 前缀存入 SharedPreferences；密钥本身不落盘（由系统 Keystore 保管）。
// 不需要外部依赖（API 23+，minSdk 24 满足）。

package com.example.finance.utils

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecurePrefs {

    const val ENC_PREFIX = "enc:"
    private const val ALIAS = "finance_ds_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** 加密为 Base64(iv:ciphertext) 的 "enc:" 前缀串；Keystore 不可用/失败时回退原文（绝不丢数据、不空写） */
    fun encrypt(plain: String?): String? {
        if (plain.isNullOrBlank()) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val ivB64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            val ctB64 = Base64.encodeToString(ct, Base64.NO_WRAP)
            ENC_PREFIX + ivB64 + ":" + ctB64
        } catch (t: Throwable) {
            android.util.Log.w("SecurePrefs", "Keystore 不可用/加密失败，回退明文存储: ${t.message}")
            plain.trim()
        }
    }

    /** 解密 "enc:" 前缀串；非该前缀或失败返回 null */
    fun decrypt(stored: String?): String? {
        if (stored.isNullOrBlank() || !stored.startsWith(ENC_PREFIX)) return null
        return try {
            val body = stored.removePrefix(ENC_PREFIX)
            val ivB64 = body.substringBefore(':')
            val ctB64 = body.substringAfter(':')
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val ct = Base64.decode(ctB64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (t: Throwable) {
            android.util.Log.w("SecurePrefs", "解密失败: ${t.message}")
            null
        }
    }
}
