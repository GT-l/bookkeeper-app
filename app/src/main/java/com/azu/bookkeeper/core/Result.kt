package com.azu.bookkeeper.core

/**
 * 全局统一结果类型。所有 Repository / UseCase 的异步方法均返回该类型，
 * 禁止把异常直接抛到 UI 层。
 */
sealed class Result<out T> {

    data class Success<out T>(val data: T) : Result<T>()

    data class Error(val error: AppError) : Result<Nothing>()

    data object Loading : Result<Nothing>()

    val isSuccess: Boolean get() = this is Success

    val isError: Boolean get() = this is Error
}

/** 成功时返回值，失败返回 null。 */
fun <T> Result<T>.getOrNull(): T? = (this as? Result.Success<T>)?.data

/** 失败或 Loading 时返回给定默认值。 */
fun <T> Result<T>.getOrDefault(default: T): T = getOrNull() ?: default

/** 转换成功值；失败原样透传。 */
inline fun <T, R> Result<T>.map(transform: (T) -> R): Result<R> = when (this) {
    is Result.Success -> Result.Success(transform(data))
    is Result.Error -> Result.Error(error)
    Result.Loading -> Result.Loading
}

inline fun <T> Result<T>.onSuccess(action: (T) -> Unit): Result<T> {
    if (this is Result.Success) action(data)
    return this
}

inline fun <T> Result<T>.onError(action: (AppError) -> Unit): Result<T> {
    if (this is Result.Error) action(error)
    return this
}

/** 把协程中的异常收敛为 Result.Error，避免 UI 出现未捕获异常。 */
suspend inline fun <T> safeResult(crossinline block: suspend () -> T): Result<T> = try {
    Result.Success(block())
} catch (throwable: Throwable) {
    Result.Error(AppError.from(throwable))
}
