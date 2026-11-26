package dev.viren.tms.common.persistence

import jakarta.persistence.Column
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

/**
 * App-assigned UUID ids + nullable @Version: Spring Data treats `version == null` as "new",
 * so save() does a plain INSERT instead of SELECT-then-merge. The version also gives
 * optimistic locking for free on every entity.
 */
@MappedSuperclass
abstract class BaseEntity {
    @Id
    @Column(length = 36)
    val id: String = UUID.randomUUID().toString()

    @Version
    var version: Long? = null
        protected set

    @Column(nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}



