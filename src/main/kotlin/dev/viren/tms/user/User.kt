package dev.viren.tms.user

import dev.viren.tms.common.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "users")
class User(
    @Column(nullable = false) var name: String,
    @Column(nullable = false, unique = true) var email: String,
) : BaseEntity()
