package com.ebani.sinage.util

import kotlinx.coroutines.flow.MutableSharedFlow

object PlayerBus {
    sealed class Command {
        object CheckWithServerForPair : Command()
        object EmitRegisteredShake : Command()
    }
    val commands = MutableSharedFlow<Command>(extraBufferCapacity = 64)
}