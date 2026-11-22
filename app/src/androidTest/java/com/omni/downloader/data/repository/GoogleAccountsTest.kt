package com.omni.downloader.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.webkit.CookieManager
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/** Private synthetic accounts and credential stores, no live Google account. */
class GoogleAccountsTest {
    internal class Fixture:AutoCloseable {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(target.cacheDir,"google-accounts-${UUID.randomUUID()}").apply{mkdirs()}
        val context=object:ContextWrapper(target){
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=root
            override fun getCacheDir()=File(root,"cache").apply{mkdirs()}
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("${root.name}-$name",mode)
        }
        val sessions=SessionRepository::class.java.getDeclaredConstructor(Context::class.java).apply{isAccessible=true}.newInstance(context)
        val accounts=GoogleAccountRepository(context,sessions)
        override fun close(){
            for(name in listOf("omni_google_accounts_v1","omni_sessions_v1"))context.getSharedPreferences(name,Context.MODE_PRIVATE).edit().clear().commit()
            root.deleteRecursively()
        }
    }
    private suspend fun set(manager:CookieManager,value:String)=withContext(Dispatchers.Main){withTimeout(5000){
        suspendCancellableCoroutine<Unit>{ready->manager.setAcceptCookie(true);manager.setCookie("https://accounts.google.com/","SID=$value; Path=/; Secure; HttpOnly"){
            manager.flush();if(ready.isActive)ready.resume(Unit)
        }}
    }}
    @Test fun individualLogoutDoesNotRemoveAnotherAccountsCookiesOrNewerCredentials():Unit=runBlocking {
        withContext(Dispatchers.Main){assumeTrue(GoogleBrowserProfile.supported())}
        Fixture().use{fixture->
            val repository=fixture.accounts
            val a=repository.add("Account A").getOrThrow();val b=repository.add("Account B").getOrThrow()
            val first=repository.state.value.accounts.single{it.id==a};val second=repository.state.value.accounts.single{it.id==b}
            val browserA=withContext(Dispatchers.Main){repository.browser(first)}
            val browserB=withContext(Dispatchers.Main){repository.browser(second)}
            set(browserA.cookies,"account-a-fixture");set(browserB.cookies,"account-b-fixture")
            repository.saveCredential(a,0,SessionPlatforms.PIXIV,"PHPSESSID=account-a-fixture").getOrThrow()
            repository.saveCredential(b,0,SessionPlatforms.PIXIV,"PHPSESSID=account-b-fixture").getOrThrow()
            repository.logout(setOf(a)).getOrThrow()
            assertEquals("PHPSESSID=account-b-fixture",fixture.sessions.cookieHeader(SessionPlatforms.PIXIV))
            assertFalse(repository.state.value.accounts.single{it.id==b}.signedOut)
            withContext(Dispatchers.Main){assertTrue(browserB.cookies.getCookie("https://accounts.google.com/").orEmpty().contains("account-b-fixture"))}
            val reopened=repository.open(a).getOrThrow()
            withContext(Dispatchers.Main){assertNull(repository.browser(reopened).cookies.getCookie("https://accounts.google.com/"))}
        }
    }
    @Test fun multiLogoutClearsOnlySelectedBindingsAndPreservesRequestCooldowns():Unit=runBlocking {
        withContext(Dispatchers.Main){assumeTrue(GoogleBrowserProfile.supported())}
        Fixture().use{fixture->
            val repo=fixture.accounts;val ids=listOf("A","B","C").map{repo.add(it).getOrThrow()}
            repo.saveCredential(ids[0],0,SessionPlatforms.PIXIV,"PHPSESSID=a-fixture").getOrThrow()
            repo.saveCredential(ids[1],0,SessionPlatforms.YOUTUBE,"SID=b-fixture").getOrThrow()
            repo.saveCredential(ids[2],0,SessionPlatforms.X,"auth_token=c-fixture").getOrThrow()
            val x=File(fixture.root,"network_guard/x.json").apply{parentFile!!.mkdirs();writeText("{\"until\":9999999999999}")}
            val pixiv=File(fixture.root,"network_guard/pixiv.json").apply{writeText("{\"until\":9999999999999}")}
            repo.logout(setOf(ids[0],ids[2])).getOrThrow()
            assertNull(fixture.sessions.cookieHeader(SessionPlatforms.PIXIV));assertNull(fixture.sessions.cookieHeader(SessionPlatforms.X))
            assertEquals("SID=b-fixture",fixture.sessions.cookieHeader(SessionPlatforms.YOUTUBE))
            assertEquals("{\"until\":9999999999999}",x.readText());assertEquals(x.readText(),pixiv.readText())
            val reloaded=GoogleAccountRepository(fixture.context,fixture.sessions).state.value
            assertTrue(reloaded.accounts.single{it.id==ids[0]}.signedOut);assertTrue(reloaded.accounts.single{it.id==ids[2]}.signedOut)
            assertFalse(reloaded.accounts.single{it.id==ids[1]}.signedOut)
            assertTrue(reloaded.accounts.filter{it.signedOut}.all{it.bindings.isEmpty() && it.pendingEpoch==null})
        }
    }
    @Test fun staleCaptureCannotSaveAfterLogoutAndStatusChangesKeepCredentialOwnership():Unit=runBlocking {
        withContext(Dispatchers.Main){assumeTrue(GoogleBrowserProfile.supported())}
        Fixture().use{fixture->
            val repo=fixture.accounts;val id=repo.add("A").getOrThrow()
            repo.saveCredential(id,0,SessionPlatforms.PIXIV,"PHPSESSID=fixture").getOrThrow()
            val owner=fixture.sessions.credentialOwnerId(SessionPlatforms.PIXIV)
            fixture.sessions.markStatus(SessionPlatforms.PIXIV,SessionStatus.NEEDS_AUTH)
            assertEquals(owner,fixture.sessions.credentialOwnerId(SessionPlatforms.PIXIV))
            fixture.sessions.setCookieEnabled(SessionPlatforms.PIXIV,false)
            repo.logout(setOf(id)).getOrThrow()
            assertNull(fixture.sessions.credentialOwnerId(SessionPlatforms.PIXIV))
            assertTrue(repo.saveCredential(id,0,SessionPlatforms.PIXIV,"PHPSESSID=stale").isFailure)
            assertNull(fixture.sessions.credentialOwnerId(SessionPlatforms.PIXIV))
        }
    }
    @Test fun unrelatedReimportOfIdenticalCookieValuesIsNotDeletedAndLinkFailureRollsBack():Unit=runBlocking {
        withContext(Dispatchers.Main){assumeTrue(GoogleBrowserProfile.supported())}
        Fixture().use{fixture->
            val repo=fixture.accounts;val id=repo.add("A").getOrThrow();val sessions=fixture.sessions
            repo.saveCredential(id,0,SessionPlatforms.PIXIV,"PHPSESSID=same-fixture").getOrThrow()
            val first=sessions.credentialOwnerId(SessionPlatforms.PIXIV)
            sessions.importCookieHeader(SessionPlatforms.PIXIV,"PHPSESSID=same-fixture").getOrThrow()
            val second=sessions.credentialOwnerId(SessionPlatforms.PIXIV);assertNotEquals(first,second)
            repo.logout(setOf(id)).getOrThrow();assertEquals(second,sessions.credentialOwnerId(SessionPlatforms.PIXIV))
            val generation=sessions.requestGeneration(SessionPlatforms.PIXIV)
            assertTrue(sessions.importLinkedCookieHeader(SessionPlatforms.PIXIV,"PHPSESSID=failed-fixture"){error("fixture link failure")}.isFailure)
            assertEquals("PHPSESSID=same-fixture",sessions.cookieHeader(SessionPlatforms.PIXIV))
            assertEquals(second,sessions.credentialOwnerId(SessionPlatforms.PIXIV));assertEquals(generation,sessions.requestGeneration(SessionPlatforms.PIXIV))
        }
    }
    @Test fun profileNotesAndBindingsAreEncryptedAndDuplicateNamesAreRejected():Unit=runBlocking {
        Fixture().use{fixture->
            val id=fixture.accounts.add("private-fixture@example.invalid").getOrThrow()
            assertTrue(fixture.accounts.add("PRIVATE-FIXTURE@example.invalid").isFailure)
            fixture.accounts.saveCredential(id,0,SessionPlatforms.PIXIV,"PHPSESSID=fixture-secret").getOrThrow()
            val raw=fixture.context.getSharedPreferences("omni_google_accounts_v1",Context.MODE_PRIVATE).all.values.joinToString()
            assertFalse(raw.contains("private-fixture"));assertFalse(raw.contains("fixture-secret"))
            assertEquals("private-fixture@example.invalid",GoogleAccountRepository(fixture.context,fixture.sessions).state.value.accounts.single().label)
        }
    }
    @Test fun invalidDownloadCookieIsNotRestoredIntoRevalidationBrowser():Unit=runBlocking {
        withContext(Dispatchers.Main){assumeTrue(GoogleBrowserProfile.supported())}
        Fixture().use{fixture->
            fixture.accounts.add("reverify").getOrThrow()
            val profile=withContext(Dispatchers.Main){fixture.accounts.browser(fixture.accounts.state.value.accounts.single())}
            fixture.sessions.importCookieHeader(SessionPlatforms.PIXIV,"PHPSESSID=invalid-reverify-fixture").getOrThrow()
            fixture.sessions.markStatus(SessionPlatforms.PIXIV,SessionStatus.NEEDS_AUTH)
            var restored=false
            withContext(Dispatchers.Main){
                fixture.sessions.restoreBrowserCookies(SessionPlatforms.PIXIV,profile.cookies){restored=true}
                assertTrue(restored);assertNull(profile.cookies.getCookie("https://www.pixiv.net/"))
            }
        }
    }
}
