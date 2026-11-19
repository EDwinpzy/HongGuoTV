// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

data class CloudHistoryPlan(
    val apply: List<BackupProgress>,
    val upload: List<CloudProgress>,
)

/** Newest checkpoint wins while tombstones keep locally deleted rows deleted. */
object CloudHistorySync {
    fun plan(
        cloud: List<CloudProgress>,
        local: List<BackupProgress>,
        deleted: Set<String>,
    ): CloudHistoryPlan {
        val cloudById = cloud.distinctBy { it.series.id }.associateBy { it.series.id }
        val localById = local.distinctBy { it.series.id }.associateBy { it.series.id }
        val apply =
            cloudById.values
                .mapNotNull { remote ->
                    if (remote.series.id in deleted) return@mapNotNull null
                    val current = localById[remote.series.id]
                    if (current != null && remote.updatedAt <= current.updatedAt) return@mapNotNull null
                    BackupProgress(
                        remote.series,
                        remote.episodeId,
                        remote.episodeIndex,
                        remote.position,
                        remote.duration,
                        false,
                        remote.updatedAt,
                    )
                }
                .sortedByDescending { it.updatedAt }
        val upload =
            localById.values
                .mapNotNull { progress ->
                    if (progress.series.id in deleted || progress.episodeId.isBlank()) return@mapNotNull null
                    val remote = cloudById[progress.series.id]
                    if (remote != null && progress.updatedAt <= remote.updatedAt) return@mapNotNull null
                    CloudProgress(
                        progress.series,
                        progress.episodeId,
                        progress.episodeIndex,
                        progress.position,
                        progress.duration,
                        progress.updatedAt,
                        episodeCount(progress.series.badge),
                    )
                }
                .sortedByDescending { it.updatedAt }
        return CloudHistoryPlan(apply, upload)
    }

    private fun episodeCount(badge: String): Int =
        Regex("(\\d{1,5})").find(badge)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}
