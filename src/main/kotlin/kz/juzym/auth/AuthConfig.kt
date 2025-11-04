package kz.juzym.auth

import java.time.Duration

data class AuthConfig(
    val accessTokenTtl: Duration,
    val refreshTokenTtl: Duration,
    val rememberMeRefreshTokenTtl: Duration
)

fun AuthConfig.refreshTtl(rememberMe: Boolean): Duration =
    if (rememberMe) rememberMeRefreshTokenTtl else refreshTokenTtl
