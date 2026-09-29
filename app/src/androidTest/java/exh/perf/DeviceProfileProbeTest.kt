package exh.perf

import android.app.ActivityManager
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceProfileProbeTest {

    @Test
    fun reportMemoryProfile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activityManager = instrumentation.targetContext.getSystemService(ActivityManager::class.java)

        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putInt("memoryClassMb", activityManager.memoryClass)
                putInt("largeMemoryClassMb", activityManager.largeMemoryClass)
                putString("isLowRamDevice", activityManager.isLowRamDevice.toString())
            },
        )

        assertTrue(activityManager.memoryClass > 0)
        assertTrue(activityManager.largeMemoryClass >= activityManager.memoryClass)
    }
}
