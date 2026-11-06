package com.headqlink.unit.bridge

import java.security.MessageDigest
import java.security.SecureRandom

/** Dónde guarda el coche los móviles emparejados (en Android, SharedPreferences). */
interface TokenStore {
    fun loadTokens(): Map<String, String>   // token -> nombre del móvil
    fun saveTokens(tokens: Map<String, String>)
}

/** Lo que el coche necesita para aceptar o rechazar un móvil. */
interface BridgeAuthenticator {
    fun isValidToken(token: String): Boolean
    /** Si [code] es el código vigente, lo gasta y devuelve un token nuevo para [deviceName]; si no, `null`. */
    fun consumePairCode(code: String, deviceName: String): String?
    fun isPairing(): Boolean
}

/**
 * Emparejamiento del lado del coche: un código de 6 cifras de un solo uso que caduca a los 5 minutos y, a cambio,
 * un token aleatorio que el móvil guarda para conectarse solo las próximas veces.
 */
class PairingRegistry(
    private val store: TokenStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val random: SecureRandom = SecureRandom(),
) : BridgeAuthenticator {

    private var code: String? = null
    private var codeExpiresAt: Long = 0L

    @Synchronized
    fun startPairing(): String {
        val n = random.nextInt(1_000_000)
        val c = n.toString().padStart(BridgeConstants.PAIR_CODE_LENGTH, '0')
        code = c
        codeExpiresAt = clock() + BridgeConstants.PAIR_CODE_VALIDITY_MS
        return c
    }

    @Synchronized
    fun cancelPairing() {
        code = null
        codeExpiresAt = 0L
    }

    @Synchronized
    override fun isPairing(): Boolean = code != null && clock() < codeExpiresAt

    @Synchronized
    fun currentCode(): String? = if (isPairing()) code else null

    @Synchronized
    override fun consumePairCode(code: String, deviceName: String): String? {
        val expected = this.code ?: return null
        if (clock() >= codeExpiresAt) {
            cancelPairing()
            return null
        }
        if (!constantTimeEquals(expected, code.trim())) return null
        cancelPairing()
        val token = newToken()
        val tokens = store.loadTokens().toMutableMap()
        tokens[token] = deviceName.ifBlank { "Móvil" }
        store.saveTokens(tokens)
        return token
    }

    @Synchronized
    override fun isValidToken(token: String): Boolean {
        val tokens = store.loadTokens()
        return tokens.keys.any { constantTimeEquals(it, token) }
    }

    @Synchronized
    fun pairedDevices(): Map<String, String> = store.loadTokens()

    @Synchronized
    fun forgetAll() = store.saveTokens(emptyMap())

    private fun newToken(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
