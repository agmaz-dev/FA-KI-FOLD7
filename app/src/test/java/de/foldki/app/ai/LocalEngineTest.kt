package de.foldki.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEngineTest {
    @Test fun gpuBuildNeverFallsBackToCpu() {
        val a = LocalEngine.attempts(4096, preferGpu = true, gpuOnly = true)
        assertTrue(a.all { it.backend == "GPU" })
        assertEquals(LocalEngine.Attempt("GPU", 4096), a.first())
        assertEquals(listOf(4096, 4096, 2048, 1024), a.map { it.ctx })
    }

    @Test fun otherFilesTryGpuThenCpu() {
        val a = LocalEngine.attempts(1280, preferGpu = true, gpuOnly = false)
        assertEquals(listOf("GPU", "GPU", "GPU", "CPU"), a.map { it.backend })
        assertEquals(1024, a[2].ctx)
    }

    @Test fun cpuSwitch() {
        assertEquals(listOf(LocalEngine.Attempt("CPU", 4096)), LocalEngine.attempts(4096, preferGpu = false, gpuOnly = false))
    }
}
