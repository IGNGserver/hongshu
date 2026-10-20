package io.hongshu.app

/** Bounded, payload-free samples. Hysteresis avoids reacting to a single long request. */
internal class LoadingNetworkProfile {
    private val samples = ArrayDeque<Long>()
    @Volatile var slow: Boolean = false
        private set

    @Synchronized fun observe(millis: Long) {
        samples.addLast(millis)
        if (samples.size > 8) samples.removeFirst()
        if (samples.size >= 3 && samples.takeLast(3).all { it >= 1200 }) slow = true
        if (samples.size >= 5 && samples.takeLast(5).all { it < 700 }) slow = false
    }
    @Synchronized fun timings(): List<Long> = samples.toList()
    @Synchronized fun reset() { samples.clear(); slow = false }
}

internal val loadingNetwork = LoadingNetworkProfile()
