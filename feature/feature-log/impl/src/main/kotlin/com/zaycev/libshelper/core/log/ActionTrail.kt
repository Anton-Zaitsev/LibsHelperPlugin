package com.zaycev.libshelper.core.log

import java.util.ArrayDeque

class ActionTrail(
    private val capacity: Int = 200,
    private val clock: () -> Long = System::currentTimeMillis,
) : ActionLog {
    private val events = ArrayDeque<ActionEvent>(capacity)

    @Synchronized
    override fun record(category: String, action: String, detail: String?) {
        if (events.size >= capacity) events.removeFirst()
        events.addLast(ActionEvent(clock(), category, action, detail))
    }

    @Synchronized
    override fun snapshot(): List<ActionEvent> = events.toList()
}
