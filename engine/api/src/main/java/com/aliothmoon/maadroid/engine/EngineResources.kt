package com.aliothmoon.maadroid.engine

import java.io.File
import java.util.Collections

/**
 * One engine's resource paths, keyed by the pack IDs declared by [GameProfile].
 *
 * This is an immutable snapshot of paths only. Construction and lookup perform no I/O;
 * they do not establish that directories exist or that their contents have been verified.
 * Declared packs may be absent, including an empty collection for a resource-free profile.
 */
class EngineResources(profile: GameProfile, directories: Map<String, File>) {
    val engineId: String = profile.id

    private val declaredPackIds: Set<String> = profile.resourcePacks.map { pack ->
        require(pack.engineId == engineId) {
            "Resource pack ${pack.packId} belongs to ${pack.engineId}, not $engineId"
        }
        pack.packId
    }.toSet()

    val directories: Map<String, File> = Collections.unmodifiableMap(LinkedHashMap(directories))

    init {
        for (packId in this.directories.keys) {
            require(packId in declaredPackIds) {
                "Resource pack $packId is not declared by engine $engineId"
            }
        }
    }

    /** Returns a declared pack's path, or null when its directory has not been supplied. */
    fun directory(pack: ResourcePackSpec): File? {
        require(pack.engineId == engineId) {
            "Resource pack ${pack.packId} belongs to ${pack.engineId}, not $engineId"
        }
        require(pack.packId in declaredPackIds) {
            "Resource pack ${pack.packId} is not declared by engine $engineId"
        }
        return directories[pack.packId]
    }

    /** Requires a supplied path; its existence and contents are not checked here. */
    fun requireDirectory(pack: ResourcePackSpec): File = checkNotNull(directory(pack)) {
        "Missing resource directory for pack ${pack.packId} (engine $engineId)"
    }
}
