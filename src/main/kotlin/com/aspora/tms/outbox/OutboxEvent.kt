package com.aspora.tms.outbox

import com.aspora.tms.common.persistence.BaseEntity
import com.aspora.tms.queue.Topics
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

enum class OutboxEventType(val topic: String) {
    PROCESS_TRANSACTION(Topics.TRANSACTION_PROCESSING),
    TRANSACTION_COMPLETED(Topics.TRANSACTION_EVENTS),
    TRANSACTION_FAILED(Topics.TRANSACTION_EVENTS),
}

enum class OutboxStatus { NEW, PUBLISHED }

@Entity
@Table(name = "outbox_events", indexes = [Index(columnList = "status, created_at")])
class OutboxEvent(
    @Enumerated(EnumType.STRING) @Column(nullable = false) val eventType: OutboxEventType,
    @Column(nullable = false) val aggregateId: String,
    @Column(nullable = false, length = 4000) val payload: String,
) : BaseEntity() {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: OutboxStatus = OutboxStatus.NEW
        protected set

    var publishedAt: Instant? = null
        protected set

    fun markPublished() {
        status = OutboxStatus.PUBLISHED
        publishedAt = Instant.now()
    }
}
