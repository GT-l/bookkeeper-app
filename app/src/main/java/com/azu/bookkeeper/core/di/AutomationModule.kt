package com.azu.bookkeeper.core.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 自动化模块 DI 聚合说明（增量架构 §5.2）。
 *
 * 本批所有自动化类（OCR / 分类 / 去重 / 校对 / 权限 / 短信解析）均以
 * @Singleton + @Inject 构造器注入，无接口多实现，Hilt 可自动绑定，
 * 无需 @Binds/@Provides。此 Module 作为显式聚合入口与编译期校验锚点，
 * 保证自动化依赖图归属 SingletonComponent 且可集中审查。
 */
@Module
@InstallIn(SingletonComponent::class)
object AutomationModule
