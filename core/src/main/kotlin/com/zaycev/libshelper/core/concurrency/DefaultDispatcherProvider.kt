package com.zaycev.libshelper.core.concurrency

import com.zaycev.libshelper.core.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class DefaultDispatcherProvider : DispatcherProvider by standardDispatcherProvider()
