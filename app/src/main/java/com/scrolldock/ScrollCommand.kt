package com.scrolldock

enum class ScrollCommand {
    PAGE_UP,
    PAGE_DOWN,
    TOP,
    BOTTOM,
    PREVIOUS_MESSAGE,
    NEXT_MESSAGE,
    PREVIOUS_USER_MESSAGE,
    NEXT_USER_MESSAGE,
    PREVIOUS_ASSISTANT_MESSAGE,
    NEXT_ASSISTANT_MESSAGE,
    STOP,
}

enum class ScrollDirection { UP, DOWN }

enum class MessageDirection { PREVIOUS, NEXT }

enum class StepResult { MOVED, EDGE, FAILED }

fun ScrollCommand.messageRequestOrNull(): Pair<MessageDirection, MessageRole>? = when (this) {
    ScrollCommand.PREVIOUS_MESSAGE -> MessageDirection.PREVIOUS to MessageRole.ANY
    ScrollCommand.NEXT_MESSAGE -> MessageDirection.NEXT to MessageRole.ANY
    ScrollCommand.PREVIOUS_USER_MESSAGE -> MessageDirection.PREVIOUS to MessageRole.USER
    ScrollCommand.NEXT_USER_MESSAGE -> MessageDirection.NEXT to MessageRole.USER
    ScrollCommand.PREVIOUS_ASSISTANT_MESSAGE -> MessageDirection.PREVIOUS to MessageRole.ASSISTANT
    ScrollCommand.NEXT_ASSISTANT_MESSAGE -> MessageDirection.NEXT to MessageRole.ASSISTANT
    else -> null
}