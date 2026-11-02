package com.mockgeo.core.model

/** A local executable path, never a shell command or an imported setting. */
object RootSuPath {
    fun normalize(input: String): String {
        require(input.none { it.code < 32 || it.code == 127 }) { "su 路径不能包含换行或控制字符" }
        val path = input.trim()
        if (path.isEmpty() || path == "su") return ""
        require(path.startsWith('/') && !path.endsWith('/')) { "请填写 su 可执行文件的完整路径，或留空使用默认 su" }
        require(path.length <= 4096) { "su 路径过长" }
        return path
    }

    fun executable(input: String): String = normalize(input).ifEmpty { "su" }
}
