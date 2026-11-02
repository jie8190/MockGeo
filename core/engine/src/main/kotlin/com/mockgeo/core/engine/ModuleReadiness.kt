package com.mockgeo.core.engine

import com.mockgeo.core.model.CheckResult

/** Package visibility is not proof of whether the system has loaded the module. */
internal object ModuleReadiness {
    fun checks(installedVersion: String?, reachable: Boolean, loadedVersion: String?): List<CheckResult> {
        val installed = installedVersion?.takeIf { it.isNotBlank() }
        val loaded = loadedVersion?.takeIf { reachable && it.isNotBlank() }
        val supported = loaded != null && XposedEngine.moduleVersionSupported(loaded)
        val matches = installed == null || installed == loaded
        return listOf(
            CheckResult("LSPosed 模块安装", installed != null || loaded != null,
                when {
                    installed != null -> "已安装 $installed"
                    loaded != null -> "系统已加载 $loaded；当前用户空间无法读取安装包版本"
                    else -> "未查询到模块安装包，也未收到系统加载回执；请检查模块安装、用户空间及作用域"
                }),
            CheckResult("模块版本", supported && matches, when {
                loaded == null -> "尚未收到加载版本，不能据此判断是否需要重启"
                !supported -> "系统加载 $loaded；请更新至 ${XposedEngine.MIN_MODULE_VERSION} 或更高版本并手动重启"
                !matches -> "已安装 $installed，系统仍加载 $loaded；请在方便时手动重启以加载已安装版本"
                installed == null -> "系统加载 $loaded，版本满足要求；安装包版本暂不可见"
                else -> "已安装并加载 $loaded"
            }),
        )
    }
}
