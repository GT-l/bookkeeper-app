package com.azu.bookkeeper.domain.usecase.auto

import com.azu.bookkeeper.automation.capture.CaptureCoordinator
import com.azu.bookkeeper.automation.capture.CaptureEvent
import javax.inject.Inject

/**
 * 自动化处理入口（领域层门面）：四路捕获统一交给 [CaptureCoordinator] 汇流处理。
 * 纯委托，不含业务分支，保证领域层调用方无需依赖 automation 实现细节。
 */
class ProcessCaptureUseCase @Inject constructor(
    private val coordinator: CaptureCoordinator
) {
    suspend operator fun invoke(event: CaptureEvent) {
        coordinator.onCapture(event)
    }
}
