package io.github.kdh949.beanflow.ordering.internal.domain

import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode

@JvmInline
value class Krw private constructor(val value: Long) : Comparable<Krw> {

	operator fun plus(other: Krw): Krw =
		try {
			of(Math.addExact(value, other.value))
		} catch (_: ArithmeticException) {
			invalid("KRW addition exceeds supported range")
		}

	operator fun minus(other: Krw): Krw {
		if (other.value > value) {
			invalid("KRW subtraction must not become negative")
		}
		return of(value - other.value)
	}

	fun multiply(multiplier: Long): Krw {
		if (multiplier < 0) {
			invalid("KRW multiplier must not be negative")
		}
		return try {
			of(Math.multiplyExact(value, multiplier))
		} catch (_: ArithmeticException) {
			invalid("KRW multiplication exceeds supported range")
		}
	}

	override fun compareTo(other: Krw): Int = value.compareTo(other.value)

	companion object {
		val ZERO: Krw = Krw(0)

		fun of(value: Long): Krw {
			if (value < 0) {
				invalid("KRW amount must not be negative")
			}
			return Krw(value)
		}

		private fun invalid(message: String): Nothing =
			throw DomainFailure(FailureCode.INVALID_REQUEST, message)
	}
}
