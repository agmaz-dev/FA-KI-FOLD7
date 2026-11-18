package de.foldki.app.ai

/**
 * CPU load of this app in percent of the whole processor (all cores = 100 %), from /proc/self/stat.
 * Android gives apps no access to the GPU load; a low CPU value while the model answers on the GPU
 * shows that the graphics chip does the work.
 */
class CpuMeter {
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private val tick: Long = runCatching { android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1L)
    private var lastCpu = cpuMs()
    private var lastWall = android.os.SystemClock.elapsedRealtime()
    private val startCpu = lastCpu
    private val startWall = lastWall

    /** Load since the previous call (or since creation). */
    fun sample(): Int {
        val c = cpuMs(); val w = android.os.SystemClock.elapsedRealtime()
        val pct = pct(c - lastCpu, w - lastWall)
        lastCpu = c; lastWall = w
        return pct
    }

    /** Average load since creation. */
    fun average(): Int = pct(cpuMs() - startCpu, android.os.SystemClock.elapsedRealtime() - startWall)

    private fun pct(cpu: Long, wall: Long): Int = if (wall <= 0 || cpu < 0) 0 else ((cpu * 100.0) / (wall * cores)).toInt().coerceIn(0, 100)

    /** utime + stime of this process in milliseconds. */
    private fun cpuMs(): Long = runCatching {
        val s = java.io.File("/proc/self/stat").readText()
        // Fields after the process name (which is in parentheses and may contain spaces).
        val f = s.substring(s.lastIndexOf(')') + 2).split(' ')
        (f[11].toLong() + f[12].toLong()) * 1000L / tick
    }.getOrDefault(0L)
}
