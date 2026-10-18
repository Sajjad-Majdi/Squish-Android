package com.artin.pinch.ui

import java.util.Locale
import kotlin.math.abs

/** All UI text, English and Persian. */
class Strings(val fa: Boolean) {
    private fun t(en: String, f: String) = if (fa) f else en

    val app = t("Squish Compressor", "فشرده‌ساز اسکوییش")
    val tabCompress = t("Compress", "فشرده‌سازی")
    val tabHistory = t("History", "تاریخچه")
    val tabSettings = t("Settings", "تنظیمات")

    val choose = t("Choose photos & videos", "انتخاب عکس و ویدیو")
    val files = t("Files", "فایل‌ها")
    fun freed(size: String) = t("$size freed so far", "$size تا حالا آزاد شده")
    fun items(n: Int) = t(if (n == 1) "1 item" else "${num(n)} items", "${num(n)} فایل")
    fun compress(n: Int) = t("Squish ${num(n)}", "فشرده کن (${num(n)})")
    val smaller = t("smaller", "کم‌حجم‌تر")
    val modes = listOf(t("Quality", "کیفیت"), t("Size", "حجم"))
    /** The four quality levels, best first. */
    val levels = listOf(t("Pristine", "بی‌نقص"), t("Lossless", "بدون افت"), t("Balanced", "متعادل"), t("Compact", "فشرده"))
    /** Words for a measured score, best first (Quality.band). */
    val bands = listOf(
        t("Pristine", "بی‌نقص"), t("No visible loss", "بدون افت محسوس"), t("Slight loss", "افت جزئی"),
        t("Visible loss", "افت محسوس"), t("Heavy loss", "افت زیاد"),
    )
    fun score(q: Float) = t("Quality ${num(Math.round(q))}", "کیفیت ${num(Math.round(q))}")
    fun scoreAtLeast(q: Float) = t("Quality ${num(Math.round(q))}+", "کیفیت ${num(Math.round(q))} به بالا")
    fun measuring(i: Int, n: Int) = t("Measuring ${num(i)}/${num(n)}", "سنجش ${num(i)} از ${num(n)}")
    val optimal = t("Optimal", "بهینه")
    val stages = listOf(t("Measuring quality", "سنجش کیفیت"), t("Compressing", "فشرده‌سازی"), t("Checking quality", "بررسی کیفیت"))
    /** Points from the old size to the new one in either reading direction. */
    val arrow = if (fa) "  ←  " else "  →  "
    val add = t("Add", "افزودن")
    val clearAll = t("Clear", "پاک کردن")
    val cancel = t("Cancel", "لغو")
    val saved = t("Saved", "صرفه‌جویی")
    fun savedSoFar(size: String) = t("$size saved", "$size صرفه‌جویی")
    val share = t("Share", "اشتراک")
    val trashOriginals = t("Trash originals", "حذف اصلی‌ها")
    val trashed = t("Moved to trash", "به سطل زباله رفت")
    val newBatch = t("New", "جدید")
    val keptOriginal = t("Already optimal", "از قبل بهینه")
    val failed = t("Couldn't compress", "فشرده نشد")
    val waiting = t("Waiting", "در صف")

    val gallery = t("Choose", "انتخاب")
    val all = t("All", "همه")
    val photos = t("Photos", "عکس")
    val videos = t("Videos", "ویدیو")
    val largest = t("Largest", "بزرگ‌ترین")
    val newest = t("Newest", "جدیدترین")
    fun addN(n: Int, size: String) = t("Add ${num(n)} · $size", "افزودن ${num(n)} · $size")
    val allowAccess = t("Allow access", "اجازه دسترسی")
    val more = t("Choose more", "انتخاب بیشتر")
    val emptyGallery = t("No photos or videos", "عکس یا ویدیویی نیست")

    val before = t("Before", "قبل")
    val after = t("After", "بعد")
    val open = t("Open", "باز کردن")

    val historyEmpty = t("Nothing yet", "هنوز چیزی نیست")
    fun filesN(n: Int) = t(if (n == 1) "1 file" else "${num(n)} files", "${num(n)} فایل")
    val freedTotal = t("freed", "آزاد شده")

    val language = t("Language", "زبان")
    val theme = t("Theme", "پوسته")
    val auto = t("Auto", "خودکار")
    val light = t("Light", "روشن")
    val dark = t("Dark", "تیره")
    val videoCodec = t("Video codec", "کدک ویدیو")
    val photoFormat = t("Photo format", "فرمت عکس")
    val keepLocation = t("Keep location", "حفظ موقعیت مکانی")
    val version = t("Version", "نسخه")

    fun notifWorking(i: Int, n: Int) = t("Compressing ${num(i)} of ${num(n)}", "فشرده‌سازی ${num(i)} از ${num(n)}")
    fun notifDone(size: String) = t("Done · $size saved", "تمام شد · $size صرفه‌جویی")

    fun num(n: Int): String = digits(n.toString())
    /** "−90%" in English; Persian puts the word after so no sign floats to the wrong side. */
    fun drop(f: Float): String = if (fa) pct(f) + " کمتر" else "−" + pct(f)

    fun pct(f: Float): String = digits(Math.round(f * 100).toString()) + if (fa) "٪" else "%"

    fun digits(s: String): String {
        if (!fa) return s
        val b = StringBuilder(s.length)
        for (c in s) b.append(
            when (c) {
                in '0'..'9' -> '۰' + (c - '0')
                '.' -> '٫'
                else -> c
            },
        )
        return b.toString()
    }

    fun size(bytes: Long): String {
        val b = abs(bytes).toDouble()
        val (v, u) = when {
            b >= 1e9 -> b / (1 shl 30) to t("GB", "گیگ")
            b >= 1e6 -> b / (1 shl 20) to t("MB", "مگ")
            b >= 1e3 -> b / (1 shl 10) to t("KB", "کیلو")
            else -> b to t("B", "بایت")
        }
        val n = when {
            v >= 100 -> String.format(Locale.US, "%.0f", v)
            v >= 10 -> String.format(Locale.US, "%.1f", v).removeSuffix(".0")
            else -> String.format(Locale.US, "%.2f", v).trimEnd('0').removeSuffix(".")
        }
        return digits(n) + " " + u
    }

    fun duration(ms: Long): String {
        val s = ms / 1000
        val text = if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        return digits(text)
    }

    companion object {
        private val EN = Strings(false)
        private val FA = Strings(true)
        fun of(lang: String) = if (lang == "fa") FA else EN
    }
}
