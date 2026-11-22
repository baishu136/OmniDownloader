package com.omni.downloader.ui.components

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.omni.downloader.engine.RequestProtectionWarning
import com.omni.downloader.ui.localization.LocalAppStrings
import java.text.DateFormat
import java.util.Date

@Composable
internal fun RequestProtectionDialog(warning:RequestProtectionWarning,onAcknowledge:()->Unit,onAccount:()->Unit) {
    val strings=LocalAppStrings.current
    AlertDialog(onDismissRequest=onAcknowledge,modifier=Modifier.testTag("request_protection_warning"),
        title={Text(strings.requestWarningTitle.format(warning.label))},
        text={Text(strings.requestWarningBody+if(warning.until>System.currentTimeMillis())
            "\n\n"+strings.requestWarningWait.format(DateFormat.getDateTimeInstance().format(Date(warning.until))) else "")},
        confirmButton={TextButton(onClick=onAcknowledge,modifier=Modifier.testTag("request_warning_ack")){Text(strings.requestWarningConfirm)}},
        dismissButton={TextButton(onClick=onAccount,modifier=Modifier.testTag("request_warning_account")){Text(strings.requestWarningAccount)}})
}
