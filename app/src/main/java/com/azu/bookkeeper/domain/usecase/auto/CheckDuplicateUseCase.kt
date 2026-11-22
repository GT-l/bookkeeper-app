package com.azu.bookkeeper.domain.usecase.auto

import com.azu.bookkeeper.automation.dedup.DuplicateDetector
import com.azu.bookkeeper.domain.model.CaptureCandidate
import javax.inject.Inject

/** 去重检查用例（领域层门面，纯委托；含短信源 ±N 秒容差）。 */
class CheckDuplicateUseCase @Inject constructor(
    private val detector: DuplicateDetector
) {
    suspend operator fun invoke(candidate: CaptureCandidate, toleranceSec: Long = 0L): Boolean =
        detector.isDuplicateWithTolerance(candidate, toleranceSec)

    operator fun buildKey(candidate: CaptureCandidate): String = detector.buildKey(candidate)
}
