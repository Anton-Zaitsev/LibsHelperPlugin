package com.zaycev.libshelper.core.metadata

import com.zaycev.libshelper.core.model.ConnectStatus
import com.zaycev.libshelper.core.network.HttpFailure

fun connectStatusOf(failure: HttpFailure?): ConnectStatus = when (failure) {
    is HttpFailure.Timeout -> ConnectStatus.Timeout
    is HttpFailure.Unauthorized -> ConnectStatus.Unauthorized
    is HttpFailure.Forbidden -> ConnectStatus.Forbidden
    is HttpFailure.NotFound, is HttpFailure.Unreachable, is HttpFailure.HttpStatus -> ConnectStatus.Unreachable
    null -> ConnectStatus.Online
}
