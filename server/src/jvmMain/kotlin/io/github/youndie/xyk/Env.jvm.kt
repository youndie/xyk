package io.github.youndie.xyk

actual fun readEnv(name: String): String? = System.getenv(name)
