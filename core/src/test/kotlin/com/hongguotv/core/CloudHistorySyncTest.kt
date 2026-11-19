// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudHistorySyncTest {
    private fun series(id: String) = Series(id, "剧 $id", "cover-$id", badge = "全 20 集")

    private fun cloud(id: String, updatedAt: Long, position: Long = 10_000) =
        CloudProgress(series(id), "ep-$id", 2, position, 90_000, updatedAt, 20)

    private fun local(id: String, updatedAt: Long, position: Long = 10_000) =
        BackupProgress(series(id), "ep-$id", 2, position, 90_000, false, updatedAt)

    @Test
    fun newerCloudCheckpointAppliesAndClearsCompletedStateInCaller() {
        val plan = CloudHistorySync.plan(listOf(cloud("1", 2_000)), listOf(local("1", 1_000)), emptySet())
        assertEquals(1, plan.apply.size)
        assertEquals(2_000L, plan.apply.single().updatedAt)
        assertTrue(plan.upload.isEmpty())
    }

    @Test
    fun newerLocalCheckpointUploadsWithoutApplyingStaleCloud() {
        val plan = CloudHistorySync.plan(listOf(cloud("1", 1_000)), listOf(local("1", 2_000, 30_000)), emptySet())
        assertTrue(plan.apply.isEmpty())
        assertEquals(1, plan.upload.size)
        assertEquals(30_000L, plan.upload.single().position)
        assertEquals(20, plan.upload.single().episodeCount)
    }

    @Test
    fun localOnlyCheckpointUploads() {
        val plan = CloudHistorySync.plan(emptyList(), listOf(local("2", 3_000)), emptySet())
        assertEquals(listOf("2"), plan.upload.map { it.series.id })
    }

    @Test
    fun tombstonesBlockApplyAndUploadUntilPlaybackClearsThem() {
        val plan =
            CloudHistorySync.plan(
                listOf(cloud("1", 2_000), cloud("2", 2_000)),
                listOf(local("1", 1_000), local("2", 3_000)),
                setOf("1", "2"),
            )
        assertTrue(plan.apply.isEmpty())
        assertTrue(plan.upload.isEmpty())
    }

    @Test
    fun sameTimestampDoesNotPingPongUploads() {
        val plan = CloudHistorySync.plan(listOf(cloud("1", 2_000)), listOf(local("1", 2_000)), emptySet())
        assertTrue(plan.apply.isEmpty())
        assertTrue(plan.upload.isEmpty())
    }
}
