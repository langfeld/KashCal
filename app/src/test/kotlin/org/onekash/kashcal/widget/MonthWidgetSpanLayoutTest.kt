package org.onekash.kashcal.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.widget.WidgetDataRepository.WidgetEvent

/**
 * Pure-logic tests for [computeMonthWidgetWeekRender] — the week slot layout behind the
 * month widget's titles mode (multi-day bars, per-cell snippets, overflow).
 *
 * Week under test: Monday 2026-08-03 .. Sunday 2026-08-09 (day codes 20260803..20260809).
 */
class MonthWidgetSpanLayoutTest {

    private val week = (3..9).map { 20260800 + it }

    // ==================== Basics ====================

    @Test
    fun `no events yields no slot rows`() {
        val render = computeMonthWidgetWeekRender(week, emptyMap(), maxSlots = 3)
        assertTrue(render.slots.isEmpty())
    }

    @Test
    fun `zero slot budget yields no rows`() {
        val events = mapOf(week[0] to listOf(timed(day = 3)))
        val render = computeMonthWidgetWeekRender(week, events, maxSlots = 0)
        assertTrue(render.slots.isEmpty())
    }

    @Test
    fun `single-day events land in their own column`() {
        val events = mapOf(
            week[0] to listOf(timed(day = 3)),
            week[4] to listOf(timed(day = 7), timed(day = 7, title = "Second"))
        )
        val render = computeMonthWidgetWeekRender(week, events, maxSlots = 3)
        assertEquals(2, render.slots.size)
        assertTrue(render.slots[0][0] is MonthWidgetSlot.CellEvent)
        assertEquals(MonthWidgetSlot.Empty, render.slots[1][0])
        assertTrue(render.slots[0][4] is MonthWidgetSlot.CellEvent)
        assertTrue(render.slots[1][4] is MonthWidgetSlot.CellEvent)
    }

    // ==================== Multi-day spans ====================

    @Test
    fun `multi-day event spans its columns as one run of bar segments`() {
        val span = multiDay(startDay = 20260804, endDay = 20260806)
        val render = computeMonthWidgetWeekRender(week, mapOf(week[1] to listOf(span)), maxSlots = 3)
        assertEquals(1, render.slots.size)
        val row = render.slots[0]
        assertEquals(MonthWidgetSlot.Empty, row[0])
        for (col in 1..3) {
            val segment = row[col] as? MonthWidgetSlot.BarSegment
                ?: error("col $col should be a BarSegment")
            assertEquals(span.eventId, segment.span.event.eventId)
        }
        assertEquals(MonthWidgetSlot.Empty, row[4])
    }

    @Test
    fun `span continuing from the previous week starts flush at column 0`() {
        val span = multiDay(startDay = 20260730, endDay = 20260805)
        val render = computeMonthWidgetWeekRender(week, mapOf(week[2] to listOf(span)), maxSlots = 3)
        val segment = render.slots[0][0] as MonthWidgetSlot.BarSegment
        assertTrue(segment.span.leftFlush)
        assertTrue(!segment.span.rightFlush)
        assertEquals(0, segment.span.startCol)
        assertEquals(2, segment.span.endCol)
    }

    @Test
    fun `span continuing into the next week ends flush at column 6`() {
        val span = multiDay(startDay = 20260807, endDay = 20260812)
        val render = computeMonthWidgetWeekRender(week, mapOf(week[4] to listOf(span)), maxSlots = 3)
        val segment = render.slots[0][6] as MonthWidgetSlot.BarSegment
        assertTrue(!segment.span.leftFlush)
        assertTrue(segment.span.rightFlush)
        assertEquals(4, segment.span.startCol)
        assertEquals(6, segment.span.endCol)
    }

    @Test
    fun `overlapping spans occupy separate lanes`() {
        val a = multiDay(startDay = 20260804, endDay = 20260806, title = "A")
        val b = multiDay(startDay = 20260805, endDay = 20260807, title = "B")
        val render = computeMonthWidgetWeekRender(week, mapOf(week[1] to listOf(a, b)), maxSlots = 3)
        assertEquals(2, render.slots.size)
        // A (earlier start) takes lane 0, B lane 1 — they overlap on columns 2..3.
        val aSeg = render.slots[0][2] as MonthWidgetSlot.BarSegment
        val bSeg = render.slots[1][2] as MonthWidgetSlot.BarSegment
        assertEquals("A", aSeg.span.event.title)
        assertEquals("B", bSeg.span.event.title)
    }

    @Test
    fun `non-overlapping spans share one lane`() {
        val a = multiDay(startDay = 20260803, endDay = 20260804, title = "A")
        val b = multiDay(startDay = 20260806, endDay = 20260808, title = "B")
        val render = computeMonthWidgetWeekRender(week, mapOf(week[0] to listOf(a), week[3] to listOf(b)), maxSlots = 3)
        assertEquals(1, render.slots.size)
        assertEquals("A", (render.slots[0][0] as MonthWidgetSlot.BarSegment).span.event.title)
        assertEquals("B", (render.slots[0][3] as MonthWidgetSlot.BarSegment).span.event.title)
    }

    @Test
    fun `span is deduplicated across the day buckets it appears in`() {
        val span = multiDay(startDay = 20260804, endDay = 20260806)
        // The repository groups a multi-day event into EVERY day it touches — the layout
        // must still render exactly one bar for it.
        val events = mapOf(
            week[1] to listOf(span),
            week[2] to listOf(span),
            week[3] to listOf(span)
        )
        val render = computeMonthWidgetWeekRender(week, events, maxSlots = 3)
        assertEquals(1, render.slots.size)
        val segments = render.slots[0].filterIsInstance<MonthWidgetSlot.BarSegment>()
        assertEquals("one bar run across Tue..Thu", 3, segments.size)
        assertEquals("all segments belong to the same event", 1, segments.map { it.span.event.spanKey }.distinct().size)
    }

    // ==================== Overflow ====================

    @Test
    fun `cell events beyond the slot budget collapse into an overflow slot`() {
        val events = mapOf(week[2] to List(4) { timed(day = 5, title = "E$it") })
        val render = computeMonthWidgetWeekRender(week, events, maxSlots = 3)
        assertEquals(3, render.slots.size)
        // Last slot of the column: 2 visible events + "+2".
        assertTrue(render.slots[0][2] is MonthWidgetSlot.CellEvent)
        assertTrue(render.slots[1][2] is MonthWidgetSlot.CellEvent)
        val overflow = render.slots[2][2] as MonthWidgetSlot.Overflow
        assertEquals(2, overflow.count)
    }

    @Test
    fun `overflow counts only the hidden events`() {
        val events = mapOf(week[2] to List(3) { timed(day = 5, title = "E$it") })
        val render = computeMonthWidgetWeekRender(week, events, maxSlots = 3)
        assertEquals(3, render.slots.size)
        assertTrue(render.slots.all { it[2] is MonthWidgetSlot.CellEvent })
    }

    @Test
    fun `lanes win over cell events when a column is fully bar-occupied`() {
        // Two bars cover the whole week in lanes 0 and 1; with maxSlots = 2 the column has
        // no free slot left, so its single-day event drops without an overflow marker.
        val a = multiDay(startDay = 20260803, endDay = 20260809, title = "A")
        val b = multiDay(startDay = 20260803, endDay = 20260809, title = "B")
        val events = mapOf(
            week[0] to listOf(a, b, timed(day = 3))
        )
        val render = computeMonthWidgetWeekRender(week, events, maxSlots = 2)
        assertEquals(2, render.slots.size)
        assertTrue(render.slots.all { row -> row.all { it is MonthWidgetSlot.BarSegment } })
    }

    // ==================== Sorting ====================

    @Test
    fun `all-day busy sorts before all-day free, timed last`() {
        val events = mapOf(
            week[0] to listOf(
                timed(day = 3, title = "Timed"),
                allDay(day = 3, title = "Free", isFree = true),
                allDay(day = 3, title = "Busy")
            )
        )
        val render = computeMonthWidgetWeekRender(week, events, maxSlots = 3)
        val titles = render.slots.map { (it[0] as MonthWidgetSlot.CellEvent).event.title }
        assertEquals(listOf("Busy", "Free", "Timed"), titles)
    }

    // ==================== Helpers ====================

    private fun timed(day: Int, title: String = "Timed") = WidgetEvent(
        eventId = title.hashCode().toLong(),
        occurrenceStartTs = day * 1000L,
        title = title,
        startTs = day * 1000L,
        endTs = day * 1000L + 3_600_000L,
        isAllDay = false,
        calendarColor = 0xFF2196F3.toInt(),
        isPast = false,
        isDeviceEvent = false,
        startDay = 20260800 + day,
        endDay = 20260800 + day
    )

    private fun allDay(day: Int, title: String, isFree: Boolean = false) = WidgetEvent(
        eventId = title.hashCode().toLong(),
        occurrenceStartTs = day * 1000L,
        title = title,
        startTs = day * 1000L,
        endTs = day * 1000L + 86_400_000L,
        isAllDay = true,
        calendarColor = 0xFF43A047.toInt(),
        isPast = false,
        isDeviceEvent = false,
        startDay = 20260800 + day,
        endDay = 20260800 + day,
        isFree = isFree
    )

    private fun multiDay(startDay: Int, endDay: Int, title: String = "Trip") = WidgetEvent(
        eventId = "$title$startDay".hashCode().toLong(),
        occurrenceStartTs = startDay * 1000L,
        title = title,
        startTs = startDay * 1000L,
        endTs = endDay * 1000L,
        isAllDay = true,
        calendarColor = 0xFF7E57C2.toInt(),
        isPast = false,
        isDeviceEvent = false,
        startDay = startDay,
        endDay = endDay
    )
}
