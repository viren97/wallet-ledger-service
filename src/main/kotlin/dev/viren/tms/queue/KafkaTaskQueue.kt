package dev.viren.tms.queue

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * Durable [TaskQueue] on Kafka (`tms.queue.type=kafka`, enabled in the docker profile).
 *
 * - Keyed by the business key (transaction id), so all messages for one transaction land on one partition, in order.
 * - publish() waits for the broker ack (acks=all, idempotent producer). The outbox row is marked published only
 *   after that, so a crash can cause a re-send but never a loss: at-least-once, consumers are idempotent.
 */
@Component
@ConditionalOnProperty(prefix = "tms.queue", name = ["type"], havingValue = "kafka")
class KafkaTaskQueue(
    private val kafka: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    @Value("\${tms.queue.kafka.send-timeout-ms:5000}") private val sendTimeoutMs: Long,
) : TaskQueue {
    override fun publish(message: QueueMessage) {
        try {
            kafka.send(message.topic, message.key, objectMapper.writeValueAsString(message))
                .get(sendTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            throw IllegalStateException("Kafka did not accept ${message.topic}/${message.key}: ${e.message}", e)
        }
    }
}

/** Routes Kafka records to the single [MessageConsumer] registered for each topic. */
@Component
@ConditionalOnProperty(prefix = "tms.queue", name = ["type"], havingValue = "kafka")
class KafkaQueueListener(
    consumers: List<MessageConsumer>,
    private val objectMapper: ObjectMapper,
) {
    private val consumerByTopic = consumers.associateBy { it.topic }

    @KafkaListener(
        topics = [Topics.TRANSACTION_PROCESSING, Topics.TRANSACTION_EVENTS],
        groupId = "\${tms.queue.kafka.group-id:tms}",
        concurrency = "\${tms.queue.workers-per-topic:4}",
    )
    fun onMessage(record: ConsumerRecord<String, String>) {
        val message: QueueMessage = objectMapper.readValue(record.value())
        val consumer = consumerByTopic[record.topic()] ?: error("No consumer registered for topic ${record.topic()}")
        consumer.consume(message)
    }
}

@Configuration
@ConditionalOnProperty(prefix = "tms.queue", name = ["type"], havingValue = "kafka")
class KafkaQueueConfig {
    @Bean
    fun processingTopic(@Value("\${tms.queue.kafka.partitions:6}") partitions: Int): NewTopic =
        TopicBuilder.name(Topics.TRANSACTION_PROCESSING).partitions(partitions).replicas(1).build()

    @Bean
    fun eventsTopic(@Value("\${tms.queue.kafka.partitions:6}") partitions: Int): NewTopic =
        TopicBuilder.name(Topics.TRANSACTION_EVENTS).partitions(partitions).replicas(1).build()

    /**
     * Retries a failing record in place with exponential backoff, then parks it on the `<topic>.DLT` dead-letter topic.
     * Spring Boot wires this into the default listener container factory.
     */
    @Bean
    fun kafkaErrorHandler(
        template: KafkaTemplate<String, String>,
        @Value("\${tms.queue.max-attempts:3}") maxAttempts: Int,
        @Value("\${tms.queue.retry-backoff-ms:200}") backoffMs: Long,
    ): DefaultErrorHandler {
        val backOff = ExponentialBackOffWithMaxRetries(maxAttempts - 1).apply {
            initialInterval = backoffMs
            multiplier = 2.0
        }
        return DefaultErrorHandler(DeadLetterPublishingRecoverer(template), backOff)
    }
}
