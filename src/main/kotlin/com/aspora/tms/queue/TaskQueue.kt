package com.aspora.tms.queue

object Topics {
    const val TRANSACTION_PROCESSING = "transaction.processing"
    const val TRANSACTION_EVENTS = "transaction.events"
}

data class QueueMessage(
    val topic: String,
    /** Business key (e.g. transaction id); a broker would partition on this. */
    val key: String,
    val type: String,
    val payload: String,
    val attempt: Int = 1,
)

/** Port for async messaging. In-memory today; Kafka/SQS adapter tomorrow without touching producers/consumers. */
interface TaskQueue {
    /** @throws IllegalStateException when the queue cannot accept the message (back-pressure). */
    fun publish(message: QueueMessage)
}

/** Exactly one consumer per topic; fan-out to many listeners is done by the consumer itself. */
interface MessageConsumer {
    val topic: String
    fun consume(message: QueueMessage)
}
