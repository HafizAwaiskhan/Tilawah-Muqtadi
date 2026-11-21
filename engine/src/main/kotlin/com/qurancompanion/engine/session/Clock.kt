package com.qurancompanion.engine.session

/** Injectable time source (milliseconds). Tests use a manual clock. */
fun interface Clock {
    fun nowMs(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}
