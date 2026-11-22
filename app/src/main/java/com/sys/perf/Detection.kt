package com.sys.perf

data class Detection(
    val x: Int,
    val y: Int,
    val headX: Int,
    val headY: Int,
    val neckY: Int,
    val bodyY: Int,
    val area: Double,
    val confidence: Float
)
