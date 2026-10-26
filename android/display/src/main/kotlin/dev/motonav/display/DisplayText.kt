package dev.motonav.display

/**
 * Các chuỗi vẽ sẵn. Mỗi lớp nhớ chuỗi đã dựng cho tới khi con số đổi, nhờ vậy frame() không cấp phát
 * bộ nhớ ở mỗi lần vẽ (trên ESP32 đây sẽ là snprintf vào một bộ đệm cố định).
 */
internal object DistanceRounding {
    /**
     * Làm tròn như trình giả lập: từ 1 000 m theo bước 100 m (hiện "x.x km"), từ 100 m theo bước 10 m,
     * dưới đó theo bước 5 m. Giá trị âm coi như 0. Trả về mét.
     *
     * Nhận thẳng đơn vị 0,1 m của giao thức: chia cho 1000/100/50 thì các mốc "nửa bước" (ví dụ 125,0 m)
     * ra đúng x,5 trong Float và luôn được làm tròn lên, còn nhân 0,1 trước thì không.
     */
    fun roundTenths(tenths: Float): Int {
        val u = if (tenths > 0f) tenths else 0f
        return when {
            u >= 10_000f -> (u / 1000f + 0.5f).toInt() * 100
            u >= 1_000f -> (u / 100f + 0.5f).toInt() * 10
            else -> (u / 50f + 0.5f).toInt() * 5
        }
    }

    /** Như [roundTenths] nhưng cho số mét nguyên. */
    fun round(meters: Long): Long {
        val m = if (meters > 0) meters else 0
        return when {
            m >= 1000 -> (m + 50) / 100 * 100
            m >= 100 -> (m + 5) / 10 * 10
            else -> (m * 2 + 5) / 10 * 5
        }
    }

    /** Bước làm tròn tại giá trị đang hiện, dùng cho quy tắc "không nảy ngược". */
    fun riseStep(shownM: Int): Int = if (shownM >= 100) 10 else 5
}

/** Một khoảng cách đã làm tròn dưới dạng phần số + đơn vị ("1.2" + "km", "150" + "m"). */
internal class DistanceText {
    private var key = Long.MIN_VALUE
    var text = "0"
        private set
    var unit = "m"
        private set

    /** [roundedM] phải là giá trị đã qua [DistanceRounding.round]. */
    fun set(roundedM: Long) {
        if (roundedM == key) return
        key = roundedM
        if (roundedM >= 1000) {
            text = "${roundedM / 1000}.${roundedM % 1000 / 100}"
            unit = "km"
        } else {
            text = roundedM.toString()
            unit = "m"
        }
    }
}

/** Một số nguyên dưới dạng chuỗi. */
internal class NumberText {
    private var key = Int.MIN_VALUE
    private var text = ""

    fun of(value: Int): String {
        if (value != key) {
            key = value
            text = value.toString()
        }
        return text
    }
}

/** Giờ "HH:MM" từ số phút kể từ 0 giờ; ngoài khoảng 0..1439 thì là "--:--". */
internal class ClockText {
    private var key = Int.MIN_VALUE
    private var text = DisplayFrame.UNKNOWN_TIME
    private val chars = CharArray(5)

    fun of(minuteOfDay: Int): String {
        if (minuteOfDay != key) {
            key = minuteOfDay
            text = if (minuteOfDay < 0 || minuteOfDay >= 1440) {
                DisplayFrame.UNKNOWN_TIME
            } else {
                val h = minuteOfDay / 60
                val m = minuteOfDay % 60
                chars[0] = '0' + h / 10
                chars[1] = '0' + h % 10
                chars[2] = ':'
                chars[3] = '0' + m / 10
                chars[4] = '0' + m % 10
                String(chars)
            }
        }
        return text
    }
}
