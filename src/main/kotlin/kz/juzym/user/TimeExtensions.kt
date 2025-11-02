package kz.juzym.user

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

internal fun Instant.toOffsetDateTime(): OffsetDateTime =
    OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

internal fun OffsetDateTime.toInstantUtc(): Instant =
    toInstant()
