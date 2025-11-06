package kz.juzym.auth

import io.ktor.http.*

open class AuthException(
    val errorCode: String,
    val status: HttpStatusCode,
    override val message: String,
    val details: Map<String, Any?> = emptyMap()
) : RuntimeException(message)

class InvalidCredentialsException : AuthException(
    errorCode = "invalid_credentials",
    status = HttpStatusCode.Unauthorized,
    message = "Неверный логин или пароль"
)

class AccountBlockedException : AuthException(
    errorCode = "account_blocked",
    status = HttpStatusCode.Forbidden,
    message = "Аккаунт заблокирован"
)

class UserNotActivatedException : AuthException (
    errorCode = "user_not_activated",
    status = HttpStatusCode.Forbidden,
    message = "Пользователь не активирован"
)

class AccessDeniedException : AuthException(
    errorCode = "access_denied",
    status = HttpStatusCode.Forbidden,
    message = "Недостаточно прав"
)

class InvalidRefreshTokenException : AuthException(
    errorCode = "invalid_refresh_token",
    status = HttpStatusCode.Unauthorized,
    message = "Refresh токен недействителен"
)

class TokenAlreadyRotatedException : AuthException(
    errorCode = "token_already_rotated",
    status = HttpStatusCode.Conflict,
    message = "Refresh токен уже был использован"
)

class DeviceMismatchException : AuthException(
    errorCode = "device_mismatch",
    status = HttpStatusCode.Locked,
    message = "Устройство не совпадает"
)

class SessionNotFoundException : AuthException(
    errorCode = "session_not_found",
    status = HttpStatusCode.NotFound,
    message = "Сессия не найдена"
)

class ForbiddenSessionException : AuthException(
    errorCode = "forbidden",
    status = HttpStatusCode.Forbidden,
    message = "Доступ к сессии запрещён"
)

class UnauthorizedException : AuthException(
    errorCode = "unauthorized",
    status = HttpStatusCode.Unauthorized,
    message = "Требуется аутентификация"
)

class WeakPasswordException : AuthException(
    errorCode = "weak_password",
    status = HttpStatusCode.BadRequest,
    message = "Пароль не соответствует требованиям"
)

class PasswordReuseForbiddenException : AuthException(
    errorCode = "password_reuse_forbidden",
    status = HttpStatusCode.Conflict,
    message = "Нельзя использовать предыдущий пароль"
)

class InvalidPayloadException(message: String) : AuthException(
    errorCode = "invalid_payload",
    status = HttpStatusCode.BadRequest,
    message = message
)
