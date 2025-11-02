package kz.juzym.http.routes

import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kz.juzym.app.ApplicationContext

@Suppress("UNUSED_PARAMETER")
fun Route.publicApiRoutes(context: ApplicationContext) {
    route("/v1") {
        // Public API endpoints will be registered here using services from the application context.
    }
}
