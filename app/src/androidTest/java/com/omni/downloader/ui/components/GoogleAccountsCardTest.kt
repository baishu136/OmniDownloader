package com.omni.downloader.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.omni.downloader.data.repository.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class GoogleAccountsCardTest {
    @get:Rule val compose=createComposeRule()
    @Test fun selectingMultipleProfilesAndSigningOutCurrentStayIndependent() {
        compose.runOnIdle{assumeTrue(GoogleBrowserProfile.supported())}
        GoogleAccountsTest.Fixture().use{fixture->
            val repo=fixture.accounts
            val ids=runBlocking {val result=listOf("A","B","C").map{repo.add(it).getOrThrow()};repo.activate(result[1]).getOrThrow();result}
            compose.setContent{MaterialTheme{Column(Modifier.verticalScroll(rememberScrollState())){GoogleAccountsCard(SessionPlatforms.PIXIV,{},repo)}}}
            compose.onNodeWithTag("google_select_${ids[0]}").performScrollTo().performClick()
            compose.onNodeWithTag("google_select_${ids[2]}").performScrollTo().performClick()
            compose.onNodeWithTag("google_logout_selected").performScrollTo().performClick()
            compose.onNodeWithTag("google_logout_confirm").performClick()
            compose.waitUntil(10000){repo.state.value.accounts.filter{it.id!=ids[1]}.all{it.signedOut && it.pendingEpoch==null}}
            assertFalse(repo.state.value.accounts.single{it.id==ids[1]}.signedOut)
            compose.onNodeWithTag("google_logout_current").performScrollTo().performClick()
            compose.onNodeWithTag("google_logout_confirm").performClick()
            compose.waitUntil(10000){repo.state.value.accounts.all{it.signedOut && it.pendingEpoch==null}}
            compose.onNodeWithTag("google_logout_current").assertIsNotEnabled()
        }
    }
}
