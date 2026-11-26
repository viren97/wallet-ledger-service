package dev.viren.tms.event

import dev.viren.tms.queue.MessageConsumer
import dev.viren.tms.queue.QueueMessage
import dev.viren.tms.queue.Topics
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** Async fan-out (observer): one event, many subscribers, each isolated so one failure doesn't block others. */
@Component
class TransactionEventFanOut(
    private val subscribers: List<EventSubscriber>,
    private val objectMapper: ObjectMapper,
) : MessageConsumer {
    private val log = LoggerFactory.getLogger(javaClass)

    override val topic = Topics.TRANSACTION_EVENTS

    override fun consume(message: QueueMessage) {
        val event: TransactionEvent = objectMapper.readValue(message.payload)
        subscribers.forEach { subscriber ->
            runCatching { subscriber.onEvent(event) }
                .onFailure { log.error("Subscriber {} failed for transaction {}", subscriber.name, event.transactionId, it) }
        }
    }
}
