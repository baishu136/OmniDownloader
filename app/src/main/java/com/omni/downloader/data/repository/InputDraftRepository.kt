package com.omni.downloader.data.repository

import android.content.Context
import com.omni.downloader.engine.BatchOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable data class InputDraft(
    val home: String = "", val images: String = "",
    val batchText: String = "", val batchEntry: String = "", val batchLinks: List<String> = emptyList(),
    val batchManual: Boolean = false, val batchOptions: BatchOptions = BatchOptions()
)

/** Hydration replays edits made before loading; one IO writer always saves the latest snapshot. */
class InputDraftRepository internal constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("omni_input_drafts", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val loaded = CompletableDeferred<Unit>()
    private var startupEdits: MutableList<(InputDraft) -> InputDraft>? = mutableListOf()
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private var revision = 0L
    private val savedRevision = MutableStateFlow(-1L)
    private val _draft = MutableStateFlow(InputDraft())
    val draft = _draft.asStateFlow()
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    init {
        scope.launch {
            val restored = try {
                prefs.getString("draft", null)?.let { json.decodeFromString<InputDraft>(SessionCrypto.decrypt(it) ?: error("无法解密草稿")) } ?: InputDraft()
            } catch (_: Exception) { _error.value = "输入草稿无法读取，请重新填写"; InputDraft() }
            synchronized(lock) {
                var merged = restored
                startupEdits?.forEach { merged = it(merged) }
                startupEdits = null; _draft.value = merged
            }
            _ready.value = true; loaded.complete(Unit)
        }
        scope.launch {
            loaded.await()
            for (ignored in writes) try {
                val (snapshot, version) = synchronized(lock) { _draft.value to revision }
                check(prefs.edit().putString("draft", SessionCrypto.encrypt(json.encodeToString(snapshot))).commit())
                savedRevision.value = version
                _error.value = null
            } catch (_: Exception) { _error.value = "草稿保存失败，请检查设备可用空间" }
        }
    }
    suspend fun awaitLoaded() = loaded.await()
    internal suspend fun awaitSaved() {
        loaded.await()
        val target = synchronized(lock) { revision }
        writes.trySend(Unit)
        savedRevision.first { it >= target }
    }
    fun update(edit: (InputDraft) -> InputDraft) {
        synchronized(lock) {
            val next = edit(_draft.value)
            if (next == _draft.value && startupEdits == null) return
            _draft.value = next; startupEdits?.add(edit); revision++
        }
        writes.trySend(Unit)
    }
    companion object {
        @Volatile private var instance: InputDraftRepository? = null
        fun getInstance(context: Context) = instance ?: synchronized(this) {
            instance ?: InputDraftRepository(context).also { instance = it }
        }
    }
}
