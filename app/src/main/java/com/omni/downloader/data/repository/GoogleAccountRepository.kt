package com.omni.downloader.data.repository

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class GoogleLoginAccount(val id:String,val label:String,val epoch:Int=0,val signedOut:Boolean=false,
    val pendingEpoch:Int?=null,val bindings:Map<String,String> = emptyMap())
internal data class GoogleAccountsState(val accounts:List<GoogleLoginAccount> = emptyList(),val currentId:String?=null)

/** Named, independently stored browser sessions. Labels are user notes, not
 * verified Google identities. Only matching owned download credentials clear. */
internal class GoogleAccountRepository(context:Context,private val sessions:SessionRepository=SessionRepository.getInstance(context)) {
    private val context=context.applicationContext
    private val prefs=this.context.getSharedPreferences("omni_google_accounts_v1",Context.MODE_PRIVATE)
    private val mutex=Mutex()
    private val _state=MutableStateFlow(GoogleAccountsState())
    val state=_state.asStateFlow()
    private var loadError:Throwable?=null
    init {
        runCatching{prefs.getString("index",null)?.let{encrypted->
            val raw=SessionCrypto.decrypt(encrypted) ?: error("Google 登录档案无法解密")
            require(raw.length<=65536)
            val root=JSONObject(raw);val rows=root.getJSONArray("accounts")
            require(rows.length()<=20)
            val accounts=(0 until rows.length()).map{index->val item=rows.getJSONObject(index)
                val id=item.getString("id");require(runCatching{UUID.fromString(id)}.isSuccess)
                val bindings=item.optJSONObject("bindings") ?: JSONObject()
                GoogleLoginAccount(id,item.getString("label"),item.optInt("epoch"),item.optBoolean("signedOut"),
                    if(item.isNull("pendingEpoch"))null else item.getInt("pendingEpoch"),
                    bindings.keys().asSequence().associateWith{bindings.getString(it)})
            }
            _state.value=GoogleAccountsState(accounts,root.optString("currentId").takeIf{id->accounts.any{it.id==id}})
        }}.onFailure{loadError=it}
    }
    private fun persist(value:GoogleAccountsState) {
        check(loadError==null){"Google 登录档案读取失败，请勿覆盖现有数据"}
        val rows=JSONArray().apply{value.accounts.forEach{account->put(JSONObject().put("id",account.id).put("label",account.label)
            .put("epoch",account.epoch).put("signedOut",account.signedOut).put("pendingEpoch",account.pendingEpoch ?: JSONObject.NULL)
            .put("bindings",JSONObject(account.bindings)))}}
        val encoded=SessionCrypto.encrypt(JSONObject().put("accounts",rows).put("currentId",value.currentId ?: JSONObject.NULL).toString())
        check(prefs.edit().putString("index",encoded).commit()){ "Google 档案保存失败" }
        _state.value=value
    }
    suspend fun add(label:String):Result<String> = withContext(Dispatchers.IO) {runCatching{mutex.withLock {
        val name=label.trim();require(name.isNotBlank() && name.length<=80){"请填写不超过 80 字的档案名称"}
        val state=_state.value;require(state.accounts.size<20){"最多保存 20 个 Google 登录档案"}
        require(state.accounts.none{it.label.equals(name,true)}){"档案名称已存在"}
        val account=GoogleLoginAccount(UUID.randomUUID().toString(),name)
        persist(state.copy(accounts=state.accounts+account,currentId=account.id));account.id
    }}}
    suspend fun activate(id:String):Result<Unit> = withContext(Dispatchers.IO){runCatching{mutex.withLock{
        require(_state.value.accounts.any{it.id==id && it.pendingEpoch==null});persist(_state.value.copy(currentId=id))
    }}}
    suspend fun open(id:String):Result<GoogleLoginAccount> = withContext(Dispatchers.IO){runCatching{mutex.withLock{
        val old=_state.value.accounts.single{it.id==id};check(old.pendingEpoch==null){"档案正在清理，请稍后重试"}
        val account=old.copy(signedOut=false)
        persist(_state.value.copy(accounts=_state.value.accounts.map{if(it.id==id)account else it},currentId=id));account
    }}}
    suspend fun saveCredential(id:String,epoch:Int,platform:String,header:String):Result<Unit> = withContext(Dispatchers.IO){runCatching{mutex.withLock{
        val account=_state.value.accounts.singleOrNull{it.id==id} ?: error("档案已移除")
        check(account.epoch==epoch && account.pendingEpoch==null && !account.signedOut){"档案已退出，请重新登录后保存"}
        sessions.importLinkedCookieHeader(platform,header){owner->
            val updated=account.copy(bindings=account.bindings+(platform to owner))
            persist(_state.value.copy(accounts=_state.value.accounts.map{if(it.id==id)updated else it}))
        }.getOrThrow()
        Unit
    }}}
    suspend fun logout(ids:Set<String>):Result<Unit> = withContext(NonCancellable+Dispatchers.IO){runCatching{mutex.withLock{
        require(ids.isNotEmpty() && ids.all{id->_state.value.accounts.any{it.id==id}})
        // Persist the new epoch first: a crash or late callback cannot revive it.
        persist(_state.value.copy(accounts=_state.value.accounts.map{account->if(account.id !in ids || account.pendingEpoch!=null)account else
            account.copy(epoch=account.epoch+1,signedOut=true,pendingEpoch=account.epoch)}))
        for(id in ids) {
            val account=_state.value.accounts.single{it.id==id}
            account.bindings.forEach{(platform,owner)->sessions.clearOwnedSession(platform,owner)}
            withContext(Dispatchers.Main.immediate){GoogleBrowserProfile.clear(context,account)}
            val completed=account.copy(bindings=emptyMap(),pendingEpoch=null)
            persist(_state.value.copy(accounts=_state.value.accounts.map{if(it.id==id)completed else it}))
        }
    }}}
    fun readError():String?=loadError?.let{"Google 登录档案读取失败，请重新检查本机数据"}
    fun browser(account:GoogleLoginAccount):GoogleBrowserProfile {
        check(_state.value.accounts.any{it.id==account.id && it.epoch==account.epoch && it.pendingEpoch==null && !it.signedOut}){"档案已退出，请重新打开"}
        return GoogleBrowserProfile.open(context,account)
    }
    companion object {
        @Volatile private var instance:GoogleAccountRepository?=null
        fun getInstance(context:Context)=instance ?: synchronized(this){instance ?: GoogleAccountRepository(context).also{instance=it}}
    }
}
