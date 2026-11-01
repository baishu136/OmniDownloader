package com.omni.downloader.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.omni.downloader.data.model.DownloadTask
import com.omni.downloader.data.model.DownloadType
import com.omni.downloader.data.model.TaskStatus
import com.omni.downloader.ui.components.CollectionTaskCard
import com.omni.downloader.ui.localization.LocalAppStrings
import com.omni.downloader.ui.localization.resolveAppStrings
import org.junit.Rule
import org.junit.Test

class CollectionTaskCardTest {
    @get:Rule val compose = createComposeRule()
    @Test fun everyEpisodeInLargeCollectionIsReachable() {
        val strings = resolveAppStrings("en")
        val episodes = (1..120).map { n ->
            DownloadTask(n.toString(), "https://example.org", "Episode " + n,
                downloadType = DownloadType.VIDEO_WITH_AUDIO, status = TaskStatus.COMPLETED,
                collectionId = "large", episodeIndex = n)
        }
        compose.setContent {
            CompositionLocalProvider(LocalAppStrings provides strings) {
                MaterialTheme {
                    CollectionTaskCard("large", "Collection", episodes, {}, { _, _ -> }, {}, {})
                }
            }
        }
        compose.onNodeWithText(String.format(strings.collectionExpandDetails, 120, 120)).performClick()
        compose.onNode(hasScrollAction()).performScrollToIndex(119)
        compose.onNodeWithText("Episode 120").assertIsDisplayed()
    }
}
