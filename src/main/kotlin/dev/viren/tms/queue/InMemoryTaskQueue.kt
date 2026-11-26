package dev.viren.tms.queue

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@ConfigurationProperties("tms.queue")
data class QueueProperties(
    /** `in-memory` (default, zero dependencies) or `kafka` (docker profile). */
    val type: String = "in-memory",
    val capacity: Int = 10_000,
    val workersPerTopic: Int = 4,
    val maxAttempts: Int = 3,
    val retryBackoffMs: Long = 200,
)

/**
 * Default [TaskQueue] for local runs and tests. Messages live in memory, so anything queued is lost on restart;
 * the outbox + StuckTransactionSweeper recover those transactions. Use `tms.queue.type=kafka` for durability.
 *
 * Bounded in-memory queue per topic with a worker pool, retry with linear backoff and a dead-letter list.
 * Delivery is at-least-once (the outbox may republish), so consumers must be idempotent.
 */
@Component
@ConditionalOnProperty(prefix = "tms.queue", name = ["type"], havingValue = "in-memory", matchIfMissing = true)
class InMemoryTaskQueue(
    consumers: List<MessageConsumer>,
    private val properties: QueueProperties,
) : TaskQueue, DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(true)
    private val consumerByTopic = consumers.associateBy { it.topic }
    private val queues = consumerByTopic.keys.associateWith { LinkedBlockingQueue<QueueMessage>(properties.capacity) }
    private val workers = Executors.newFixedThreadPool(queues.size * properties.workersPerTopic)
    private val retryScheduler = Executors.newSingleThreadScheduledExecutor()
    private val deadLetters = ConcurrentLinkedQueue<QueueMessage>()

    init {
        check(consumerByTopic.size == consumers.size) { "Only one MessageConsumer per topic is allowed" }
        queues.forEach { (topic, queue) ->
            repeat(properties.workersPerTopic) { workers.submit { workerLoop(topic, queue) } }
        }
    }

    override fun publish(message: QueueMessage) {
        val queue = queues[message.topic] ?: throw IllegalStateException("No consumer registered for topic ${message.topic}")
        check(queue.offer(message)) { "Queue ${message.topic} is full" }
    }

    fun deadLetters(): List<QueueMessage> = deadLetters.toList()

    private fun workerLoop(topic: String, queue: LinkedBlockingQueue<QueueMessage>) {
        val consumer = consumerByTopic.getValue(topic)
        while (running.get()) {
            val message = try {
                queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            try {
                consumer.consume(message)
            } catch (e: Exception) {
                handleFailure(message, e)
            }
        }
    }

    private fun handleFailure(message: QueueMessage, error: Exception) {
        if (message.attempt >= properties.maxAttempts) {
            log.error("Message {} on {} dead-lettered after {} attempts", message.key, message.topic, message.attempt, error)
            deadLetters.add(message)
            return
        }
        log.warn("Message {} on {} failed (attempt {}), retrying: {}", message.key, message.topic, message.attempt, error.message)
        retryScheduler.schedule(
            { publish(message.copy(attempt = message.attempt + 1)) },
            properties.retryBackoffMs * message.attempt,
            TimeUnit.MILLISECONDS,
        )
    }

    override fun destroy() {
        running.set(false)
        retryScheduler.shutdownNow()
        workers.shutdown()
        workers.awaitTermination(5, TimeUnit.SECONDS)
    }
}
