package com.mockgeo.core.model

enum class RootSuMode(val label: String) {
    AUTO("自动检测"), COMMAND("-c 参数"), STDIN("标准输入");

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.name == value } ?: AUTO
    }
}
