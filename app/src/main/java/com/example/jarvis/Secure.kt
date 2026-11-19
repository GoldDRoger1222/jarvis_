package com.example.jarvis

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object Secure {
    private const val ALIAS = "jarvis_key_v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val ex = ks.getKey(ALIAS, null)
        if (ex is SecretKey) return ex
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return g.generateKey()
    }

    fun enc(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    fun dec(b64: String): String? {
        return try {
            val all = Base64.decode(b64, Base64.NO_WRAP)
            val iv = all.copyOfRange(0, 12)
            val ct = all.copyOfRange(12, all.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun prefs(ctx: Context): SharedPreferences =
        SecurePrefs(ctx.getSharedPreferences("j", Context.MODE_PRIVATE))
}

class SecurePrefs(private val d: SharedPreferences) : SharedPreferences by d {
    override fun getString(key: String, defValue: String?): String? {
        if (key != "k") return d.getString(key, defValue)
        val enc = d.getString("kx", null)
        if (enc != null) {
            val p = Secure.dec(enc)
            if (p != null) return p
        }
        val plain = d.getString("k", null)
        if (plain != null && plain.isNotEmpty()) {
            try {
                val e2 = Secure.enc(plain)
                d.edit().putString("kx", e2).remove("k").apply()
            } catch (ex: Exception) {
            }
            return plain
        }
        return defValue
    }

    override fun contains(key: String): Boolean =
        if (key == "k") (d.contains("kx") || d.contains("k")) else d.contains(key)

    override fun edit(): SharedPreferences.Editor = SecureEditor(d.edit())
}

class SecureEditor(private val e: SharedPreferences.Editor) : SharedPreferences.Editor by e {
    override fun putString(key: String, value: String?): SharedPreferences.Editor {
        if (key == "k") {
            val v = value ?: ""
            if (v.isEmpty()) {
                e.remove("kx")
                e.remove("k")
            } else {
                val enc = try { Secure.enc(v) } catch (ex: Exception) { null }
                if (enc != null) {
                    e.putString("kx", enc)
                    e.remove("k")
                } else e.putString("k", v)
            }
        } else e.putString(key, value)
        return this
    }

    override fun putInt(key: String, value: Int): SharedPreferences.Editor { e.putInt(key, value); return this }
    override fun putLong(key: String, value: Long): SharedPreferences.Editor { e.putLong(key, value); return this }
    override fun putFloat(key: String, value: Float): SharedPreferences.Editor { e.putFloat(key, value); return this }
    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { e.putBoolean(key, value); return this }
    override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
        e.putStringSet(key, values)
        return this
    }
    override fun remove(key: String): SharedPreferences.Editor {
        e.remove(key)
        if (key == "k") e.remove("kx")
        return this
    }
    override fun clear(): SharedPreferences.Editor { e.clear(); return this }
}
