package app.shul.display

class ExponentialBackoff(
    private val initialDelay: Long = 30_000L,
    private val maxDelay: Long = 15 * 60_000L,
    private val multiplier: Double = 2.0
) {
    @Volatile private var currentDelay = initialDelay
    @Volatile var failureCount = 0
        private set

    fun nextDelay(): Long {
        val delay = currentDelay
        currentDelay = (currentDelay * multiplier).toLong().coerceAtMost(maxDelay)
        failureCount++
        return delay
    }

    fun reset() {
        currentDelay = initialDelay
        failureCount = 0
    }
}
