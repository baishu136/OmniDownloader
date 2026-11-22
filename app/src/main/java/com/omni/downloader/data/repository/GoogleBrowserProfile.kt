package com.omni.downloader.data.repository

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import kotlin.coroutines.resume

/** Opt-in account containers only; the existing shared login flow stays intact. */
internal class GoogleBrowserProfile private constructor(val name:String,val cookies:CookieManager) {
    fun bind(view:WebView){WebViewCompat.setProfile(view,name)}
    fun register(close:()->Unit):()->Unit {
        closers.getOrPut(name){linkedSetOf()}.add(close)
        return {closers[name]?.let{it.remove(close);if(it.isEmpty())closers.remove(name)}}
    }
    companion object {
        private val closers=mutableMapOf<String,MutableSet<()->Unit>>()
        fun supported()=WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        private fun prefix(context:Context,account:GoogleLoginAccount):String {
            val hash=MessageDigest.getInstance("SHA-256").digest(context.filesDir.path.toByteArray()).take(8).joinToString(""){"%02x".format(it)}
            return "omni_google_${hash}_${account.id}_"
        }
        private fun name(context:Context,account:GoogleLoginAccount,epoch:Int)=prefix(context,account)+epoch
        fun open(context:Context,account:GoogleLoginAccount):GoogleBrowserProfile {
            check(supported()){ "当前 WebView 不支持独立登录档案，请更新 Android System WebView" }
            check(account.pendingEpoch==null)
            val current=name(context,account,account.epoch);val store=ProfileStore.getInstance()
            store.allProfileNames.filter{it.startsWith(prefix(context,account)) && it!=current}.forEach{
                try{store.deleteProfile(it)}catch(_:IllegalStateException){}
            }
            return GoogleBrowserProfile(current,store.getOrCreateProfile(current).cookieManager)
        }
        suspend fun clear(context:Context,account:GoogleLoginAccount) {
            check(supported()){ "请更新 Android System WebView 后重试清理独立登录档案" }
            val target=name(context,account,requireNotNull(account.pendingEpoch))
            closers[target]?.toList()?.forEach{it()}
            val store=ProfileStore.getInstance()
            if(target !in store.allProfileNames)return
            try{store.deleteProfile(target)}catch(_:IllegalStateException){
                val profile=store.getOrCreateProfile(target)
                withTimeout(10000){suspendCancellableCoroutine<Unit>{ready->
                    profile.cookieManager.removeAllCookies{profile.cookieManager.flush();if(ready.isActive)ready.resume(Unit)}
                }}
                profile.webStorage.deleteAllData()
            }
        }
    }
}
