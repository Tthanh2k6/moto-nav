package dev.motonav.nav

/**
 * Mọi ngưỡng của bộ dẫn đường gom về một chỗ, để sau khi chạy thử ngoài đường chỉ cần chỉnh ở đây.
 * Giá trị mặc định là số của bản đánh giá thiết kế (NAV-1 … NAV-9), trừ hai chỗ đã chỉnh sau khi chạy giả lập
 * và có ghi rõ lý do ngay tại chỗ: [rejoinDistanceFactor] và [deadReckonAfterMs]. Đơn vị nằm ngay trong tên:
 * M = mét, Mps = m/s, Ms = mili giây, S = giây, Deg = độ, Fixes = số lần định vị liên tiếp.
 */
data class NavConfig(
    // ---- Tốc độ, đứng yên ----

    /** Chậm hơn mức này thì không bám tuyến và không đếm lệch tuyến: GPS trôi 10–25 m lúc dừng đèn đỏ. */
    val movingSpeedMps: Double = 1.0,
    /** Đang đứng yên thì phải nhanh hơn mức này mới coi là chạy lại (cao hơn mức trên để khỏi chớp tắt). */
    val resumeSpeedMps: Double = 1.5,
    val standstillFixes: Int = 2,
    val resumeFixes: Int = 2,
    /** Hướng đi do GPS báo chỉ đáng tin từ tốc độ này trở lên. */
    val bearingTrustSpeedMps: Double = 2.5,
    /** Máy không báo tốc độ thì suy ra từ quãng dịch chuyển, lọc với hệ số này (phần giữ lại của giá trị cũ). */
    val derivedSpeedLowPass: Double = 0.5,
    /** Lần định vị có sai số lớn hơn mức này coi như không có. */
    val maxAccuracyM: Float = 50f,
    /**
     * Tốc độ (máy báo hay tự suy ra) bị kẹp ở mức này, 216 km/h. Chip GPS thỉnh thoảng báo một con số vô lý,
     * và vị trí dự đoán = tốc độ × thời gian: không kẹp thì một lần đọc sai hất tiến độ đi vài trăm mét.
     */
    val maxSpeedMps: Double = 60.0,

    // ---- Cửa sổ tìm kiếm quanh vị trí hiện tại trên tuyến ----

    val windowBackMinM: Double = 15.0,
    val windowAheadMinM: Double = 40.0,
    val windowAheadSpeedFactor: Double = 3.0,
    val windowAheadAccuracyFactor: Double = 1.5,
    val windowAheadExtraM: Double = 15.0,
    val windowAheadMaxM: Double = 600.0,
    /** Sau một quãng mất GPS dài: cửa sổ phía trước = tốc độ cũ × thời gian mất + chừng này. */
    val gapWindowExtraM: Double = 100.0,

    // ---- Chấm điểm các khả năng, làm mượt ----

    /** Điểm phạt cho mỗi mét lệch so với vị trí dự đoán (tốc độ × thời gian). */
    val continuityWeight: Double = 0.5,
    /** Điểm phạt cho mỗi độ lệch hướng vượt quá [bearingFreeDeg]. */
    val bearingWeight: Double = 0.25,
    val bearingFreeDeg: Double = 30.0,
    /** Mỗi giây kéo vị trí dự đoán về phía điểm chiếu một phần chừng này. */
    val smoothingGain: Double = 0.4,
    /** Tiến độ được lùi tối đa chừng này mỗi lần định vị… */
    val maxBackStepM: Double = 2.0,
    /** …và tổng cộng không lùi quá chừng này so với chỗ xa nhất đã tới. */
    val maxBackTotalM: Double = 15.0,

    // ---- Cổng ở chỗ rẽ: không cho tiến độ vượt qua góc cua khi xe chưa thật sự rẽ ----

    /** Chỗ rẽ đổi hướng ít hơn mức này thì không cần cổng. */
    val gateMinTurnDeg: Double = 30.0,
    /** Cổng giữ tiến độ ở trước chỗ rẽ chừng này. */
    val gateMarginM: Double = 1.0,
    /** Độ dài dây cung dùng để đo hướng vào / hướng ra của một chỗ rẽ. */
    val turnChordM: Double = 15.0,
    /** Không có hướng đi đáng tin: điểm chiếu phải qua chỗ rẽ max(chừng này, hệ số × sai số) mới mở cổng. */
    val gateSlowPassMinM: Double = 8.0,
    val gateSlowPassAccuracyFactor: Double = 0.5,
    /** Bị giữ đủ lâu mà điểm chiếu đã qua chỗ rẽ rất xa thì mở cổng dù hướng đi nói gì. */
    val gateHoldFixes: Int = 5,
    val gateHeldPassMinM: Double = 20.0,
    val gateHeldPassAccuracyFactor: Double = 1.5,
    /** Vừa mất GPS lâu hơn mức này thì khỏi chờ đủ [gateHoldFixes]: xe đã chạy "mù" đủ lâu rồi. */
    val gateBlindGapS: Double = 3.0,

    // ---- Lệch tuyến ----

    /** Ngưỡng lệch tuyến T = kẹp(base + hệ số × sai số, min, max). */
    val offRouteBaseM: Double = 10.0,
    val offRouteAccuracyFactor: Double = 1.5,
    val offRouteMinM: Double = 25.0,
    val offRouteMaxM: Double = 50.0,
    /** Đường nhanh: chạy nhanh, xa tuyến, không có đoạn tuyến nào "hợp lý" bên cạnh, chừng này lần liên tiếp. */
    val offRouteFastFixes: Int = 3,
    /** Một đoạn tuyến là "hợp lý" nếu cách xe không quá chừng này, lệch hướng không quá chừng này, và xe nằm ngang hông nó. */
    val plausibleDistanceM: Double = 60.0,
    val plausibleBearingDeg: Double = 45.0,
    val plausibleBesideM: Double = 5.0,
    /** Đường chậm: xa tuyến chừng này lần liên tiếp và đã đi được chừng này mét. */
    val offRouteSlowFixes: Int = 8,
    val offRouteSlowTravelM: Double = 40.0,
    /** Khi tìm đường quay lại tuyến: xét từ sau vị trí đang giữ chừng này mét tới hết tuyến. */
    val rejoinBackM: Double = 100.0,
    /**
     * Phải gần tuyến hơn hẳn ngưỡng lệch (T × hệ số này) mới tính là đã quay lại.
     * Bản đánh giá đề nghị 0,6; chạy giả lập với sai số 8 m thì 0,6 quá chặt: gần 10 % số lần xe đã về lại tuyến
     * mà hơn 5 giây sau vẫn bị coi là lệch (GPS còn lệch 17–20 m). 0,75 đưa con số đó xuống khoảng 2 %, đổi lại
     * khi chạy trên đường song song cách tuyến 40 m thì có khoảng 2 % thời gian bị nhận nhầm là đã về tuyến.
     */
    val rejoinDistanceFactor: Double = 0.75,
    val rejoinBearingDeg: Double = 45.0,
    val rejoinFixes: Int = 3,
    val rejoinSlackM: Double = 8.0,
    /** Lần định vị đầu tiên: chỉ tìm trong chừng này mét đầu tuyến… */
    val firstFixAheadM: Double = 150.0,
    /** …và dùng ngưỡng lệch rộng rãi này cho tới lần bám đầu tiên (người lái có thể còn ở trong sân). */
    val firstFixThresholdM: Double = 80.0,

    // ---- Mất GPS ----

    /**
     * Xe đang chạy mà chưa quá mức này kể từ lần định vị cuối thì lặp lại nguyên kết quả cũ, quá rồi mới đoán vị trí.
     * Bản đánh giá đề nghị 2 000 (vì màn hình tự nội suy được 2 giây). Để 0 vì NAV_STATE không mang theo thời điểm đo:
     * màn hình nhận một kết quả "cũ 1,5 giây" sẽ tưởng xe vẫn ở chỗ cũ và kéo xe lùi lại hơn chục mét, rồi lại
     * nhảy tới khi có kết quả đoán. Đoán ngay từ đầu thì kết quả gửi đi luôn khớp với cái màn hình đang tự nội suy.
     */
    val deadReckonAfterMs: Long = 0,
    /**
     * Tiến độ đoán ở một nhịp mất GPS ngắn không quá mức này (bằng quãng màn hình tự nội suy) đã được gửi đi như
     * tiến độ thật, nên lần định vị kế tiếp cũng chỉ được lùi tối đa [maxBackStepM] so với nó. Mất GPS lâu hơn thì
     * bỏ luật này: xe có thể đã dừng từ lâu, bám lại cho đúng quan trọng hơn là không lùi.
     */
    val reckonKeepMs: Long = 2_000,
    /** Chỉ đoán vị trí theo tốc độ cũ khi lúc đó xe chạy nhanh hơn mức này. */
    val deadReckonMinSpeedMps: Double = 2.0,
    /** Hai lần định vị cách nhau quá mức này (lúc xe đang chạy) thì lần sau phải bám tuyến lại dù xe đã dừng. */
    val resyncGapMs: Long = 2_000,
    /** Quá mức này thì báo mất GPS. */
    val noGpsAfterMs: Long = 8_000,
    /** Lúc đang đứng yên hoặc lệch tuyến thì không có gì để đoán, báo mất GPS sớm hơn. */
    val noGpsStoppedAfterMs: Long = 3_000,

    // ---- Tới đích ----

    /** Chỉ xét "đã tới" khi còn không quá chừng này mét DỌC TUYẾN (tuyến vòng về chỗ cũ không được tính nhầm). */
    val arrivalZoneM: Double = 150.0,
    val arriveM: Double = 15.0,
    /** Còn max(chừng này, sai số + phần thêm) mét và đi chậm đủ lâu cũng là đã tới. */
    val arriveSlowMinM: Double = 30.0,
    val arriveSlowAccuracyExtraM: Double = 10.0,
    val arriveSlowSpeedMps: Double = 1.5,
    val arriveSlowMs: Long = 3_000,
    /** Đỗ gần điểm đến thật (thường nằm lệch khỏi đường) đủ lâu cũng là đã tới, kể cả khi đang bị coi là lệch tuyến. */
    val arriveNearDestinationM: Double = 30.0,
    val arriveNearMs: Long = 5_000,
    /**
     * Đỗ hẳn trong vùng cuối tuyến (còn ≤ [arrivalZoneM] dọc tuyến, hoặc cách điểm đến ≤ [arriveParkedDestinationM])
     * liên tục chừng này cũng là đã tới: điểm đến hay nằm sâu trong hẻm, chợ, chung cư, người lái đỗ ngoài rồi đi bộ vào.
     * Đủ dài để một lần chờ đèn đỏ gần đích không bị tính nhầm.
     */
    val arriveParkedMs: Long = 120_000,
    val arriveParkedDestinationM: Double = 100.0,
    /** Sát đích thì không đòi tính lại đường nữa. */
    val noRerouteRemainingM: Double = 60.0,
    val noRerouteDestinationM: Double = 50.0,

    // ---- Thông tin kèm theo ----

    val alertRangeM: Double = 500.0,
    val maxAlerts: Int = 4,
    val laneRangeM: Double = 220.0,
    /** LaneHint được coi là của chỗ rẽ nếu vị trí hai bên lệch nhau không quá chừng này. */
    val laneMatchM: Double = 1.0,
    val followingMaxGapM: Double = 100.0,

    // ---- Giờ đến ----

    /** Hệ số nhịp đi k được "ghìm" bằng chừng này giây đi với nhịp ban đầu, rồi mới ngả dần theo nhịp thật. */
    val paceWarmupS: Double = 180.0,
    val paceMin: Double = 0.8,
    val paceMax: Double = 2.5,
    /** Tuyến không có thời gian ước tính thì tạm lấy tốc độ trung bình này. */
    val fallbackSpeedMps: Double = 7.0,
)
