package com.sb.phototool.ui.gif

import java.util.Locale

fun gifTime(ms: Long): String = String.format(Locale.US, "%.2f초", ms / 1000.0)

fun <T> movedFrame(frames: List<T>, from: Int, to: Int): List<T> =
    if (from !in frames.indices || to !in frames.indices) frames else frames.toMutableList().apply { add(to, removeAt(from)) }
