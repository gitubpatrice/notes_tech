package com.filestech.notes_tech.security.applock

import com.filestech.notes_tech.core.crypto.SecretBytes
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A keystore that computes a real HMAC-SHA256 under a key held in memory: what the device does,
 * minus the secure hardware. Records every MAC input.
 */
internal class JvmHmacKeystore : AppLockKeystore {

    private var key: ByteArray? = null
    val inputs = mutableListOf<ByteArray>()

    override fun ensureKey() {
        if (key == null) key = SecretBytes.randomBytes(KEY_BYTES)
    }

    override fun mac(data: ByteArray): ByteArray {
        val current = key ?: throw AppLockKeyMissingException()
        inputs += data.copyOf()
        return Mac.getInstance(HMAC).apply { init(SecretKeySpec(current, HMAC)) }.doFinal(data)
    }

    override fun deleteKey() {
        key = null
    }

    override fun hasKey(): Boolean = key != null

    private companion object {
        const val HMAC = "HmacSHA256"
        const val KEY_BYTES = 32
    }
}

/** The PIN verifier, its stored form, the PIN rules and the waits — everything below the manager. */
class AppLockPinTest {

    private val keystore = JvmHmacKeystore()
    private val verifier = AppLockPinVerifier(keystore)

    @Test
    @DisplayName("a verifier accepts the PIN it was made from, and no other")
    fun accepts_only_its_pin() {
        val stored = verifier.create("2468")

        assertThat(verifier.matches("2468", stored)).isTrue()
        assertThat(verifier.matches("2469", stored)).isFalse()
        assertThat(verifier.matches("24680", stored)).isFalse()
    }

    /**
     * The whole reason for the Keystore key: the value on disk must not let anyone check a PIN away
     * from this device. Same PIN, same salt, another device key → no match.
     */
    @Test
    @DisplayName("the stored verifier is useless without this device's key")
    fun useless_without_the_device_key() {
        val stored = verifier.create("2468")
        val elsewhere = AppLockPinVerifier(JvmHmacKeystore().apply { ensureKey() })

        assertThat(elsewhere.matches("2468", stored)).isFalse()
    }

    @Test
    @DisplayName("a missing key is reported as such, never read as a wrong PIN")
    fun missing_key_is_not_a_wrong_pin() {
        val stored = verifier.create("2468")
        keystore.deleteKey()

        assertThrows(AppLockKeyMissingException::class.java) { verifier.matches("2468", stored) }
    }

    @Test
    @DisplayName("two verifiers of the same PIN differ: the salt is fresh each time")
    fun fresh_salt_each_time() {
        assertThat(verifier.create("2468").encode()).isNotEqualTo(verifier.create("2468").encode())
    }

    @Test
    @DisplayName("a candidate that is not a PIN is refused without a key operation")
    fun malformed_candidate_costs_nothing() {
        val stored = verifier.create("2468")
        val before = keystore.inputs.size

        assertThat(verifier.matches("24a8", stored)).isFalse()
        assertThat(verifier.matches("", stored)).isFalse()
        assertThat(keystore.inputs).hasSize(before)
    }

    /** The label keeps this MAC from ever being confused with another use of the same key. */
    @Test
    @DisplayName("the MAC input is the label followed by the 32-byte derived key")
    fun mac_input_is_domain_separated() {
        verifier.create("2468")

        val label = AppLockParams.MAC_LABEL.toByteArray(Charsets.UTF_8)
        val input = keystore.inputs.single()
        assertThat(input.copyOfRange(0, label.size)).isEqualTo(label)
        assertThat(input.size).isEqualTo(label.size + DERIVED_KEY_BYTES)
    }

    @Test
    @DisplayName("a stored PIN survives its encoding")
    fun encode_decode_round_trip() {
        val stored = verifier.create("2468")
        val decoded = StoredPin.decode(stored.encode())

        assertThat(decoded).isNotNull()
        assertThat(decoded!!.salt).isEqualTo(stored.salt)
        assertThat(decoded.tag).isEqualTo(stored.tag)
        assertThat(verifier.matches("2468", decoded)).isTrue()
    }

    /** Each of these must come back `null` — which the store turns into "locked, erase only". */
    @Test
    @DisplayName("anything that is not a well-formed v1 value is refused")
    fun malformed_values_are_refused() {
        val salt = "00".repeat(AppLockParams.SALT_BYTES)
        val tag = "11".repeat(AppLockParams.TAG_BYTES)

        assertThat(StoredPin.decode("v1:$salt:$tag")).isNotNull()
        for (value in listOf(
            "",
            "v1",
            "v2:$salt:$tag",
            "v1:$salt",
            "v1:$salt:$tag:00",
            "v1:${salt.dropLast(2)}:$tag",
            "v1:$salt:${tag}00",
            "v1:$salt:${"zz".repeat(AppLockParams.TAG_BYTES)}",
        )) {
            assertThat(StoredPin.decode(value)).isNull()
        }
    }

    @Test
    @DisplayName("a stored PIN does not print its bytes")
    fun to_string_leaks_nothing() {
        val stored = verifier.create("2468")

        assertThat(stored.toString()).doesNotContain(SecretBytes.toHex(stored.tag))
        assertThat(stored.toString()).doesNotContain(SecretBytes.toHex(stored.salt))
    }

    /**
     * ⚠️ `Char.isDigit` would accept the Arabic-Indic digits: they are digits, but not the ones the
     * keypad types, and a PIN made of them could never be typed again.
     */
    @Test
    @DisplayName("a PIN is 4 to 6 ASCII digits")
    fun pin_rules() {
        assertThat(AppLockParams.isValidPin("1234")).isTrue()
        assertThat(AppLockParams.isValidPin("123456")).isTrue()
        assertThat(AppLockParams.isValidPin("123")).isFalse()
        assertThat(AppLockParams.isValidPin("1234567")).isFalse()
        assertThat(AppLockParams.isValidPin("12 4")).isFalse()
        val arabicIndic = String(CharArray(4) { Char(ARABIC_INDIC_ONE + it) })
        assertThat(AppLockParams.isValidPin(arabicIndic)).isFalse()
    }

    @Test
    @DisplayName("five free attempts, then 30 s doubling at each failure, capped at one hour")
    fun throttle_table() {
        val expected = mapOf(
            0 to 0L,
            4 to 0L,
            5 to 30_000L,
            6 to 60_000L,
            7 to 120_000L,
            11 to 1_920_000L,
            12 to 3_600_000L,
            100 to 3_600_000L,
            Int.MAX_VALUE to 3_600_000L,
        )
        expected.forEach { (failures, wait) ->
            assertThat(AppLockThrottle.delayAfter(failures)).isEqualTo(wait)
        }
    }

    private companion object {
        const val DERIVED_KEY_BYTES = 32
        const val ARABIC_INDIC_ONE = 0x0661
    }
}
