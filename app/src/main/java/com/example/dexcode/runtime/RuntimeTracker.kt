package com.example.dexcode.runtime

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.example.dexcode.CodeActivity
import com.example.dexcode.PortActivity
import com.example.dexcode.SshCodeActivity
import com.example.dexcode.data.ConnectionStore

object RuntimeTracker : Application.ActivityLifecycleCallbacks {
    private val ids = java.util.WeakHashMap<Activity, String>()
    fun id(activity: Activity): String = synchronized(ids) {
        ids.getOrPut(activity) { java.util.UUID.randomUUID().toString() }
    }
    fun phase(activity: Activity, value: String) = RuntimeState.store.updateWindow(id(activity), phase = value)

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        val type = when (activity) {
            is SshCodeActivity -> "SSH workspace"
            is CodeActivity -> "Web editor"
            is PortActivity -> "Web / port window"
            else -> return
        }
        val connectionId = activity.intent.getStringExtra(CodeActivity.EXTRA_CONNECTION_ID)
        val connection = connectionId?.let { ConnectionStore(activity).get(it) }
        val title = connection?.name ?: activity.intent.getStringExtra(PortActivity.EXTRA_TITLE) ?: type
        RuntimeState.store.putWindow(RuntimeWindow(id(activity), activity.taskId, title, type, connectionId))
    }
    override fun onActivityStarted(activity: Activity) = RuntimeState.store.updateWindow(id(activity), visibility = "Visible")
    override fun onActivityResumed(activity: Activity) = RuntimeState.store.updateWindow(id(activity), visibility = "Active")
    override fun onActivityPaused(activity: Activity) = RuntimeState.store.updateWindow(id(activity), visibility = "Visible")
    override fun onActivityStopped(activity: Activity) = RuntimeState.store.updateWindow(id(activity), visibility = "Background")
    override fun onActivityDestroyed(activity: Activity) {
        RuntimeState.store.removeWindow(id(activity))
        synchronized(ids) { ids.remove(activity) }
    }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
