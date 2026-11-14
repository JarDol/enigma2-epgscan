package pl.jardol.e2stream

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNsdManager
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@Implements(NsdManager::class)
class TestNsdManager : ShadowNsdManager() {
    companion object {
        var service: NsdServiceInfo? = null
        var failure: Int? = null
        var stopped = false
        var started = CountDownLatch(1)
    }

    @Implementation
    fun discoverServices(type: String, protocol: Int, listener: NsdManager.DiscoveryListener) {
        val error = failure
        if (error != null) listener.onStartDiscoveryFailed(type, error)
        else {
            listener.onDiscoveryStarted(type)
            service?.let { listener.onServiceFound(it) }
        }
        started.countDown()
    }

    @Implementation
    fun resolveService(info: NsdServiceInfo, listener: NsdManager.ResolveListener) {
        listener.onServiceResolved(info)
    }

    @Implementation
    fun stopServiceDiscovery(listener: NsdManager.DiscoveryListener) {
        stopped = true
        listener.onDiscoveryStopped("_epgscan._tcp.")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [TestNsdManager::class])
class MdnsDiscoveryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        Shadows.shadowOf(wifi).setWifiState(WifiManager.WIFI_STATE_ENABLED)
        TestNsdManager.service = null
        TestNsdManager.failure = null
        TestNsdManager.stopped = false
        TestNsdManager.started = CountDownLatch(1)
    }

    private fun assertReleased() {
        assertTrue(TestNsdManager.stopped)
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        assertEquals(0, Shadows.shadowOf(wifi).activeLockCount)
    }

    @Test
    fun resolvesAdvertisedServiceAndReleasesResources() {
        TestNsdManager.service = NsdServiceInfo().apply {
            serviceName = "Receiver EPG Scan"
            serviceType = "_epgscan._tcp."
            host = InetAddress.getByName("10.0.20.12")
            port = 9000
            setAttribute("webif-port", "8080")
            setAttribute("stream-port", "8002")
        }
        val result = MdnsDiscovery.find(context)
        assertFalse(result.warning)
        assertEquals(listOf(MdnsDiscovery.Service("10.0.20.12", 9000, 8080, 8002)), result.services)
        assertReleased()
    }

    @Test
    fun startFailureCodeZeroIsReportedAndResourcesAreReleased() {
        TestNsdManager.failure = NsdManager.FAILURE_INTERNAL_ERROR
        val result = MdnsDiscovery.find(context)
        assertTrue(result.warning)
        assertTrue(result.services.isEmpty())
        assertReleased()
    }

    @Test
    fun interruptionStopsDiscoveryAndReleasesMulticastLock() {
        val worker = thread {
            try { MdnsDiscovery.find(context) } catch (_: InterruptedException) { }
        }
        assertTrue(TestNsdManager.started.await(5, TimeUnit.SECONDS))
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        assertEquals(1, Shadows.shadowOf(wifi).activeLockCount)
        worker.interrupt()
        worker.join(5000)
        assertFalse(worker.isAlive)
        assertReleased()
    }
}
