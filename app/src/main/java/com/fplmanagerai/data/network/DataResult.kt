package com.fplmanagerai.data.network

sealed interface DataResult<out T> {
    data class Success<out T>(
        val data: T,
        val isFromCache: Boolean = false,
        val lastUpdatedMillis: Long = System.currentTimeMillis()
    ) : DataResult<T>

    data class Error<out T>(
        val message: String,
        val cause: Throwable? = null,
        val cachedData: T? = null
    ) : DataResult<T> {
        val hasFallbackData: Boolean get() = cachedData != null
    }

    data object Loading : DataResult<Nothing>
}
