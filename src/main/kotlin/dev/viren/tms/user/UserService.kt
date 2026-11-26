package dev.viren.tms.user

import dev.viren.tms.common.error.ErrorCode
import dev.viren.tms.common.error.TmsException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserService(private val users: UserRepository) {

    @Transactional
    fun create(name: String, email: String): User {
        val normalisedEmail = email.trim().lowercase()
        if (users.existsByEmail(normalisedEmail)) {
            throw TmsException(ErrorCode.DUPLICATE_RESOURCE, "User with email '$normalisedEmail' already exists")
        }
        return users.save(User(name.trim(), normalisedEmail))
    }

    @Transactional(readOnly = true)
    fun get(id: String): User = users.findByIdOrNull(id) ?: throw TmsException.notFound("User", id)

    @Transactional(readOnly = true)
    fun exists(id: String): Boolean = users.existsById(id)
}
