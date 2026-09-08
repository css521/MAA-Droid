package com.aliothmoon.maadroid.data.model

import kotlinx.serialization.Serializable


@Serializable
data class AssetManifest(val files: List<String>)
