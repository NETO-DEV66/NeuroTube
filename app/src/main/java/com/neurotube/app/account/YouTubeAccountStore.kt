/*
 * Copyright (C) 2026 NeuroTube | A-EDev
 *
 * This file is part of NeuroTube (https://github.com/NETO-DEV66/NeuroTube).
 *
 * NeuroTube is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 */

package com.neurotube.app.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The signed-in YouTube account, as far as the app needs to know it.
 *
 * [cookieHeader] is the whole credential: it is a live Google session, so it is only ever written to
 * [YouTubeAccountStore]'s encrypted file and handed to the network layer. Never log it.
 *
 * [visitorData] and [dataSyncId] are the identity the session was minted with. InnerTube expects them
 * echoed back in the request context, so they are stored beside the cookie and re-applied on launch.
 */
data class YouTubeAccount(
    val name: String,
    val email: String?,
    val channelHandle: String?,
    val thumbnailUrl: String?,
    val cookieHeader: String,
    val visitorData: String? = null,
    val dataSyncId: String? = null,
)

/**
 * Every linked account, plus which one the app is currently using.
 *
 * [activeId] is a [YouTubeAccount.identity] digest rather than the address itself, because
 * preferences and this list are compared by identity, never by name.
 */
data class StoredAccounts(
    val accounts: List<YouTubeAccount>,
    val activeId: String?,
) {
    fun active(): YouTubeAccount? = accounts.firstOrNull { it.identity() == activeId }
}

/**
 * Encrypted-at-rest store for the linked YouTube sessions.
 *
 * Same scheme as `DiscordTokenStore`: AES/GCM with a key that lives in the Android keystore, written
 * through [AtomicFile] in `noBackupFilesDir` so it is never swept into a cloud backup. A file that
 * cannot be decrypted (key rotated, keystore reset) is dropped rather than surfaced as an error.
 *
 * The file holds every linked account, not just one, so switching accounts is a matter of moving
 * [StoredAccounts.activeId] rather than signing in again. A file written by the older single-account
 * build is read as a one-entry list by [YouTubeAccountCodec].
 */
class YouTubeAccountStore(
    context: Context,
) {
    private val storageFile =
        AtomicFile(File(context.applicationContext.noBackupFilesDir, FILE_NAME))

    @Synchronized
    fun save(stored: StoredAccounts) {
        val plaintext = YouTubeAccountCodec.encode(stored)

        val cipher =
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            }
        val payload =
            JSONObject()
                .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .put("ciphertext", Base64.encodeToString(cipher.doFinal(plaintext), Base64.NO_WRAP))
                .toString()
                .toByteArray(Charsets.UTF_8)

        val output = storageFile.startWrite()
        try {
            output.write(payload)
            output.flush()
            storageFile.finishWrite(output)
        } catch (error: Throwable) {
            storageFile.failWrite(output)
            throw error
        }
    }

    @Synchronized
    fun load(): StoredAccounts {
        if (!storageFile.baseFile.exists()) return StoredAccounts(emptyList(), null)

        return runCatching {
            val payload =
                JSONObject(
                    storageFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() },
                )
            val cipher =
                Cipher.getInstance(TRANSFORMATION).apply {
                    init(
                        Cipher.DECRYPT_MODE,
                        getOrCreateKey(),
                        GCMParameterSpec(
                            GCM_TAG_LENGTH_BITS,
                            Base64.decode(payload.getString("iv"), Base64.NO_WRAP),
                        ),
                    )
                }
            val plaintext =
                cipher.doFinal(Base64.decode(payload.getString("ciphertext"), Base64.NO_WRAP))
            YouTubeAccountCodec.decode(plaintext)
        }.getOrElse {
            clear()
            StoredAccounts(emptyList(), null)
        }
    }

    @Synchronized
    fun clear() {
        storageFile.delete()
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply {
                load(null)
                deleteEntry(KEY_ALIAS)
            }
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec
                    .Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val FILE_NAME = "youtube_account.enc"
        const val KEY_ALIAS = "flow_youtube_account_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
