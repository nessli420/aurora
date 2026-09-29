package com.aurora.music.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import java.net.JarURLConnection

class ComposeStabilityConfigTest {
    @Test fun declaredStableClassesAreDeeplyImmutable() {
        val classes = File("../compose-stability.conf").readLines().map { it.trim() }.filter { it.isNotEmpty() }
            .flatMap { entry ->
                if (!entry.endsWith(".*")) listOf(load(entry))
                else packageClasses(entry.removeSuffix(".*")).also { assertTrue(entry, it.isNotEmpty()) }
            }.toSet()
        val boxed = listOf(Boolean::class, Byte::class, Char::class, Short::class, Int::class, Long::class, Float::class, Double::class)
            .map { it.javaObjectType } + String::class.java
        classes.filterNot { it.isEnum }.forEach { type ->
            type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.forEach { field ->
                val name = "${type.name}.${field.name}"
                assertTrue("$name is mutable", Modifier.isFinal(field.modifiers))
                assertTrue("$name holds ${field.type.name}",
                    field.type.isPrimitive || field.type.isEnum || field.type in boxed || field.type in classes)
            }
        }
    }

    private fun load(name: String): Class<*> = Class.forName(name, false, javaClass.classLoader)

    private fun packageClasses(pkg: String): List<Class<*>> {
        val path = pkg.replace('.', '/') + "/"
        return javaClass.classLoader.getResources(path.dropLast(1)).toList().flatMap { url ->
            if (url.protocol == "jar") (url.openConnection() as JarURLConnection).jarFile.entries().toList()
                .map { it.name }.filter { it.startsWith(path) }.map { it.removePrefix(path) }
            else File(url.toURI()).list().orEmpty().toList()
        }.filter { it.endsWith(".class") && '/' !in it }.map { load(pkg + "." + it.removeSuffix(".class")) }
    }
}
