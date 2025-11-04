package kz.juzym.http.routes

import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kz.juzym.app.ApplicationContext

fun Route.publicApiRoutes(context: ApplicationContext) {
    route("/v1") {
        authRoutes(context)
        registrationRoutes(context.userService)
    }
}
