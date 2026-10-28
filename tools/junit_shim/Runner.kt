package sandbox

import org.junit.Test
import kotlin.reflect.KClass

/**
 * Runs the real JUnit test classes without JUnit's runner.
 *
 * `java.lang.reflect` is used rather than kotlin-reflect so the sandbox needs no extra jar.
 * A class-level `@Ignore` is not supported on purpose - these suites must always run.
 */
object Runner {

    @JvmStatic
    fun main(args: Array<String>) {
        var passed = 0
        val failures = mutableListOf<String>()

        for (className in args) {
            val klass: KClass<*> = Class.forName(className).kotlin
            val instance = klass.java.getDeclaredConstructor().newInstance()

            val tests = klass.java.declaredMethods
                .filter { it.isAnnotationPresent(Test::class.java) }
                .sortedBy { it.name }

            println()
            println("${klass.simpleName}  (${tests.size} tests)")
            println("-".repeat(72))

            for (method in tests) {
                method.isAccessible = true
                val label = method.name.replace('$', ' ')
                try {
                    method.invoke(instance)
                    passed++
                    println("  PASS  $label")
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    val cause = e.targetException
                    failures += "${klass.simpleName}.${method.name}: ${cause?.message}"
                    println("  FAIL  $label")
                    println("        ${cause?.javaClass?.simpleName}: ${cause?.message}")
                }
            }
        }

        println()
        println("=".repeat(72))
        if (failures.isEmpty()) {
            println("$passed passed, 0 failed")
        } else {
            failures.forEach { println("FAILED  $it") }
            println("${passed - failures.size} passed, ${failures.size} failed")
            System.exit(1)
        }
    }
}
