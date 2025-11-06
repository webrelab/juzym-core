package kz.juzym.http.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kz.juzym.app.ApplicationContext

fun Route.publicApiRoutes(context: ApplicationContext) {
    route("/v1") {
        authRoutes(context)
        registrationRoutes(context.userService)
        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
    }
}
