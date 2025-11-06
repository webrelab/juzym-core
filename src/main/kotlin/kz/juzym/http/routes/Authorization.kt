package kz.juzym.http.routes

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.util.AttributeKey
import kz.juzym.audit.SecurityContext
import kz.juzym.auth.AccessDeniedException
import kz.juzym.auth.AuthException
import kz.juzym.user.Role
import kz.juzym.user.security.jwt.JwtPrincipal
import kz.juzym.user.security.jwt.JwtService

fun Route.authorize(
    jwtService: JwtService,
    vararg roles: Role,
    build: Route.() -> Unit
) {
    val allowedRoles = roles.toSet()
    val authorizedRoute = createChild(AuthorizeRouteSelector)
    authorizedRoute.intercept(ApplicationCallPipeline.Plugins) {
        val principal = try {
            call.requirePrincipal(jwtService)
        } catch (ex: AuthException) {
            call.respondAuthError(ex)
            return@intercept
        }
        if (allowedRoles.isNotEmpty() && allowedRoles.none { it in principal.roles }) {
            call.respondAuthError(AccessDeniedException())
            return@intercept
        }
        call.attributes.put(JWT_PRINCIPAL_ATTRIBUTE_KEY, principal)
        SecurityContext.setCurrentUserId(principal.userId)
        try {
            proceed()
        } finally {
            SecurityContext.clear()
        }
    }
    authorizedRoute.apply(build)
}

internal fun ApplicationCall.jwtPrincipal(): JwtPrincipal? =
    if (attributes.contains(JWT_PRINCIPAL_ATTRIBUTE_KEY)) {
        attributes[JWT_PRINCIPAL_ATTRIBUTE_KEY]
    } else {
        null
    }

private val JWT_PRINCIPAL_ATTRIBUTE_KEY = AttributeKey<JwtPrincipal>("JwtPrincipal")

private object AuthorizeRouteSelector : RouteSelector() {
    override fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation =
        RouteSelectorEvaluation.Constant

    override fun toString(): String = "authorize"
}
