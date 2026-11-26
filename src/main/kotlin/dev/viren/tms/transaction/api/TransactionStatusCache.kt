package dev.viren.tms.transaction.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Read-side cache for `GET /transactions/{id}`, which clients poll far more often than they write.
 *
 * Only terminal transactions (COMPLETED / FAILED) are cached: they never change again, so there is nothing to
 * invalidate and a client can never read a stale status. In-flight transactions always come from the database,
 * which also gives read-your-writes right after a submit.
 */
interface TransactionStatusCache {
    fun get(id: String): TransactionResponse?
    fun putIfTerminal(response: TransactionResponse)
}

@Component
@ConditionalOnProperty(prefix = "tms.cache.status", name = ["enabled"], havingValue = "true")
class RedisTransactionStatusCache(
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${tms.cache.status.ttl-seconds:86400}") ttlSeconds: Long,
) : TransactionStatusCache {
    private val log = LoggerFactory.getLogger(javaClass)
    private val ttl = Duration.ofSeconds(ttlSeconds)

    // Fail open: if Redis is down, reads fall through to the database instead of failing the request.
    override fun get(id: String): TransactionResponse? = runCatching {
        redis.opsForValue().get(key(id))?.let { objectMapper.readValue<TransactionResponse>(it) }
    }.onFailure { log.warn("Status cache read failed for {}: {}", id, it.toString()) }.getOrNull()

    override fun putIfTerminal(response: TransactionResponse) {
        if (!response.status.isTerminal) return
        runCatching { redis.opsForValue().set(key(response.id), objectMapper.writeValueAsString(response), ttl) }
            .onFailure { log.warn("Status cache write failed for {}: {}", response.id, it.toString()) }
    }

    private fun key(id: String) = "tms:txn:$id"
}

@Component
@ConditionalOnProperty(prefix = "tms.cache.status", name = ["enabled"], havingValue = "false", matchIfMissing = true)
class NoOpTransactionStatusCache : TransactionStatusCache {
    override fun get(id: String): TransactionResponse? = null
    override fun putIfTerminal(response: TransactionResponse) = Unit
}
