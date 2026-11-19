package com.aspora.tms.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class OutboxService(
    private val outbox: OutboxRepository,
    private val objectMapper: ObjectMapper,
) {
    /**
     * MANDATORY: the event must be written in the same DB transaction as the business change,
     * otherwise we could commit the change and lose the event (or the reverse).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun record(type: OutboxEventType, aggregateId: String, payload: Any) {
        outbox.save(OutboxEvent(type, aggregateId, objectMapper.writeValueAsString(payload)))
    }

    @Transactional(readOnly = true)
    fun pending(limit: Int): List<OutboxEvent> =
        outbox.findByStatusOrderByCreatedAtAsc(OutboxStatus.NEW, PageRequest.of(0, limit))

    @Transactional
    fun markPublished(ids: Collection<String>) {
        if (ids.isNotEmpty()) outbox.findAllById(ids).forEach { it.markPublished() }
    }
}
