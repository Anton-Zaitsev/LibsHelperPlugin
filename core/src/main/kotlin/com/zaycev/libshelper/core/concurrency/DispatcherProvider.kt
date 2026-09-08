package com.zaycev.libshelper.core.concurrency

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

interface DispatcherProvider {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
}

@Suppress("InjectDispatcher")
private object StandardDispatcherProvider : DispatcherProvider {
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val default: CoroutineDispatcher get() = Dispatchers.Default
}

fun standardDispatcherProvider(): DispatcherProvider = StandardDispatcherProvider
