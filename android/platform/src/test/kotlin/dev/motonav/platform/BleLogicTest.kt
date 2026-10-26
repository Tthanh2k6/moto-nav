package dev.motonav.platform

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class BleLogicTest {
    // ---------------------------------------------------------------- cắt khung

    @Test
    fun payloadSizeFollowsTheProtocolRule() {
        assertEquals(20, BleChunks.payloadSize(null)) // chưa biết MTU
        assertEquals(20, BleChunks.payloadSize(23)) // MTU mặc định
        assertEquals(182, BleChunks.payloadSize(185))
        assertEquals(244, BleChunks.payloadSize(247)) // MTU của firmware
        assertEquals(244, BleChunks.payloadSize(517)) // Android 14+ luôn xin 517: không bao giờ vượt 244
        assertEquals(20, BleChunks.payloadSize(5)) // giá trị vô lý không được làm cỡ mẩu thành 0 hay âm
    }

    @Test
    fun splitKeepsEveryByteInOrderAndRespectsTheSize() {
        val random = Random(7)
        for (length in listOf(1, 19, 20, 21, 40, 243, 244, 245, 486)) {
            val frame = ByteArray(length).also { random.nextBytes(it) }
            for (size in listOf(20, 182, 244)) {
                val chunks = BleChunks.split(frame, size)
                assertEquals("chunk count for $length/$size", (length + size - 1) / size, chunks.size)
                assertTrue(chunks.all { it.isNotEmpty() && it.size <= size })
                // chỉ mẩu cuối được phép ngắn hơn
                assertTrue(chunks.dropLast(1).all { it.size == size })
                assertArrayEquals(frame, chunks.fold(ByteArray(0)) { acc, c -> acc + c })
            }
        }
    }

    @Test
    fun longestFrameNeedsTwentyFiveWritesBeforeTheMtuIsKnownAndTwoAfter() {
        val frame = ByteArray(486)
        assertEquals(25, BleChunks.split(frame, BleChunks.payloadSize(null)).size)
        assertEquals(2, BleChunks.split(frame, BleChunks.payloadSize(247)).size)
    }

    @Test
    fun cursorRepeatsTheSameChunkUntilAcknowledged() {
        val frame = ByteArray(50) { it.toByte() }
        val cursor = FrameCursor(frame)
        val first = cursor.current(20)
        assertArrayEquals(first, cursor.current(20)) // bị từ chối, ghi lại: vẫn đúng đoạn đó
        assertEquals(0, cursor.offset)
        cursor.ack()
        assertEquals(20, cursor.offset)
        assertFalse(cursor.done)
    }

    @Test
    fun cursorFollowsAnMtuChangeBetweenChunks() {
        val frame = ByteArray(300) { it.toByte() }
        val cursor = FrameCursor(frame)
        val sent = ArrayList<ByteArray>()
        sent += cursor.current(20)
        cursor.ack()
        // MTU vừa được thương lượng xong giữa khung: các mẩu sau to hơn, không mất và không lặp byte nào
        cursor.current(20)
        sent += cursor.current(244)
        cursor.ack()
        sent += cursor.current(244)
        cursor.ack()
        assertTrue(cursor.done)
        assertEquals(listOf(20, 244, 36), sent.map { it.size })
        assertArrayEquals(frame, sent.fold(ByteArray(0)) { acc, c -> acc + c })
    }

    @Test(expected = IllegalStateException::class)
    fun cursorRefusesToReadPastTheEnd() {
        val cursor = FrameCursor(ByteArray(3))
        cursor.current(20)
        cursor.ack()
        cursor.current(20)
    }

    // ---------------------------------------------------------------- lịch nối lại

    @Test
    fun reconnectTriesDirectlyThreeTimesThenWaitsInTheBackground() {
        val policy = ReconnectPolicy()
        assertEquals(ReconnectAttempt(1_000, background = false), policy.next())
        assertEquals(ReconnectAttempt(2_000, background = false), policy.next())
        assertEquals(ReconnectAttempt(4_000, background = false), policy.next())
        repeat(50) {
            val attempt = policy.next()
            assertTrue(attempt.background)
            assertTrue(attempt.delayMs > 0) // không bao giờ quay vòng không nghỉ
        }
    }

    @Test
    fun reconnectStartsOverAfterASuccessfulConnection() {
        val policy = ReconnectPolicy()
        repeat(7) { policy.next() }
        policy.reset()
        assertEquals(ReconnectAttempt(1_000, background = false), policy.next())
    }

    // ---------------------------------------------------------------- các bước GATT

    private fun connecting(background: Boolean = false) = GattSetupMachine().apply {
        assertTrue(on(GattEvent.Started(background)).isEmpty())
        assertEquals(GattStep.CONNECT, step)
    }

    private fun ready(mtu: Int = 247) = connecting().apply {
        on(GattEvent.Connected)
        on(GattEvent.MtuChanged(mtu, ok = true))
        on(GattEvent.ServicesDiscovered(ok = true, found = true))
        on(GattEvent.Subscribed(ok = true))
        assertEquals(GattStep.READY, step)
    }

    @Test
    fun setupRunsOneStepAtATimeInTheRequiredOrder() {
        val m = connecting()
        assertEquals(listOf<GattAction>(GattAction.RequestMtu), m.on(GattEvent.Connected))
        assertEquals(GattStep.MTU, m.step)
        assertNull(m.mtu)
        assertEquals(listOf<GattAction>(GattAction.DiscoverServices), m.on(GattEvent.MtuChanged(247, ok = true)))
        assertEquals(GattStep.DISCOVER, m.step)
        assertEquals(247, m.mtu)
        assertEquals(listOf<GattAction>(GattAction.EnableNotifications), m.on(GattEvent.ServicesDiscovered(ok = true, found = true)))
        assertEquals(GattStep.SUBSCRIBE, m.step)
        assertEquals(listOf<GattAction>(GattAction.AnnounceReady), m.on(GattEvent.Subscribed(ok = true)))
        assertEquals(GattStep.READY, m.step)
    }

    @Test
    fun everyStepHasItsTimeout() {
        val m = connecting()
        assertEquals(GattSetupMachine.CONNECT_TIMEOUT_MS, m.timeoutMs)
        m.on(GattEvent.Connected)
        assertEquals(2_000L, m.timeoutMs)
        m.on(GattEvent.MtuChanged(247, ok = true))
        assertEquals(5_000L, m.timeoutMs)
        m.on(GattEvent.ServicesDiscovered(ok = true, found = true))
        assertEquals(5_000L, m.timeoutMs)
        m.on(GattEvent.Subscribed(ok = true))
        assertEquals(0L, m.timeoutMs)
        // kết nối chờ (autoConnect) được phép treo vô hạn
        assertEquals(0L, connecting(background = true).timeoutMs)
    }

    @Test
    fun missingMtuAnswerFallsBackToTwentyByteWrites() {
        val noAnswer = listOf(GattEvent.TimedOut(GattStep.MTU), GattEvent.Rejected, GattEvent.MtuChanged(0, ok = false))
        for (event in noAnswer) {
            val m = connecting()
            m.on(GattEvent.Connected)
            assertEquals("after $event", listOf<GattAction>(GattAction.DiscoverServices), m.on(event))
            assertEquals(GattStep.DISCOVER, m.step)
            assertEquals(23, m.mtu)
            assertEquals(20, BleChunks.payloadSize(m.mtu))
        }
    }

    @Test
    fun lateOrPeripheralInitiatedMtuChangeOnlyUpdatesTheSize() {
        val m = connecting()
        m.on(GattEvent.Connected)
        m.on(GattEvent.TimedOut(GattStep.MTU))
        // câu trả lời tới muộn, khi đã sang bước khác: không được chạy discoverServices lần hai
        assertTrue(m.on(GattEvent.MtuChanged(247, ok = true)).isEmpty())
        assertEquals(GattStep.DISCOVER, m.step)
        assertEquals(247, m.mtu)

        val r = ready(mtu = 100)
        assertTrue(r.on(GattEvent.MtuChanged(247, ok = true)).isEmpty())
        assertEquals(GattStep.READY, r.step)
        assertEquals(244, BleChunks.payloadSize(r.mtu))
        // đổi MTU thất bại thì giữ cỡ cũ
        assertTrue(r.on(GattEvent.MtuChanged(23, ok = false)).isEmpty())
        assertEquals(247, r.mtu)
    }

    @Test
    fun aDeviceWithoutTheServiceIsNotRetried() {
        val m = connecting()
        m.on(GattEvent.Connected)
        m.on(GattEvent.MtuChanged(247, ok = true))
        val actions = m.on(GattEvent.ServicesDiscovered(ok = true, found = false))
        assertEquals(1, actions.size)
        assertFalse((actions[0] as GattAction.TearDown).retry)
        assertEquals(GattStep.IDLE, m.step)
    }

    @Test
    fun failuresTearDownAndRetry() {
        val failing: List<Pair<String, (GattSetupMachine) -> List<GattAction>>> = listOf(
            "connect timeout" to { m -> m.on(GattEvent.TimedOut(GattStep.CONNECT)) },
            "connect rejected" to { m -> m.on(GattEvent.Rejected) },
            "discover failed" to { m ->
                m.on(GattEvent.Connected)
                m.on(GattEvent.MtuChanged(247, ok = true))
                m.on(GattEvent.ServicesDiscovered(ok = false, found = false))
            },
            "discover timeout" to { m ->
                m.on(GattEvent.Connected)
                m.on(GattEvent.MtuChanged(247, ok = true))
                m.on(GattEvent.TimedOut(GattStep.DISCOVER))
            },
            "subscribe failed" to { m ->
                m.on(GattEvent.Connected)
                m.on(GattEvent.MtuChanged(247, ok = true))
                m.on(GattEvent.ServicesDiscovered(ok = true, found = true))
                m.on(GattEvent.Subscribed(ok = false))
            },
            "subscribe rejected" to { m ->
                m.on(GattEvent.Connected)
                m.on(GattEvent.MtuChanged(247, ok = true))
                m.on(GattEvent.ServicesDiscovered(ok = true, found = true))
                m.on(GattEvent.Rejected)
            },
            "lost while connecting" to { m -> m.on(GattEvent.Lost("status 133")) },
        )
        for ((name, run) in failing) {
            val m = connecting()
            val actions = run(m)
            assertEquals(name, 1, actions.size)
            assertTrue(name, (actions[0] as GattAction.TearDown).retry)
            assertEquals(name, GattStep.IDLE, m.step)
            assertNull(name, m.mtu)
        }
    }

    @Test
    fun losingAReadyLinkTearsDownOnce() {
        val m = ready()
        val actions = m.on(GattEvent.Lost("disconnected (status 8)"))
        assertEquals(listOf<GattAction>(GattAction.TearDown("disconnected (status 8)", retry = true)), actions)
        // sự kiện thứ hai cho cùng một lần đứt (hạn chờ ghi + callback ngắt tới sát nhau) không được dọn dẹp lần nữa
        assertTrue(m.on(GattEvent.Lost("again")).isEmpty())
        assertTrue(m.on(GattEvent.TimedOut(GattStep.READY)).isEmpty())
    }

    @Test
    fun eventsOfAnotherStepAreIgnored() {
        val m = connecting()
        assertTrue(m.on(GattEvent.Subscribed(ok = true)).isEmpty())
        assertTrue(m.on(GattEvent.ServicesDiscovered(ok = true, found = true)).isEmpty())
        assertTrue(m.on(GattEvent.TimedOut(GattStep.DISCOVER)).isEmpty()) // hạn chờ cũ của bước khác
        assertEquals(GattStep.CONNECT, m.step)

        val idle = GattSetupMachine()
        val events = listOf(GattEvent.Connected, GattEvent.Rejected, GattEvent.TimedOut(GattStep.IDLE), GattEvent.Lost("x"))
        for (event in events) {
            assertTrue(idle.on(event).isEmpty())
            assertEquals(GattStep.IDLE, idle.step)
        }
    }

    @Test
    fun aNewConnectionForgetsTheOldMtu() {
        val m = ready(mtu = 247)
        m.on(GattEvent.Lost("gone"))
        m.on(GattEvent.Started(background = true))
        assertNull(m.mtu)
        assertEquals(20, BleChunks.payloadSize(m.mtu))
    }

    /** Bơm sự kiện ngẫu nhiên: dù callback tới theo thứ tự nào, máy cũng không được nhảy cóc bước. */
    @Test
    fun randomEventStormsNeverSkipAStep() {
        val random = Random(20261014)
        val order = listOf(GattStep.CONNECT, GattStep.MTU, GattStep.DISCOVER, GattStep.SUBSCRIBE, GattStep.READY)
        var reachedReady = 0
        repeat(3_000) {
            val m = GattSetupMachine()
            repeat(40) {
                val before = m.step
                val event: GattEvent = when (random.nextInt(12)) {
                    0 -> GattEvent.Started(random.nextBoolean())
                    1, 2 -> GattEvent.Connected
                    3, 4 -> GattEvent.MtuChanged(23 + random.nextInt(500), random.nextInt(4) != 0)
                    5, 6 -> GattEvent.ServicesDiscovered(random.nextInt(6) != 0, random.nextInt(6) != 0)
                    7, 8 -> GattEvent.Subscribed(random.nextInt(6) != 0)
                    9 -> GattEvent.Rejected
                    10 -> GattEvent.TimedOut(GattStep.entries[random.nextInt(GattStep.entries.size)])
                    else -> GattEvent.Lost("lost")
                }
                val actions = m.on(event)
                val after = m.step
                assertTrue("at most one action per event", actions.size <= 1)
                when (actions.firstOrNull()) {
                    GattAction.RequestMtu -> assertEquals(GattStep.CONNECT to GattStep.MTU, before to after)
                    GattAction.DiscoverServices -> assertEquals(GattStep.MTU to GattStep.DISCOVER, before to after)
                    GattAction.EnableNotifications -> assertEquals(GattStep.DISCOVER to GattStep.SUBSCRIBE, before to after)
                    GattAction.AnnounceReady -> {
                        assertEquals(GattStep.SUBSCRIBE to GattStep.READY, before to after)
                        reachedReady++
                    }
                    is GattAction.TearDown -> {
                        assertTrue(before != GattStep.IDLE)
                        assertEquals(GattStep.IDLE, after)
                    }
                    null -> if (event !is GattEvent.Started) assertEquals("$event must not move $before", before, after)
                }
                // chỉ tiến đúng một bước, hoặc về IDLE, hoặc bắt đầu lại ở CONNECT
                val legal = after == before || after == GattStep.IDLE ||
                    (event is GattEvent.Started && after == GattStep.CONNECT) ||
                    order.indexOf(after) == order.indexOf(before) + 1
                assertTrue("$before -> $after on $event", legal)
                // cỡ mẩu luôn nằm trong khoảng hợp lệ
                assertTrue(BleChunks.payloadSize(m.mtu) in 20..244)
            }
        }
        // phép thử chỉ có nghĩa nếu chuỗi ngẫu nhiên thật sự đi được tới READY
        assertTrue("random storms reached READY only $reachedReady times", reachedReady > 20)
    }
}
