package com.cutm.nt14.gateway.algorithms

import kotlin.collections.ArrayDeque

data class SlidingWindowResult(
    val allowed: Boolean,
    val remainingRequests: Int,
    val resetSeconds: Double
)

/**
 * Thread-safe Sliding Window counter rate limiter.
 *
 * Smooths out boundary spikes by recording timestamps of requests
 * within a rolling time window (default 60 seconds).
 */
class SlidingWindow(
    val limit: Int,
    val windowMillis: Long = 60_000L
) {
    init {
        require(limit > 0) { "Limit must be greater than zero" }
        require(windowMillis > 0) { "Window must be greater than zero" }
    }

    private val timestamps = ArrayDeque<Long>()

    private fun evictExpired(nowMillis: Long) {
        val boundary = nowMillis - windowMillis
        while (timestamps.isNotEmpty() && timestamps.first() <= boundary) {
            timestamps.removeFirst()
        }
    }

    @Synchronized
    fun allow(): SlidingWindowResult {
        val now = System.currentTimeMillis()
        evictExpired(now)

        return if (timestamps.size < limit) {
            timestamps.addLast(now)
            val remaining = limit - timestamps.size
            val resetSec = if (timestamps.isNotEmpty()) {
                ((timestamps.first() + windowMillis - now).coerceAtLeast(0L)) / 1000.0
            } else {
                windowMillis / 1000.0
            }
            SlidingWindowResult(
                allowed = true,
                remainingRequests = remaining,
                resetSeconds = resetSec
            )
        } else {
            val oldest = timestamps.first()
            val retryAfter = ((oldest + windowMillis - now).coerceAtLeast(100L)) / 1000.0
            SlidingWindowResult(
                allowed = false,
                remainingRequests = 0,
                resetSeconds = retryAfter
            )
        }
    }

    @Synchronized
    fun peek(): Triple<Int, Int, Double> {
        val now = System.currentTimeMillis()
        evictExpired(now)
        val current = timestamps.size
        val resetSec = if (timestamps.isNotEmpty()) {
            ((timestamps.first() + windowMillis - now).coerceAtLeast(0L)) / 1000.0
        } else {
            windowMillis / 1000.0
        }
        return Triple(current, limit, resetSec)
    }
}
