package com.aspora.tms.user

import com.aspora.tms.common.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "users")
class User(
    @Column(nullable = false) var name: String,
    @Column(nullable = false, unique = true) var email: String,
) : BaseEntity()
