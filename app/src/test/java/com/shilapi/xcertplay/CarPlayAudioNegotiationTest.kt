package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.network.CarPlayBonjourProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayAudioNegotiationTest {

    private fun sampleConfig(
        microphone: Boolean = false,
        disableAudioOutput: Boolean = false,
    ): AirPlayConfig = AirPlayConfig(
        deviceName = "LeapCarPlay",
        deviceId = "02:11:22:33:44:55",
        btMac = "AA:BB:CC:11:22:33",
        sourceVersion = "950.7.1",
        main = AirPlayDisplayConfig(widthPixels = 1920, heightPixels = 1080, fps = 60),
        microphone = microphone,
        disableAudioOutput = disableAudioOutput,
    )

    @Test
    fun infoPlistAlwaysDeclaresType100AudioInputFormatsEvenWithoutMicrophone() {
        val info = AirPlayInfoPlist.build(sampleConfig(microphone = false))
        @Suppress("UNCHECKED_CAST")
        val audioFormats = info["audioFormats"] as List<Map<String, Any?>>
        val type100Required = listOf("compatibility", "default", "telephony", "speechRecognition")
        for (audioType in type100Required) {
            val entry = audioFormats.firstOrNull {
                (it["type"] as Number).toInt() == 100 && it["audioType"] == audioType
            }
            assertNotNull("Expected type=100 entry for $audioType", entry)
            assertNotNull(
                "Expected non-null audioInputFormats on type=100 $audioType",
                entry?.get("audioInputFormats"),
            )
        }

        val type102Media = audioFormats.firstOrNull {
            (it["type"] as Number).toInt() == 102 && it["audioType"] == "media"
        }
        assertNotNull("Expected type=102 media entry", type102Media)
        assertEquals(0xc00000, (type102Media?.get("audioOutputFormats") as Number).toInt())

        @Suppress("UNCHECKED_CAST")
        val latencies = info["audioLatencies"] as List<Map<String, Any?>>
        assertTrue(
            latencies.any { (it["type"] as Number).toInt() == 102 && it["audioType"] == "media" },
        )
    }

    @Test
    fun bonjourFeaturesTxtMatchesInfoFeaturesMask() {
        val identity = AirPlayIdentity.generate()

        val audioEnabledInfo = AirPlayInfoPlist.build(sampleConfig(disableAudioOutput = false))
        assertEquals(0x615653aee2L, (audioEnabledInfo["features"] as Number).toLong())
        val audioEnabledTxt = CarPlayBonjourProtocol.airPlayTxtRecords(
            sampleConfig(disableAudioOutput = false),
            identity,
        )
        assertEquals("0x5653aee2,0x61", audioEnabledTxt["features"])

        val audioDisabledInfo = AirPlayInfoPlist.build(sampleConfig(disableAudioOutput = true))
        assertEquals(0x615203a4e2L, (audioDisabledInfo["features"] as Number).toLong())
        val audioDisabledTxt = CarPlayBonjourProtocol.airPlayTxtRecords(
            sampleConfig(disableAudioOutput = true),
            identity,
        )
        assertEquals("0x5203a4e2,0x61", audioDisabledTxt["features"])
    }

    @Test
    fun changeModesReleasesMainAudioForAirPlayOrTakesForNativeBt() {
        val airPlayCmd = AirPlaySession.buildAudioRouteChangeModesCommand(disableAudioOutput = false)
        assertEquals("changeModes", airPlayCmd["type"])
        @Suppress("UNCHECKED_CAST")
        val airPlayResources = (airPlayCmd["params"] as Map<String, Any?>)["resources"] as List<Map<String, Any?>>
        assertEquals(1, airPlayResources.size)
        assertEquals(2, airPlayResources.first()["resourceID"])
        assertEquals(2, airPlayResources.first()["transferType"])

        val nativeBtCmd = AirPlaySession.buildAudioRouteChangeModesCommand(disableAudioOutput = true)
        assertEquals("changeModes", nativeBtCmd["type"])
        @Suppress("UNCHECKED_CAST")
        val btResources = (nativeBtCmd["params"] as Map<String, Any?>)["resources"] as List<Map<String, Any?>>
        assertEquals(1, btResources.size)
        assertEquals(2, btResources.first()["resourceID"])
        assertEquals(1, btResources.first()["transferType"])
        assertEquals(100, btResources.first()["transferPriority"])
        assertEquals(100, btResources.first()["takeConstraint"])
        assertEquals(100, btResources.first()["borrowConstraint"])
        assertEquals(100, btResources.first()["unborrowConstraint"])
    }
}
