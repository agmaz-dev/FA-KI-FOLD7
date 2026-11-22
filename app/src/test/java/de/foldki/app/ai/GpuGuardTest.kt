package de.foldki.app.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuGuardTest {
    @Test fun qwenWithoutGpuIsBlockedOnGpu() {
        assertTrue(GpuGuard.blocked("Qwen3.5-2B_int8.litertlm"))
        assertTrue(GpuGuard.blocked("/data/models/qwen3-4b_q4_ekv4096.litertlm"))
        assertFalse(GpuGuard.blocked("Qwen3.5-2B_gpu_int4.litertlm"))
        assertFalse(GpuGuard.blocked("Qwen3.5-4B-Q4_K_M.gguf"))
        assertFalse(GpuGuard.blocked("gemma-4-E2B-it.litertlm"))
    }
}
