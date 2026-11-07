package com.fplmanagerai.data.network

sealed class FplDataException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NetworkUnavailableException(message: String = "تعذر الاتصال بخوادم FPL، تحقق من اتصال الإنترنت", cause: Throwable? = null) :
        FplDataException(message, cause)

    class MalformedResponseException(message: String = "استجابة غير متوقعة أو حقول ناقصة من خادم الفانتازي", cause: Throwable? = null) :
        FplDataException(message, cause)

    class RateLimitExceededException(message: String = "تم تجاوز الحد المسموح للطلبات (HTTP 429)، يرجى الانتظار دقيقة") :
        FplDataException(message)

    class ServerMaintenanceException(message: String = "لعبة الفانتازي تخضع للتحديث حالياً من قبل الموقع الرسمي (FPL Game Updating)") :
        FplDataException(message)

    class CacheExpiredException(message: String = "انتهت صلاحية البيانات المخزنة محلياً") :
        FplDataException(message)
}
