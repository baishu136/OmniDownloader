package com.omni.downloader.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omni.downloader.data.repository.*
import com.omni.downloader.engine.LogSanitizer
import com.omni.downloader.ui.design.OmniCard
import com.omni.downloader.ui.localization.LocalAppStrings
import kotlinx.coroutines.launch

@Composable
internal fun GoogleAccountsCard(platform:String,onLegacy:()->Unit,
    repository:GoogleAccountRepository=GoogleAccountRepository.getInstance(LocalContext.current)) {
    val strings=LocalAppStrings.current
    val state by repository.state.collectAsStateWithLifecycle();val scope=rememberCoroutineScope()
    val supported=remember{GoogleBrowserProfile.supported()}
    var selected by remember{mutableStateOf(setOf<String>())}
    var showAdd by remember{mutableStateOf(false)};var label by remember{mutableStateOf("")}
    var confirm by remember{mutableStateOf(setOf<String>())}
    var openAccount by remember{mutableStateOf<GoogleLoginAccount?>(null)}
    var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf<String?>(null)}
    var notice by remember{mutableStateOf<String?>(null)}
    fun failure(failure:Throwable){error=LogSanitizer.sanitize(failure.message.orEmpty())}
    OmniCard(modifier=Modifier.testTag("google_accounts")) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text(strings.googleAccountsTitle,style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
            TextButton(enabled=supported && !busy && repository.readError()==null,onClick={label="";showAdd=true},modifier=Modifier.testTag("google_add")){Text(strings.googleAddAccount)}
        }
        Text(strings.googleAccountsHint,style=MaterialTheme.typography.bodySmall)
        notice?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)}
        if(!supported) Text(strings.googleUnsupported,color=MaterialTheme.colorScheme.error)
        repository.readError()?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        if(state.accounts.isEmpty() && repository.readError()==null)Text(strings.googleNoAccounts,Modifier.padding(vertical=12.dp),style=MaterialTheme.typography.bodyMedium)
        state.accounts.forEach{account->
            Row(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                Checkbox(checked=account.id in selected,enabled=!busy && supported,
                    onCheckedChange={checked->selected=if(checked)selected+account.id else selected-account.id},modifier=Modifier.testTag("google_select_${account.id}"))
                Column(Modifier.weight(1f)) {
                    Text(account.label,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
                    Text(when {account.pendingEpoch!=null->strings.googlePending;account.signedOut->strings.googleSignedOut;
                        account.bindings.isEmpty()->strings.googleNoCredentials;else->strings.googleCredentials.format(account.bindings.keys.joinToString())},
                        style=MaterialTheme.typography.bodySmall)
                    if(state.currentId==account.id)Text(strings.googleCurrentAccount,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                }
                Column {
                    if(state.currentId!=account.id)TextButton(enabled=!busy && account.pendingEpoch==null,onClick={busy=true;scope.launch{try{repository.activate(account.id).onFailure(::failure)}finally{busy=false}}}){Text(strings.googleUseAccount)}
                    TextButton(enabled=supported && !busy && account.pendingEpoch==null,onClick={busy=true;scope.launch{
                        try{repository.open(account.id).onSuccess{openAccount=it}.onFailure(::failure)}finally{busy=false}
                    }},modifier=Modifier.testTag("google_open_${account.id}")){Text(strings.googleOpenAccount)}
                }
            }
        }
        if(state.accounts.isNotEmpty()) {
            TextButton(enabled=!busy,onClick={selected=if(selected.size==state.accounts.size)emptySet() else state.accounts.map{it.id}.toSet()}){
                Text(if(selected.size==state.accounts.size)strings.googleClearSelection else strings.googleSelectAll)
            }
            OutlinedButton(shape=MaterialTheme.shapes.medium,enabled=supported && !busy && state.accounts.any{it.id==state.currentId && (!it.signedOut || it.pendingEpoch!=null)},
                onClick={confirm=setOf(state.currentId!!)},modifier=Modifier.fillMaxWidth().testTag("google_logout_current")){Text(strings.googleLogoutCurrent)}
            Spacer(Modifier.height(8.dp))
            Button(shape=MaterialTheme.shapes.medium,enabled=supported && !busy && selected.isNotEmpty(),
                onClick={confirm=selected},modifier=Modifier.fillMaxWidth().testTag("google_logout_selected")){Text(strings.googleLogoutSelected.format(selected.size))}
        }
        TextButton(onClick=onLegacy,enabled=!busy){Text(strings.googleLegacyAccount)}
    }
    if(showAdd)AlertDialog(onDismissRequest={if(!busy)showAdd=false},title={Text(strings.googleAddAccount)},
        text={OutlinedTextField(value=label,onValueChange={label=it.take(80)},singleLine=true,label={Text(strings.googleAccountName)},modifier=Modifier.testTag("google_account_label"))},
        confirmButton={TextButton(enabled=!busy && label.isNotBlank(),onClick={busy=true;scope.launch{
            try{repository.add(label).onSuccess{showAdd=false}.onFailure(::failure)}finally{busy=false}
        }},modifier=Modifier.testTag("google_add_confirm")){Text(strings.googleAddAccount)}},
        dismissButton={TextButton(enabled=!busy,onClick={showAdd=false}){Text(strings.cancel)}})
    if(confirm.isNotEmpty())AlertDialog(onDismissRequest={if(!busy)confirm=emptySet()},title={Text(strings.googleLogoutTitle)},
        text={Text(state.accounts.filter{it.id in confirm}.joinToString("\n"){it.label}+"\n\n"+strings.googleLogoutHint)},
        confirmButton={TextButton(enabled=!busy,onClick={val ids=confirm;busy=true;scope.launch{
            try{repository.logout(ids).onSuccess{selected=selected-ids;confirm=emptySet();notice=strings.googleCleanupFinished}.onFailure{confirm=emptySet();failure(it)}}finally{busy=false}
        }},modifier=Modifier.testTag("google_logout_confirm")){Text(strings.googleLogoutSelected.format(confirm.size))}},
        dismissButton={TextButton(enabled=!busy,onClick={confirm=emptySet()}){Text(strings.cancel)}})
    val shown=openAccount?.let{opened->state.accounts.firstOrNull{it.id==opened.id && it.epoch==opened.epoch && it.pendingEpoch==null && !it.signedOut}}
    if(shown!=null) {
        val profile=remember(shown.id,shown.epoch){runCatching{repository.browser(shown)}}
        profile.onSuccess{browser->PlatformLoginSheet(platform=platform,onDismissRequest={openAccount=null},onCookieCaptured={header->
            openAccount=null;busy=true;scope.launch{try{repository.saveCredential(shown.id,shown.epoch,platform,header)
                .onSuccess{notice=strings.googleSaved}.onFailure(::failure)}finally{busy=false}}
        },initialUrl="https://accounts.google.com/",googleProfile=browser,googleLabel=shown.label)}
        profile.exceptionOrNull()?.let{problem->LaunchedEffect(problem){openAccount=null;failure(problem)}}
    }
    if(error!=null)AlertDialog(onDismissRequest={error=null},text={Text(error.orEmpty())},confirmButton={TextButton(onClick={error=null}){Text(strings.sessionLoginDone)}})
}
