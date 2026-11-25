package com.azu.bookkeeper.ui.quick

import com.azu.bookkeeper.automation.permission.PermissionGuide
import com.azu.bookkeeper.data.local.datastore.AutomationSettingsDataStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 悬浮层服务的「惰性取依赖」入口。
 *
 * 为什么不用 @AndroidEntryPoint 字段注入：
 * 字段注入发生在 Service.onCreate 之前，一旦 Hilt 图里任何一环构造失败（例如数据库
 * 打开异常、native 库加载失败），进程会直接在服务创建阶段崩溃，而我们写在
 * onStartCommand 里的 try/catch 根本来不及拦截——这正是「开启悬浮球即闪退、
 * 之后每次启动都闪退」的成因。改为在真正用到时通过本 EntryPoint 取，且调用处
 * 全部 runCatching 包裹，服务创建阶段零依赖，崩溃面收敛到「功能不可用」而不是「进程死亡」。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface OverlayEntryPoint {
    fun permissionGuide(): PermissionGuide
    fun quickCaptureTrigger(): QuickCaptureTrigger
    fun automationSettings(): AutomationSettingsDataStore
}
