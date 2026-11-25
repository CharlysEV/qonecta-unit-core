package com.shilapi.xcertplay

import android.app.Application
import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Verifies [CarPlayBackgroundSession] ownership and shared-sink tracking across Activity transitions
 * so a superseded `CarPlayHostActivity.onDestroy()` cannot tear down an active resumed sink.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], manifest = Config.NONE)
class CarPlayBackgroundSessionHandoffTest {

    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null

    @After
    fun tearDown() {
        CarPlayBackgroundSession.clear()
        runCatching { controller?.close() }
        runCatching { sink?.close() }
        controller = null
        sink = null
    }

    @Test
    fun isCurrentSinkRemainsTrueAcrossOwnerTransferUntilCleared() {
        val context: Context = RuntimeEnvironment.getApplication()
        val testSink = AndroidMediaSink(surface = null).also { sink = it }
        val testController = CarPlayController(
            context = context,
            config = CarPlayRuntimeConfig(
                mfiTarget = MfiTarget.LOCAL,
                identification = Iap2IdentificationConfig(
                    name = "LeapCarPlay-Test",
                    modelIdentifier = "C10",
                    manufacturer = "Leapmotor",
                    serialNumber = "TEST-001",
                    firmwareVersion = "0.1.0",
                    hardwareVersion = "1.0",
                    carPlayUsbInterfaceNumber = 3,
                    wireless = null,
                ),
            ),
            airPlayConfig = AirPlayConfig(
                deviceName = "LeapCarPlay-Test",
                deviceId = "02:11:22:33:44:55",
                btMac = "AA:BB:CC:11:22:33",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720, fps = 60),
            ),
            identity = AirPlayIdentity.generate(),
            pairings = PairingStore(),
            listener = object : AirPlaySessionListener {},
            media = object : AirPlayMediaHandler {},
            reportStatus = {},
        ).also { controller = it }

        val firstOwner = Any()
        val secondOwner = Any()

        CarPlayBackgroundSession.store(testController, testSink, 1280, 720, firstOwner) { it() }
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertTrue(CarPlayBackgroundSession.isOwner(firstOwner))
        assertTrue(CarPlayBackgroundSession.isCurrentSink(testSink))

        // Second activity adopts the live background session and takes over ownership.
        CarPlayBackgroundSession.store(testController, testSink, 1280, 720, secondOwner) { it() }
        assertFalse("Old activity must no longer be owner", CarPlayBackgroundSession.isOwner(firstOwner))
        assertTrue("Adopted owner must hold ownership", CarPlayBackgroundSession.isOwner(secondOwner))
        assertTrue(
            "Shared sink must still be protected from superseded onDestroy()",
            CarPlayBackgroundSession.isCurrentSink(testSink),
        )

        CarPlayBackgroundSession.clear(testController)
        assertFalse(CarPlayBackgroundSession.hasSession())
        assertFalse(CarPlayBackgroundSession.isOwner(secondOwner))
        assertFalse(CarPlayBackgroundSession.isCurrentSink(testSink))
        assertFalse(CarPlayBackgroundSession.isCurrentSink(null))
    }
}
