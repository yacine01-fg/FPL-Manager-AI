package com.fplmanagerai.data.model

enum class DataSyncStatus(
    val titleAr: String,
    val badgeAr: String,
    val descriptionAr: String
) {
    LIVE(
        titleAr = "بيانات حية مباشرة",
        badgeAr = "LIVE",
        descriptionAr = "تم التحديث بنجاح الآن من خوادم Fantasy Premier League الرسمية."
    ),
    CACHED(
        titleAr = "بيانات مخزنة محلياً",
        badgeAr = "CACHED",
        descriptionAr = "بيانات صالحة ومحفوظة من جلسة قريبة سابقة."
    ),
    STALE(
        titleAr = "بيانات قديمة",
        badgeAr = "STALE",
        descriptionAr = "البيانات المخزنة قديمة وتحتاج إلى إعادة تحديث يدوي."
    ),
    ERROR(
        titleAr = "خطأ في المزامنة",
        badgeAr = "ERROR",
        descriptionAr = "تعذر الوصول لخوادم FPL أو انقطع الاتصال بالإنترنت."
    );

    companion object {
        fun resolve(isFromCache: Boolean, isError: Boolean, timestampMillis: Long): DataSyncStatus {
            if (isError) return ERROR
            if (!isFromCache && timestampMillis > 0) return LIVE
            val age = System.currentTimeMillis() - timestampMillis
            // إذا كانت البيانات المحفوظة أقدم من ساعتين تعتبر STALE
            return if (age > 2 * 60 * 60 * 1000L) STALE else CACHED
        }

        fun formatRelativeTimeAr(timestampMillis: Long): String {
            if (timestampMillis <= 0L) return "غير محدد"
            val diff = System.currentTimeMillis() - timestampMillis
            if (diff < 0) return "الآن"
            val seconds = diff / 1000
            val minutes = seconds / 60
            val hours = minutes / 60
            val days = hours / 24

            return when {
                seconds < 60 -> "منذ لحظات"
                minutes < 60 -> "منذ $minutes دقيقة"
                hours < 24 -> "منذ $hours ساعة"
                days == 1L -> "أمس"
                else -> "منذ $days يوم"
            }
        }
    }
}

