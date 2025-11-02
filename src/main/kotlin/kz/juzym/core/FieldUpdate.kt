package kz.juzym.core

/**
 * Represents an intention to modify a field when performing partial updates.
 *
 * By default, the field is [Keep] which means the current value must remain unchanged.
 * When a new value (including `null`) needs to be applied the [Value] variant is used.
 */
sealed interface FieldUpdate<out T> {

    /**
     * Keeps the current value of the field untouched.
     */
    data object Keep : FieldUpdate<Nothing>

    /**
     * Sets the field to a new [value]. The value may be `null` for nullable fields.
     */
    data class Value<T>(val value: T) : FieldUpdate<T>

    companion object {
        fun <T> keep(): FieldUpdate<T> = Keep
        fun <T> of(value: T): FieldUpdate<T> = Value(value)
    }
}
