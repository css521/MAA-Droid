package com.aliothmoon.maadroid.schedule.model

sealed class CountdownState {
    data class Counting(val strategyName: String, val remainingSeconds: Int) : CountdownState()
    data object Executing : CountdownState()
    data object Idle : CountdownState()
}
