package com.aliothmoon.maadroid.data.api.model

import kotlinx.serialization.Serializable

@Serializable
data class MirrorChyanResponse(
    val code: Int,
    val msg: String? = null,
    val data: MirrorChyanData? = null
) {
    companion object {
        val UNKNOWN_ERR = MirrorChyanResponse(-1)
    }
}