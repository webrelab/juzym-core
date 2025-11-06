package kz.juzym.http

import io.ktor.server.application.Application
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kz.juzym.app.ApplicationContext
import kz.juzym.http.routes.publicApiRoutes

fun Application.registerRoutes(context: ApplicationContext) {
    routing {
        route("/api") {
            publicApiRoutes(context)
        }
    }
}
