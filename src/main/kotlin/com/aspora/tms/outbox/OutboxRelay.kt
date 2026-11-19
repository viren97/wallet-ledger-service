package com.aspora.tms.outbox

import com.aspora.tms.queue.QueueMessage
import com.aspora.tms.queue.TaskQueue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Polls NEW outbox rows and hands them to the queue. Publishing happens *outside* a DB transaction
 * (no I/O while holding a transaction). If we crash between publish and markPublished the event is
 * re-sent: at-least-once delivery, consumers are idempotent.
 */
@Component
class OutboxRelay(
    private val outbox: OutboxService,
    private val queue: TaskQueue,
    @Value("\${tms.outbox.batch-size:100}") private val batchSize: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${tms.outbox.poll-interval-ms:200}")
    fun relay() {
        val batch = outbox.pending(batchSize)
        if (batch.isEmpty()) return

        val published = mutableListOf<String>()
        for (event in batch) {
            try {
                queue.publish(QueueMessage(event.eventType.topic, event.aggregateId, event.eventType.name, event.payload))
                published += event.id
            } catch (e: Exception) {
                log.warn("Outbox relay paused, queue rejected event {}: {}", event.id, e.message)
                break // keep ordering; retry the rest on the next tick
            }
        }
        outbox.markPublished(published)
    }
}
