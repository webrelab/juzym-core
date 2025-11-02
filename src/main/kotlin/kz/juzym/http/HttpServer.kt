package kz.juzym.http

import io.ktor.server.application.Application
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kz.juzym.app.ApplicationContext

class HttpServer(
    private val context: ApplicationContext
) {
    private val engine: ApplicationEngine = embeddedServer(
        factory = Netty,
        host = context.config.server.host,
        port = context.config.server.port
    ) {
        configureHttpServer(context)
    }

    fun start(wait: Boolean = true) {
        engine.start(wait)
    }

    fun stop(gracePeriodMillis: Long = 1_000, timeoutMillis: Long = 5_000) {
        engine.stop(gracePeriodMillis, timeoutMillis)
    }
}

fun Application.configureHttpServer(context: ApplicationContext) {
    installPlugins(context)
    registerRoutes(context)
}
