package com.headqlink.unit.core

import com.andrerinas.openheadunit.aap.TouchCoordinateMapper
import com.andrerinas.openheadunit.aap.protocol.AudioConfigs
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.messages.ClusterDisplayServices
import com.andrerinas.openheadunit.connection.P2pSessionOwnership
import com.andrerinas.openheadunit.decoder.MicRecorder
import com.andrerinas.openheadunit.utils.AppPermissions
import com.andrerinas.openheadunit.utils.Settings
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayHid
import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.media.MicrophoneUplink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.math.BigInteger
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager

/**
 * Automated safety invariants for Qonecta Unit Core (Phase 0 — pure Android Auto + CarPlay).
 * Prevents accidental re-introduction of any mechanism linked to Leapmotor C10 instability.
 */
class CoreSafetyInvariantsTest {

    private fun fakeContext(): Context {
        val map = HashMap<String, Any?>()
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "putBoolean", "putInt", "putString", "putFloat", "putLong", "putStringSet" -> {
                    map[args[0] as String] = args[1]
                    proxy
                }
                "remove" -> {
                    map.remove(args[0] as String)
                    proxy
                }
                "clear" -> {
                    map.clear()
                    proxy
                }
                "commit" -> true
                "apply" -> null
                else -> proxy
            }
        } as SharedPreferences.Editor

        val prefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getBoolean" -> (map[args[0] as String] as? Boolean) ?: (args[1] as Boolean)
                "getInt" -> (map[args[0] as String] as? Int) ?: (args[1] as Int)
                "getString" -> (map[args[0] as String] as? String) ?: (args[1] as? String)
                "getFloat" -> (map[args[0] as String] as? Float) ?: (args[1] as Float)
                "getLong" -> (map[args[0] as String] as? Long) ?: (args[1] as Long)
                "getStringSet" -> (map[args[0] as String] as? Set<*>) ?: (args[1] as? Set<*>)
                "contains" -> map.containsKey(args[0] as String)
                "edit" -> editor
                "getAll" -> map
                else -> null
            }
        } as SharedPreferences

        return object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        }
    }

    private fun locateAppFile(relativePath: String): File {
        return listOf(
            File(relativePath),
            File("app/$relativePath"),
        ).firstOrNull { it.exists() }
            ?: error("Could not locate $relativePath from ${File(".").absolutePath}")
    }

    @Test
    fun headUnitMicProbeIsAlwaysDisabledInCoreMode() {
        val ctx = fakeContext()
        assertFalse(
            "Head-unit MicRecorder must never probe AudioRecord in Core mode",
            MicRecorder.canHeadUnitCaptureAudio(ctx)
        )
    }

    @Test
    fun clusterExtendedAndCompanionAreDisabledByDefault() {
        val ctx = fakeContext()
        val settings = Settings(ctx)
        assertFalse("Cluster secondary H.264 decoder must be disabled in Core mode", settings.clusterMapEnabled)
        assertFalse("Extended mode must be disabled in Core mode", settings.extendedMode)
        assertFalse("Companion BT bridge must be disabled in Core mode", settings.btCompanionEnabled)
        assertTrue(
            "ServiceDiscovery must not announce DISPLAY_TYPE_CLUSTER in Core mode",
            ClusterDisplayServices.build(ctx, settings).isEmpty()
        )
    }

    @Test
    fun audioVolumeOffsetsDefaultToUnityGain() {
        val ctx = fakeContext()
        val settings = Settings(ctx)
        assertEquals("mediaVolumeOffset must default to 0 (unity gain)", 0, settings.mediaVolumeOffset)
        assertEquals("assistantVolumeOffset must default to 0 (unity gain)", 0, settings.assistantVolumeOffset)
        assertEquals("navigationVolumeOffset must default to 0 (unity gain)", 0, settings.navigationVolumeOffset)
    }

    @Test
    fun manifestContainsNoBootReceiversMicrophoneFgsOrOemPermissions() {
        val xml = locateAppFile("src/main/AndroidManifest.xml").readText()
        val forbidden = listOf(
            "FOREGROUND_SERVICE_MICROPHONE",
            "RECORD_AUDIO",
            "RECEIVE_BOOT_COMPLETED",
            "directBootAware",
            "BootCompleteReceiver",
            "AutoStartReceiver",
            "WifiAutoStartReceiver",
            "ClusterMapActivity",
            "QUERY_ALL_PACKAGES",
            "REQUEST_INSTALL_PACKAGES",
            "PACKAGE_USAGE_STATS",
            "com.leapmotor.",
        )
        for (token in forbidden) {
            assertFalse("Forbidden token '$token' found in AndroidManifest.xml", xml.contains(token))
        }
    }

    @Test
    fun appOnCreateStartsNoBridgeOrOemBinders() {
        val appSource = locateAppFile("src/main/java/com/andrerinas/openheadunit/App.kt").readText()
        assertFalse("App.kt must never start CompanionBridgeHost", appSource.contains("CompanionBridgeHost"))
        assertFalse("App.kt must never start LeapVehicleDataSource", appSource.contains("LeapVehicleDataSource"))
    }

    @Test
    fun manifestDeclaresRealCarPlayVpnServiceAndMulticastPermission() {
        val xml = locateAppFile("src/main/AndroidManifest.xml").readText()
        assertTrue(
            "AndroidManifest must declare real CarPlayVpnService",
            xml.contains("com.shilapi.xcertplay.network.CarPlayVpnService")
        )
        assertFalse(
            "AndroidManifest must NOT reference legacy stub CarPlayVpnService",
            xml.contains("com.headqlink.unit.carplay.CarPlayVpnService")
        )
        assertTrue(
            "AndroidManifest must declare CHANGE_WIFI_MULTICAST_STATE for CarPlay Bonjour",
            xml.contains("android.permission.CHANGE_WIFI_MULTICAST_STATE")
        )
        assertTrue(
            "AndroidManifest must declare LeapCarPlaySessionService",
            xml.contains("com.shilapi.xcertplay.LeapCarPlaySessionService")
        )
    }

    @Test
    fun carPlayAndAndroidAutoSafetyInvariantsAreEnforced() {
        val ctx = fakeContext()
        assertFalse("BYD DiLink navigation output must be permanently disabled on C10", BydOutputSettings.available(ctx))
        val micConfig = com.shilapi.xcertplay.airplay.MicrophoneConfig(
            audioType = "speechRecognition",
            sampleRate = 16000,
            channels = 1,
            payloadType = 96,
            frameMillis = 20,
            host = java.net.InetAddress.getLoopbackAddress(),
            port = 12345,
            key = ByteArray(32),
        )
        assertFalse(
            "CarPlay MicrophoneUplink must stay disabled on C10 Audio HAL",
            MicrophoneUplink(micConfig).start()
        )
        assertNotEquals(
            "AA Assistant channel must never route to STREAM_VOICE_CALL (collides with HFP/Lyra)",
            AudioManager.STREAM_VOICE_CALL,
            AudioConfigs.stream(Channel.ID_AU1, true)
        )
        assertFalse(
            "AppPermissions must never request RECORD_AUDIO",
            AppPermissions.ALL.any { android.Manifest.permission.RECORD_AUDIO in it.permissions }
        )
    }

    @Test
    fun bplistCodecEncodesOddByteBigIntegersAndNegativeNumbers() {
        val payload = linkedMapOf<String, Any>(
            "threeByte" to BigInteger("123456"),
            "fiveByte" to BigInteger("1099511627775"),
            "negInt" to -42,
            "negLong" to -1_000_000_000_000L,
            "zero" to 0L,
        )
        val encoded = BplistCodec.encode(payload)
        @Suppress("UNCHECKED_CAST")
        val decoded = BplistCodec.decode(encoded) as Map<String, Any?>
        assertEquals(123456L, (decoded["threeByte"] as Number).toLong())
        assertEquals(1099511627775L, (decoded["fiveByte"] as Number).toLong())
        assertEquals(-42L, (decoded["negInt"] as Number).toLong())
        assertEquals(-1_000_000_000_000L, (decoded["negLong"] as Number).toLong())
        assertEquals(0L, (decoded["zero"] as Number).toLong())
    }

    @Test
    fun airPlayHidTouchReportPreservesSlotIdentityWhenFingerZeroLifts() {
        val report = AirPlayHid.touchReport(
            listOf(
                AirPlayContact(id = 1, x = 500.0, y = 300.0, down = true),
            )
        )
        // Slot 0 must remain UP (not hijacked by contact id=1), and slot 1 must be DOWN at (500, 300).
        assertEquals(0.toByte(), report[0])
        assertEquals(0.toByte(), report[1])
        assertEquals(1.toByte(), report[6])
        assertEquals(1.toByte(), report[7])
        val xSlot1 = (report[8].toInt() and 0xFF) or ((report[9].toInt() and 0xFF) shl 8)
        val ySlot1 = (report[10].toInt() and 0xFF) or ((report[11].toInt() and 0xFF) shl 8)
        assertEquals(500, xSlot1)
        assertEquals(300, ySlot1)
    }

    @Test
    fun touchCoordinateMapperHandlesDegenerateMarginsWithoutNaN() {
        val mapped = TouchCoordinateMapper.map(
            rawX = 120f,
            rawY = 80f,
            inputSurfaceWidth = 1280f,
            inputSurfaceHeight = 720f,
            negotiatedWidth = 1280,
            negotiatedHeight = 720,
            marginWidth = 1280f,
            marginHeight = 720f,
            stretchToFill = false,
            hudMirroring = false,
        )
        assertTrue("Mapped X must be clamped within valid range", mapped.x in 0..1280)
        assertTrue("Mapped Y must be clamped within valid range", mapped.y in 0..720)
    }

    @Test
    fun p2pSessionOwnershipBumpsGenerationOnStart() {
        val ownership = P2pSessionOwnership(null)
        val gen0 = ownership.generation
        ownership.start()
        val gen1 = ownership.generation
        assertTrue("start() must bump generation to invalidate stale in-flight callbacks", gen1 > gen0)
        assertFalse("Old generation must no longer be current", ownership.current(gen0))
        assertTrue("New generation must be current", ownership.current(gen1))
    }
}
