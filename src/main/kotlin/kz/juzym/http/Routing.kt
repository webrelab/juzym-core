package kz.juzym.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kz.juzym.app.ApplicationContext
import kz.juzym.http.routes.publicApiRoutes

fun Application.registerRoutes(context: ApplicationContext) {
    routing {
        route("/api") {
            publicApiRoutes(context)
        }
        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
    }
}
