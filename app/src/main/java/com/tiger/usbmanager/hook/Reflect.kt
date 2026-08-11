package com.tiger.usbmanager.hook

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Reflection helpers used inside system_server. Everything is best-effort and
 * silently degrades (returns null / false) so that ROM-specific class layout
 * differences never crash the USB stack.
 *
 * Declared as top-level extension functions (mirroring the ExampleApp pattern)
 * so callers can write `"com.foo.Bar".findClassOrNull(loader)`.
 */

internal fun ClassLoader.findClassOrNull(name: String): Class<*>? =
    runCatching { Class.forName(name, false, this) }.getOrNull()

internal fun Class<*>.allFields(): Sequence<Field> = sequence {
    val seen = mutableSetOf<String>()
    var current: Class<*>? = this@allFields
    while (current != null) {
        current.declaredFields.forEach { field ->
            field.isAccessible = true
            val key = field.name + ":" + field.type.name
            if (seen.add(key)) yield(field)
        }
        current = current.superclass
    }
}

internal fun Class<*>.allMethods(): Sequence<Method> = sequence {
    val seen = mutableSetOf<String>()
    var current: Class<*>? = this@allMethods
    while (current != null) {
        current.declaredMethods.forEach { method ->
            method.isAccessible = true
            val sig = method.name +
                method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
            if (seen.add(sig)) yield(method)
        }
        current = current.superclass
    }
}

internal fun Class<*>.fieldOrNull(name: String): Field? =
    allFields().firstOrNull { it.name == name }

internal fun Class<*>.methodOrNull(name: String, vararg parameterTypes: Class<*>): Method? =
    runCatching { getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true } }
        .getOrNull()
        ?: allMethods().firstOrNull {
            it.name == name && it.parameterTypes.contentEquals(parameterTypes)
        }

internal fun Class<*>.methodsNamed(name: String): List<Method> =
    allMethods().filter { it.name == name }.distinctBy { it.toGenericString() }.toList()

internal fun Class<*>.staticLongFieldOrNull(name: String): Long? =
    fieldOrNull(name)?.takeIf {
        it.type == Long::class.javaPrimitiveType || it.type == Long::class.javaObjectType
    }?.let { runCatching { (it.get(null) as Number).toLong() }.getOrNull() }

internal fun Any.callMethod(name: String, vararg args: Any?): Any? {
    val method = javaClass.allMethods().firstOrNull { candidate ->
        candidate.name == name &&
            candidate.parameterCount == args.size &&
            candidate.parameterTypes.indices.all { i ->
                isAssignable(candidate.parameterTypes[i], args[i])
            }
    } ?: return null
    return runCatching { method.invoke(this, *args) }.getOrNull()
}

private fun isAssignable(type: Class<*>, value: Any?): Boolean {
    if (value == null) return !type.isPrimitive
    if (type.isInstance(value)) return true
    return primitiveWrapper(type)?.isInstance(value) == true
}

private fun primitiveWrapper(type: Class<*>): Class<*>? = when (type) {
    Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
    Byte::class.javaPrimitiveType -> Byte::class.javaObjectType
    Short::class.javaPrimitiveType -> Short::class.javaObjectType
    Int::class.javaPrimitiveType -> Int::class.javaObjectType
    Long::class.javaPrimitiveType -> Long::class.javaObjectType
    Float::class.javaPrimitiveType -> Float::class.javaObjectType
    Double::class.javaPrimitiveType -> Double::class.javaObjectType
    Char::class.javaPrimitiveType -> Char::class.javaObjectType
    else -> null
}
