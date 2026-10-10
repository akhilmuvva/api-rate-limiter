package com.cutm.nt14.gateway.algorithms

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TokenBucketResult(
    val allowed: Boolean,
    val remainingTokens: Double,
    val retryAfterSeconds: Double
)

/**
 * Thread-safe Token Bucket rate limiter using Kotlin Mutex.
 *
 * Governs request bursts up to a maximum bucket capacity and continuously
 * refills tokens at a constant rate derived from limitPerMinute.
 */
class TokenBucket(
    val capacity: Double,
    val refillRatePerSec: Double
) {
    init {
        require(capacity > 0) { "Capacity must be greater than zero" }
        require(refillRatePerSec > 0) { "Refill rate must be greater than zero" }
    }

    private var tokens: Double = capacity
    private var lastRefillNanos: Long = System.nanoTime()
    private val mutex = Mutex()

    private fun refillUnlocked(nowNanos: Long) {
        val elapsedSec = (nowNanos - lastRefillNanos) / 1_000_000_000.0
        if (elapsedSec > 0) {
            val newTokens = elapsedSec * refillRatePerSec
            tokens = (tokens + newTokens).coerceAtMost(capacity)
            lastRefillNanos = nowNanos
        }
    }

    suspend fun allow(tokensNeeded: Double = 1.0): TokenBucketResult = mutex.withLock {
        val now = System.nanoTime()
        refillUnlocked(now)

        if (tokens >= tokensNeeded) {
            tokens = (tokens - tokensNeeded).coerceAtMost(capacity)
            TokenBucketResult(
                allowed = true,
                remainingTokens = tokens,
                retryAfterSeconds = 0.0
            )
        } else {
            val missing = tokensNeeded - tokens
            val retryAfter = if (refillRatePerSec > 0) missing / refillRatePerSec else 60.0
            TokenBucketResult(
                allowed = false,
                remainingTokens = tokens,
                retryAfterSeconds = retryAfter.coerceAtLeast(0.1)
            )
        }
    }

    suspend fun peek(): Pair<Double, Double> = mutex.withLock {
        val now = System.nanoTime()
        refillUnlocked(now)
        tokens to capacity
    }

    suspend fun refund(tokensToRefund: Double = 1.0) = mutex.withLock {
        val now = System.nanoTime()
        refillUnlocked(now)
        tokens = (tokens + tokensToRefund).coerceAtMost(capacity)
    }
}
