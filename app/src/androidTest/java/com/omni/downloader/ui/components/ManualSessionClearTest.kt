package com.omni.downloader.ui.components

import android.webkit.CookieManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.downloader.data.repository.*
import com.omni.downloader.ui.screens.SettingsScreen
import com.omni.downloader.ui.viewmodel.MainViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Dedicated QA installation. Synthetic cookies only; no real Google sign-in. */
class ManualSessionClearTest {
    @get:Rule val compose=createComposeRule()
    private fun setCookie(url:String,value:String) {
        val ready=AtomicBoolean()
        compose.runOnIdle {CookieManager.getInstance().apply {setAcceptCookie(true);setCookie(url,value){flush();ready.set(true)}}}
        compose.waitUntil(10000){ready.get()}
    }
    @Test fun revalidationDoesNotAutoSaveOldCookieAndExplicitSaveUsesUpdatedCookie() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val sessions=SessionRepository.getInstance(context)
        assumeTrue(sessions.info(SessionPlatforms.YOUTUBE)!!.status==SessionStatus.MISSING)
        runBlocking{sessions.importCookieHeader(SessionPlatforms.YOUTUBE,"SID=old-reverify-fixture").getOrThrow()}
        sessions.markStatus(SessionPlatforms.YOUTUBE,SessionStatus.NEEDS_AUTH)
        setCookie("https://www.youtube.com/","SID=old-reverify-fixture; Path=/; Secure; HttpOnly")
        val server=ServerSocket(0);val requests=AtomicInteger();val captured=AtomicInteger();var header=""
        val thread=Thread{while(!server.isClosed)try{server.accept().use{socket->
            socket.soTimeout=2000;val reader=socket.getInputStream().bufferedReader();while(!reader.readLine().isNullOrEmpty()){}
            requests.incrementAndGet();val html="<html>Reverify local fixture</html>".toByteArray()
            socket.getOutputStream().apply{write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n".toByteArray());write(html);flush()}
        }}catch(_:Exception){}}.apply{isDaemon=true;start()}
        try{
            compose.setContent{MaterialTheme{PlatformLoginSheet(SessionPlatforms.YOUTUBE,{}, {header=it;captured.incrementAndGet()},initialUrl="http://127.0.0.1:${server.localPort}/reverify")}}
            compose.waitUntil(10000){requests.get()>0};Thread.sleep(1500);compose.waitForIdle()
            assertEquals("Old SID must not close revalidation",0,captured.get())
            assertEquals(SessionStatus.NEEDS_AUTH,sessions.info(SessionPlatforms.YOUTUBE)!!.status)
            setCookie("https://www.youtube.com/","SID=new-reverify-fixture; Path=/; Secure; HttpOnly")
            compose.onNodeWithTag("session_login_done").performClick()
            assertEquals(1,captured.get());assertTrue(header.contains("SID=new-reverify-fixture"))
            runBlocking{sessions.importCookieHeader(SessionPlatforms.YOUTUBE,header).getOrThrow()}
            assertEquals(SessionStatus.IMPORTED,sessions.info(SessionPlatforms.YOUTUBE)!!.status)
        }finally{
            server.close();thread.join(3000);sessions.clear(SessionPlatforms.YOUTUBE)
            setCookie("https://www.youtube.com/","SID=; Path=/; Secure; HttpOnly; Max-Age=0")
        }
    }
    @Test fun clearingCredentialsShowsManualInstructionsAndPreservesBrowserCookies() {
        val instrument=InstrumentationRegistry.getInstrumentation();val sessions=SessionRepository.getInstance(instrument.targetContext)
        assumeTrue(sessions.info(SessionPlatforms.PIXIV)!!.status==SessionStatus.MISSING)
        runBlocking {sessions.importCookieHeader(SessionPlatforms.PIXIV,"PHPSESSID=manual-clear-fixture").getOrThrow()}
        setCookie("https://accounts.google.com/","OMNI_MANUAL_QA=fixture-only; Path=/; Secure; HttpOnly")
        val store=ViewModelStore();lateinit var model:MainViewModel
        instrument.runOnMainSync {model=MainViewModel(instrument.targetContext.applicationContext as android.app.Application);store.put("manual-clear",model);model.requestedSessionPlatform.value=SessionPlatforms.PIXIV}
        try {
            compose.setContent {MaterialTheme {SettingsScreen(model)}}
            compose.onNodeWithTag("settings_page_ACCOUNTS").performScrollToNode(hasTestTag("session_clear"))
            compose.onNodeWithTag("session_clear").performClick()
            compose.onNodeWithTag("session_clear_hint").assertExists()
            compose.onNodeWithTag("session_manage_google").assertIsDisplayed()
            assertNull(sessions.cookieHeader(SessionPlatforms.PIXIV))
            compose.runOnIdle {assertTrue(CookieManager.getInstance().getCookie("https://accounts.google.com/").orEmpty().contains("OMNI_MANUAL_QA=fixture-only"))}
            compose.onNodeWithTag("session_clear_done").performClick()
            compose.onNodeWithTag("session_clear_hint").assertDoesNotExist()
            compose.onNodeWithTag("session_clear").assertIsEnabled()
        } finally {
            setCookie("https://accounts.google.com/","OMNI_MANUAL_QA=; Path=/; Secure; HttpOnly; Max-Age=0")
            instrument.runOnMainSync {store.clear()}
        }
    }
    @Test fun googleManagementDoesNotAutoCaptureOrReimportCookies() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val sessions=SessionRepository.getInstance(context)
        assumeTrue(sessions.info(SessionPlatforms.YOUTUBE)!!.status==SessionStatus.MISSING)
        var existing=""
        compose.runOnIdle{existing=CookieManager.getInstance().getCookie("https://www.youtube.com/").orEmpty()}
        assumeTrue(existing.isBlank())
        setCookie("https://www.youtube.com/","SID=manual-management-fixture; Path=/; Secure; HttpOnly")
        val server=ServerSocket(0);val requests=AtomicInteger();val captured=AtomicInteger();val visible=mutableStateOf(true)
        val thread=Thread {while(!server.isClosed)try{server.accept().use{socket->
            val reader=socket.getInputStream().bufferedReader();while(!reader.readLine().isNullOrEmpty()){}
            requests.incrementAndGet();val html="<html><body>Manual account page fixture</body></html>".toByteArray()
            socket.getOutputStream().apply{write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n".toByteArray());write(html);flush()}
        }}catch(_:Exception){} }.apply{isDaemon=true;start()}
        try {
            compose.setContent {MaterialTheme {if(visible.value)PlatformLoginSheet(SessionPlatforms.YOUTUBE,{visible.value=false},{captured.incrementAndGet()},
                initialUrl="http://127.0.0.1:${server.localPort}/manual",manageOnly=true)}}
            compose.waitUntil(10000){requests.get()>0}
            // Normal YouTube login polls every second; management must not capture SID.
            Thread.sleep(1500);compose.waitForIdle()
            assertEquals(0,captured.get());assertTrue(visible.value)
            compose.onNodeWithTag("session_login_done").performClick()
            assertFalse(visible.value);assertEquals(0,captured.get())
            assertEquals(SessionStatus.MISSING,sessions.info(SessionPlatforms.YOUTUBE)!!.status)
            compose.runOnIdle {assertTrue(CookieManager.getInstance().getCookie("https://www.youtube.com/").orEmpty().contains("SID=manual-management-fixture"))}
        } finally {
            compose.runOnIdle {visible.value=false};compose.waitForIdle()
            server.close();thread.join(2000)
            setCookie("https://www.youtube.com/","SID=; Path=/; Secure; HttpOnly; Max-Age=0")
        }
    }
}
