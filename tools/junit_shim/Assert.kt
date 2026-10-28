package org.junit

/**
 * The subset of `org.junit.Assert` these suites use.
 *
 * Only here so the real test files can be compiled and executed offline, where JUnit
 * cannot be downloaded. Gradle's own JUnit is used on a normal machine; nothing in
 * build.gradle points at this directory, so the two never meet.
 *
 * One fidelity gap: Kotlin will not resolve `import org.junit.Assert.*` against an
 * object, so a test using the wildcard import has to be given explicit imports to run
 * here. Direct `import org.junit.Assert.assertEquals` - what the timeline tests use -
 * works unchanged.
 */
object Assert {

    @JvmStatic
    fun fail(message: String): Nothing = throw AssertionError(message)

    @JvmStatic
    fun assertEquals(expected: Any?, actual: Any?) = assertEquals(null, expected, actual)

    @JvmStatic
    fun assertEquals(message: String?, expected: Any?, actual: Any?) {
        val equal = if (expected == null) actual == null else expected == actual
        if (!equal) {
            val prefix = if (message.isNullOrBlank()) "" else "$message "
            throw AssertionError("${prefix}expected:<$expected> but was:<$actual>")
        }
    }

    @JvmStatic
    fun assertTrue(condition: Boolean) = assertTrue(null, condition)

    @JvmStatic
    fun assertTrue(message: String?, condition: Boolean) {
        if (!condition) throw AssertionError(message ?: "expected condition to be true")
    }

    @JvmStatic
    fun assertFalse(condition: Boolean) = assertFalse(null, condition)

    @JvmStatic
    fun assertFalse(message: String?, condition: Boolean) {
        if (condition) throw AssertionError(message ?: "expected condition to be false")
    }

    @JvmStatic
    fun assertNull(value: Any?) = assertNull(null, value)

    @JvmStatic
    fun assertNull(message: String?, value: Any?) {
        if (value != null) throw AssertionError((message ?: "") + "expected null but was:<$value>")
    }

    @JvmStatic
    fun assertNotNull(value: Any?) = assertNotNull(null, value)

    @JvmStatic
    fun assertNotNull(message: String?, value: Any?) {
        if (value == null) throw AssertionError((message ?: "") + "expected a value but was null")
    }
}
