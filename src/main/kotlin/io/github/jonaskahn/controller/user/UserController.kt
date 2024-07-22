package io.github.jonaskahn.controller.user

import io.github.jonaskahn.services.user.UserInfoDto
import io.github.jonaskahn.services.user.UserService
import io.jooby.annotation.GET
import io.jooby.annotation.Path
import jakarta.inject.Inject

@Path("/api/user/secure")
class UserController @Inject constructor(private val userService: UserService) {

    @GET("/info")
    fun info(): UserInfoDto {
        return userService.getCurrentUserInfo()
    }
}