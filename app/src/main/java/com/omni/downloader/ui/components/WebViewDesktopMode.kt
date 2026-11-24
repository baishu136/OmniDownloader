package com.omni.downloader.ui.components

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** Change UA, UA Client Hints and the top-level viewport together, before loading. */
internal class WebViewDesktopMode(private val web: WebView) {
    private val originalAgent = web.settings.userAgentString
    private val originalMetadata = if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA))
        WebSettingsCompat.getUserAgentMetadata(web.settings) else null
    private var script: ScriptHandler? = null
    var desktop = false
        private set

    fun apply(enabled: Boolean) {
        desktop = enabled
        stripRequestedWithHeader(web)
        web.settings.apply {
            userAgentString = if (enabled) BrowserIdentity.desktopUserAgent(originalAgent) else BrowserIdentity.mobileUserAgent(originalAgent)
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = 100
        }
        originalMetadata?.let { original ->
            val builder = UserAgentMetadata.Builder(original)
            val brands = original.brandVersionList.filterNot { it.brand.contains("WebView", ignoreCase = true) }
            if (brands.isNotEmpty()) builder.setBrandVersionList(brands)
            val metadata = if (enabled) {
                builder
                    .setMobile(false).setPlatform("Windows").setPlatformVersion("10.0.0")
                    .setArchitecture("x86").setBitness(64).setModel("").setWow64(false).build()
            } else {
                builder.build()
            }
            WebSettingsCompat.setUserAgentMetadata(web.settings, metadata)
        }
        script?.remove(); script = null
        if (enabled && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            script = WebViewCompat.addDocumentStartJavaScript(web, VIEWPORT_SCRIPT, setOf("*"))
        }
    }

    fun onPageStarted() {
        if (desktop && !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) web.evaluateJavascript(VIEWPORT_SCRIPT, null)
    }
    fun onPageFinished() { if (desktop) web.evaluateJavascript(VIEWPORT_SCRIPT, null) }
    fun close() { script?.remove(); script = null }

    companion object {
        /** 禁用 Chromium 默认发送的 X-Requested-With: <packageName> 头，避免站点 CORS/反爬拦截。 */
        fun stripRequestedWithHeader(web: WebView) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(web.settings, emptySet())
            }
        }

        // Responsive login pages otherwise still use the phone's CSS width even with a desktop UA,
        // and anti-bot scripts check navigator.platform against the desktop User-Agent string.
        internal val VIEWPORT_SCRIPT = """
            (function(){
              if(window.top!==window || window.__omniDesktopViewport) return;
              window.__omniDesktopViewport=true;
              try{
                if(navigator.platform!=='Win32'){
                  Object.defineProperty(Navigator.prototype,'platform',{get:function(){return 'Win32';},configurable:true});
                }
                if(navigator.maxTouchPoints>0){
                  Object.defineProperty(Navigator.prototype,'maxTouchPoints',{get:function(){return 0;},configurable:true});
                }
              }catch(e){}
              var content='width=1280, user-scalable=yes';
              function apply(){
                if(!document.head) return;
                var tags=document.querySelectorAll('meta[name="viewport" i]');
                if(!tags.length){var tag=document.createElement('meta');tag.name='viewport';tag.content=content;document.head.appendChild(tag);}
                else tags.forEach(function(tag){if(tag.content!==content)tag.content=content;});
              }
              apply();
              new MutationObserver(apply).observe(document,{childList:true,subtree:true,attributes:true,attributeFilter:['content','name']});
              document.addEventListener('DOMContentLoaded',apply,{once:true});
            })();
        """.trimIndent()
    }
}
