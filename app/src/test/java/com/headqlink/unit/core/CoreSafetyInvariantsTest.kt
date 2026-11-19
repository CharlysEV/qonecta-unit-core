package com.headqlink.unit.core

import com.andrerinas.openheadunit.aap.protocol.messages.ClusterDisplayServices
import com.andrerinas.openheadunit.decoder.MicRecorder
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import android.content.Context
import android.content.SharedPreferences

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
}
