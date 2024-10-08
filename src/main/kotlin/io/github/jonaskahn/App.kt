package io.github.jonaskahn

import io.github.jonaskahn.assistant.JacksonMapper
import io.github.jonaskahn.assistant.Language
import io.github.jonaskahn.assistant.Response
import io.github.jonaskahn.controllers.HealthController
import io.github.jonaskahn.controllers.TestRoleController
import io.github.jonaskahn.controllers.auth.AuthController
import io.github.jonaskahn.controllers.user.UserController
import io.github.jonaskahn.exception.*
import io.github.jonaskahn.extensions.JedisModule
import io.github.jonaskahn.middlewares.context.LanguageContextHolder
import io.github.jonaskahn.middlewares.jwt.AdvancedJwtAuthenticator
import io.jooby.MediaType
import io.jooby.OpenAPIModule
import io.jooby.StatusCode
import io.jooby.exception.NotFoundException
import io.jooby.exception.UnauthorizedException
import io.jooby.flyway.FlywayModule
import io.jooby.guice.GuiceModule
import io.jooby.handler.AssetHandler
import io.jooby.handler.AssetSource
import io.jooby.handler.Cors
import io.jooby.handler.CorsHandler
import io.jooby.hibernate.HibernateModule
import io.jooby.hibernate.TransactionalRequest
import io.jooby.hibernate.validator.HibernateValidatorModule
import io.jooby.hikari.HikariModule
import io.jooby.jackson.JacksonModule
import io.jooby.kt.AfterContext
import io.jooby.kt.Kooby
import io.jooby.kt.runApp
import io.jooby.netty.NettyServer
import io.jooby.pac4j.Pac4jModule
import jakarta.persistence.NoResultException
import org.pac4j.core.authorization.authorizer.CheckHttpMethodAuthorizer
import org.pac4j.core.context.HttpConstants
import org.pac4j.http.client.direct.HeaderClient
import org.pac4j.jwt.config.signature.SecretSignatureConfiguration
import redis.clients.jedis.JedisPooled
import java.time.Duration

class App : Kooby({
    setting()
    decorate()
    routes()
    web()
})

fun Kooby.setting() {
    install(NettyServer())
    install(OpenAPIModule())
    install(JacksonModule(JacksonMapper.INSTANCE))

    install(GuiceModule())

    install(JedisModule())

    install(HikariModule())
    install(FlywayModule())
    install(HibernateValidatorModule())
    install(HibernateModule().scan("io.github.jonaskahn.entities"))

    val corsOption = Cors()
    corsOption.setOrigin("*")
    corsOption.setUseCredentials(true)
    corsOption.setHeaders("X-Requested-With", "Content-Type", "Accept", "Origin", "Authorization")
    corsOption.setMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD")
    corsOption.setMaxAge(Duration.ofMinutes(60))
    use(CorsHandler(corsOption))

    install(
        Pac4jModule().client(
            "/api/secure/*",
            CheckHttpMethodAuthorizer(
                HttpConstants.HTTP_METHOD.GET,
                HttpConstants.HTTP_METHOD.PUT,
                HttpConstants.HTTP_METHOD.POST,
                HttpConstants.HTTP_METHOD.DELETE,
                HttpConstants.HTTP_METHOD.PATCH
            )
        ) {
            HeaderClient(
                "Authorization",
                "Bearer ",
                AdvancedJwtAuthenticator(
                    require(JedisPooled::class.java),
                    SecretSignatureConfiguration(it.getString("jwt.salt"))
                )
            )
        }
    )

    use(TransactionalRequest().enabledByDefault(true))
    setContextAsService(true)

}

fun Kooby.decorate() {
    before {
        val acceptLanguage: String? = ctx.header("Accept-Language").valueOrNull()
        LanguageContextHolder.setLanguage(acceptLanguage)
    }
    after {
        if (failure == null) {
            ctx.setDefaultResponseType(MediaType.json)
            handleSuccess()
        }
    }
    error { ctx, cause, _ ->
        log.error("Something went wrong, detail", cause)
        val (calculatedStatusCode, data) = getStatusCodeAndMessage(cause)
        ctx.setDefaultResponseType(MediaType.json)
        ctx.responseCode = calculatedStatusCode
        ctx.render(data)
    }
}

private fun AfterContext.handleSuccess() {
    ctx.responseCode = StatusCode.OK
    when (result) {
        is Response<*> -> {
            ctx.render(result as Response<*>)
        }

        is StatusCode -> {
            ctx.render(Response.ok())
        }

        else -> {
            ctx.render(Response.ok(result))
        }
    }
    return
}


private fun getStatusCodeAndMessage(ex: Throwable): Pair<StatusCode, Response<*>> {
    val code: StatusCode?
    val res: Response<*>?
    when (ex) {
        is LogicException -> {
            code = StatusCode.BAD_REQUEST
            res = Response.fail(code, ex.message, ex.variables)
        }

        is ValidationException -> {
            code = StatusCode.PRECONDITION_FAILED
            res = Response.fail(code = code, payload = ex.data)
        }

        is NotFoundException -> {
            code = StatusCode.NOT_FOUND
            res = Response.fail(code, "app.common.exception.notfound")
        }

        is AuthenticationException -> {
            code = StatusCode.BAD_REQUEST
            res = Response.fail(code, ex.message)
        }

        is AuthorizationException, is UnauthorizedException -> {
            code = StatusCode.UNAUTHORIZED
            res = Response.fail(
                code = code,
                message = Language.of("app.common.exception.AuthorizationException")
            )
        }

        is ForbiddenAccessException -> {
            code = StatusCode.FORBIDDEN
            res = Response.fail(code = code, message = Language.of("app.common.exception.forbidden"))
        }

        is NoResultException -> {
            code = StatusCode.BAD_REQUEST
            res = Response.fail(code = code, message = Language.of("app.common.exception.no-data"))
        }

        is Exception -> {
            code = StatusCode.SERVER_ERROR
            res = Response.fail(code = code, message = Language.of("app.common.exception.server-error"))
        }

        else -> {
            code = StatusCode.SERVER_ERROR
            res =
                Response.fail(code = code, message = Language.of("app.common.exception.unknown-error"))
        }
    }
    return Pair(code, res)
}


fun Kooby.routes() {
    mount("/api", object : Kooby({
        install(JacksonModule(JacksonMapper.INSTANCE))
        mvc(HealthController::class.java)
        mvc(AuthController::class.java)
        mvc(UserController::class.java)
        mvc(TestRoleController::class.java)
    }) {})
}

fun Kooby.web() {
    val www = AssetSource.create(this.classLoader, "static")
    assets(
        "/*", AssetHandler("index.html", www)
            .setMaxAge(Duration.ofDays(365))
    )
}

fun main(args: Array<String>) {
    runApp(args, ::App)
}
