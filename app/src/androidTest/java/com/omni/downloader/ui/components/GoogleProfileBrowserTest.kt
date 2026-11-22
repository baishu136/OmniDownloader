package com.omni.downloader.ui.components

import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import com.omni.downloader.data.repository.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/** Local pages and synthetic cookies; no Google or platform requests. */
class GoogleProfileBrowserTest {
    @get:Rule val compose=createComposeRule()
    private class Page:AutoCloseable {
        val server=ServerSocket(0);val url="http://127.0.0.1:${server.localPort}/";val calls=AtomicInteger()
        private val thread=Thread{while(!server.isClosed)try{server.accept().use{socket->
            socket.soTimeout=2000;val input=socket.getInputStream().bufferedReader();while(!input.readLine().isNullOrEmpty()){}
            calls.incrementAndGet()
            val html="""<html><head><meta name="viewport" content="width=device-width"></head><body style="margin:0"><button style="position:fixed;left:0;top:0;width:200px;height:100px" onclick="window.open('/child')">Open login</button></body></html>""".toByteArray()
            socket.getOutputStream().apply{write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n".toByteArray());write(html);flush()}
        }}catch(_:Exception){}}.apply{isDaemon=true;start()}
        override fun close(){server.close();thread.join(3000)}
    }
    @Test fun oauthPopupUsesSamePrivateProfileAndLogoutClosesBothWindows() {
        compose.runOnIdle{assumeTrue(GoogleBrowserProfile.supported())}
        GoogleAccountsTest.Fixture().use{fixture->Page().use{page->
            val repo=fixture.accounts;val id=runBlocking{repo.add("popup").getOrThrow()}
            lateinit var profile:GoogleBrowserProfile
            compose.runOnIdle{profile=repo.browser(repo.state.value.accounts.single())}
            lateinit var controller:LoginBrowserController;val loaded=AtomicInteger()
            compose.setContent{AndroidView(modifier=Modifier.fillMaxSize(),factory={context->LoginBrowserController(context,SessionPlatforms.PIXIV,page.url,true,{}, {loaded.incrementAndGet()},{},googleProfile=profile).also{controller=it}.container})}
            try{
                compose.waitUntil(15000){loaded.get()>0}
                compose.runOnIdle{
                    assertEquals(profile.name,WebViewCompat.getProfile(controller.root).name)
                    val web=controller.root;val ratio=web.width/1280f;val now=SystemClock.uptimeMillis()
                    MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,80*ratio,40*ratio,0).also{web.dispatchTouchEvent(it);it.recycle()}
                    MotionEvent.obtain(now,now+80,MotionEvent.ACTION_UP,80*ratio,40*ratio,0).also{web.dispatchTouchEvent(it);it.recycle()}
                }
                compose.waitUntil(12000){loaded.get()>=2}
                compose.runOnIdle{
                    assertEquals(2,controller.container.childCount)
                    assertEquals(profile.name,WebViewCompat.getProfile(controller.container.getChildAt(1) as WebView).name)
                }
                runBlocking{repo.logout(setOf(id)).getOrThrow()}
                compose.runOnIdle{assertEquals(0,controller.container.childCount)}
            }finally{compose.runOnIdle{controller.close()}}
        }}
    }
    @Test fun privateCookieCaptureRequiresSaveAndReadsOnlySelectedProfile() {
        compose.runOnIdle{assumeTrue(GoogleBrowserProfile.supported())}
        GoogleAccountsTest.Fixture().use{fixture->Page().use{page->
            val repo=fixture.accounts;runBlocking{repo.add("capture").getOrThrow()}
            lateinit var profile:GoogleBrowserProfile;var ready=false;var header=""
            compose.runOnIdle{profile=repo.browser(repo.state.value.accounts.single());profile.cookies.setCookie("https://www.youtube.com/","SID=private-profile-fixture; Path=/; Secure; HttpOnly"){ready=true}}
            compose.waitUntil(5000){ready}
            compose.setContent{MaterialTheme{PlatformLoginSheet(SessionPlatforms.YOUTUBE,{}, {header=it},initialUrl=page.url,googleProfile=profile,googleLabel="capture")}}
            compose.waitUntil(10000){page.calls.get()>0};Thread.sleep(1200);compose.waitForIdle()
            assertEquals("",header)
            compose.onNodeWithTag("session_login_done").performClick()
            assertTrue(header,header.contains("SID=private-profile-fixture"))
            assertNull(fixture.sessions.cookieHeader(SessionPlatforms.YOUTUBE)) // Import is an explicit caller operation.
        }}
    }
}
