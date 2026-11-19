package com.aspora.tms.user

import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class CreateUserRequest(
    @field:NotBlank val name: String?,
    @field:NotBlank @field:Email val email: String?,
)

data class UserResponse(val id: String, val name: String, val email: String, val createdAt: Instant)

fun User.toResponse() = UserResponse(id, name, email, createdAt)

@RestController
@RequestMapping("/api/v1/users")
class UserController(private val userService: UserService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateUserRequest): UserResponse =
        userService.create(request.name!!, request.email!!).toResponse()

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): UserResponse = userService.get(id).toResponse()
}
