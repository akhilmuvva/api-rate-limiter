package com.cutm.nt14.gateway.algorithms

data class TokenBucketResult(
    val allowed: Boolean,
    val remainingTokens: Double,
    val retryAfterSeconds: Double
)

/**
 * Thread-safe Token Bucket rate limiter.
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

    private fun refillUnlocked(nowNanos: Long) {
        val elapsedSec = (nowNanos - lastRefillNanos) / 1_000_000_000.0
        if (elapsedSec > 0) {
            val newTokens = elapsedSec * refillRatePerSec
            tokens = (tokens + newTokens).coerceAtMost(capacity)
            lastRefillNanos = nowNanos
        }
    }

    @Synchronized
    fun allow(tokensNeeded: Double = 1.0): TokenBucketResult {
        val now = System.nanoTime()
        refillUnlocked(now)

        return if (tokens >= tokensNeeded) {
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

    @Synchronized
    fun peek(): Pair<Double, Double> {
        val now = System.nanoTime()
        refillUnlocked(now)
        return tokens to capacity
    }

    @Synchronized
    fun refund(tokensToRefund: Double = 1.0) {
        val now = System.nanoTime()
        refillUnlocked(now)
        tokens = (tokens + tokensToRefund).coerceAtMost(capacity)
    }
}
