package de.foldki.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HfModelsTest {
    private val tree = """[
      {"type":"file","path":"README.md","size":100},
      {"type":"file","path":"Qwen3-4B_int8.litertlm","size":5,"lfs":{"oid":"${"a".repeat(64)}","size":4000000000}},
      {"type":"file","path":"Qwen3-4B_q4_block32_ekv4096.litertlm","size":5,"lfs":{"oid":"${"b".repeat(64)}","size":2300000000}},
      {"type":"file","path":"Qwen3-4B_f32.task","size":5,"lfs":{"oid":"${"c".repeat(64)}","size":9000000000}},
      {"type":"directory","path":"sub"}
    ]"""

    @Test fun parsesOnlyModelFilesAndRecommends4Bit() {
        val files = HfModels.parseTree("litert-community/Qwen3-4B", tree)
        assertEquals(3, files.size)
        assertEquals("Qwen3-4B_q4_block32_ekv4096.litertlm", files[0].fileName) // recommended first
        assertEquals("4-Bit", files[0].bits)
        assertEquals("b".repeat(64), files[0].sha256)
        assertEquals(2300000000L, files[0].size)
        assertFalse(files.last().usable) // .task is shown but not usable
    }

    @Test fun urls() {
        assertEquals("litert-community/Qwen3-4B", HfModels.repoFromUrl("https://huggingface.co/litert-community/Qwen3-4B/tree/main"))
        assertNull(HfModels.repoFromUrl("https://huggingface.co/models?search=litert"))
        assertEquals("a b.litertlm", HfModels.fileFromUrl("https://huggingface.co/o/m/resolve/main/a%20b.litertlm"))
        assertTrue(HfModels.resolveUrl("o/m", "a b.litertlm", "abc").endsWith("/resolve/abc/a%20b.litertlm"))
    }

    @Test fun names() {
        assertEquals("Gemma 4 E2B", HfModels.displayName(LocalModelManager.GEMMA_FILE))
        assertEquals("Qwen3.5-2B_int8 · 8-Bit", HfModels.displayName("Qwen3.5-2B_int8.litertlm"))
        assertTrue(HfModels.isThinking("Qwen3-4B-Thinking-2507_q4.litertlm"))
        assertFalse(HfModels.isThinking("gemma-4-E2B-it.litertlm"))
    }
}
