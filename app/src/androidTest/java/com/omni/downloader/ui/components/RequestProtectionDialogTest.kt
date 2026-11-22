package com.omni.downloader.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.omni.downloader.engine.RequestProtectionWarning
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RequestProtectionDialogTest {
    @get:Rule val compose=createComposeRule()
    @Test fun warningOffersAcknowledgmentAndAccountManagementWithoutAutomaticRetry() {
        var acknowledged=0;var account=0
        compose.setContent{MaterialTheme{RequestProtectionDialog(RequestProtectionWarning("x","X","fixture","AUTH_REQUIRED",0),{acknowledged++},{account++})}}
        compose.onNodeWithTag("request_protection_warning").assertExists()
        compose.onNodeWithTag("request_warning_ack").performClick();assertEquals(1,acknowledged);assertEquals(0,account)
        compose.onNodeWithTag("request_warning_account").performClick();assertEquals(1,account)
    }
}
