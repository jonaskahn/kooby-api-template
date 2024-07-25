package io.github.jonaskahn.services.authen

import com.google.inject.ImplementedBy

@ImplementedBy(AuthenticationServiceImpl::class)
interface AuthenticationService {
    fun generateToken(username: String, password: String, increaseExpired: Boolean = false): String

    fun logout()
}