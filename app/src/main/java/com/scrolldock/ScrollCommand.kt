package com.scrolldock

enum class ScrollCommand {
    PAGE_UP,
    PAGE_DOWN,
    TOP,
    BOTTOM,
    STOP,
}

enum class ScrollDirection { UP, DOWN }

enum class StepResult { MOVED, NO_MOVEMENT, FAILED }
