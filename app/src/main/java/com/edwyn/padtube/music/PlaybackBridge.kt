package com.edwyn.padtube.music

enum class PlaybackCommand {
    PLAY,
    PAUSE,
    NEXT,
    PREVIOUS,
    VIDEO
}

fun interface PlaybackCommandHandler {
    fun handle(command: PlaybackCommand)
}

object PlaybackBridge {
    @Volatile
    private var handler: PlaybackCommandHandler? = null

    @Volatile
    private var seekHandler: ((Long) -> Unit)? = null

    fun attach(newHandler: PlaybackCommandHandler, onSeek: ((Long) -> Unit)? = null) {
        handler = newHandler
        seekHandler = onSeek
    }

    fun detach(oldHandler: PlaybackCommandHandler) {
        if (handler === oldHandler) {
            handler = null
            seekHandler = null
        }
    }

    fun dispatch(command: PlaybackCommand) {
        handler?.handle(command)
    }

    fun dispatchSeek(positionMs: Long) {
        seekHandler?.invoke(positionMs)
    }
}
