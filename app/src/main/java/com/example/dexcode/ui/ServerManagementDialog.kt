package com.example.dexcode.ui

import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.dexcode.R
import com.example.dexcode.ssh.ServerManagement
import com.example.dexcode.ssh.SshSessionManager
import com.example.dexcode.util.dp
import java.util.concurrent.ExecutorService

/** Read-only inspection first. Install/restart require a concrete confirmation on the device. */
class ServerManagementDialog(private val activity:AppCompatActivity,private val manager:SshSessionManager,
    private val executor:ExecutorService,private val reconnect:()->Unit) {
    private val handler=Handler(Looper.getMainLooper())
    private val content=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(activity.dp(20),activity.dp(8),activity.dp(20),activity.dp(12))}
    private val details=TextView(activity).apply {textSize=13f;setTextIsSelectable(true)}
    private val state=TextView(activity).apply {textSize=13f;setPadding(0,activity.dp(12),0,activity.dp(8))}
    private val target=EditText(activity).apply {
        hint=activity.getString(R.string.server_target_version);setSingleLine(true)
        inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        filters=arrayOf(android.text.InputFilter.LengthFilter(32))
    }
    private val admin=CheckBox(activity).apply {text=activity.getString(R.string.server_sudo);isChecked=false}
    private fun button(label:Int)=Button(activity).apply {text=activity.getString(label);isAllCaps=false}
    private val refresh=button(R.string.server_inspect)
    private val latest=button(R.string.server_check_latest)
    private val update=button(R.string.server_change_version)
    private val previous=button(R.string.server_previous_version)
    private val restart=button(R.string.server_restart)
    private val connect=button(R.string.server_reconnect)
    private lateinit var dialog:AlertDialog
    private var snapshot:ServerManagement.Snapshot?=null
    private var busy=false
    private var polling=false
    private var epoch=0
    private var lastTerminalJob:String?=null
    private val children=mutableSetOf<AlertDialog>()
    private fun showChild(child:AlertDialog) {
        children.add(child)
        child.setOnDismissListener{children.remove(child)}
        child.show()
    }

    fun show() {
        content.addView(details);content.addView(state);content.addView(target);content.addView(admin)
        for(view in listOf(refresh,latest,update,previous,restart,connect))content.addView(view,LinearLayout.LayoutParams(-1,-2))
        dialog=AlertDialog.Builder(activity).setTitle(R.string.server_management)
            .setView(ScrollView(activity).apply {addView(content)})
            .setNegativeButton(R.string.close,null).create()
        refresh.setOnClickListener{inspect()}
        latest.setOnClickListener{checkLatest()}
        update.setOnClickListener{confirmUpdate()}
        previous.setOnClickListener{snapshot?.previousVersion?.let {target.setText(it);confirmUpdate()}}
        restart.setOnClickListener{confirmRestart()}
        connect.setOnClickListener{dialog.dismiss();reconnect()}
        admin.setOnCheckedChangeListener{_,_->renderEnabled()}
        val observer=LifecycleEventObserver { _, event ->
            if(event==Lifecycle.Event.ON_STOP)dialog.dismiss()
        }
        activity.lifecycle.addObserver(observer)
        dialog.setOnDismissListener{
            epoch++;handler.removeCallbacksAndMessages(null);activity.lifecycle.removeObserver(observer)
            children.toList().forEach{it.dismiss()}
        }
        dialog.show()
        inspect()
    }
    private fun text(id:Int,vararg values:Any)=activity.getString(id,*values)
    private fun valid(e:Int)=::dialog.isInitialized && dialog.isShowing && !activity.isFinishing && epoch==e
    private fun renderEnabled() {
        val info=snapshot
        refresh.isEnabled=!busy && !polling
        latest.isEnabled=!busy && !polling
        target.isEnabled=!busy && !polling
        admin.isEnabled=!busy && !polling
        update.isEnabled=!busy && !polling && info?.canUpdate==true && (!info.needsAdmin || admin.isChecked)
        previous.isEnabled=update.isEnabled && info?.previousVersion!=null
        restart.isEnabled=!busy && !polling && info?.canRestart==true
        connect.isEnabled=!busy && !polling
    }
    private fun inspect() {
        if(busy || polling)return
        busy=true;state.text=text(R.string.server_inspecting);renderEnabled()
        val current=++epoch
        executor.execute {
            val result=runCatching{manager.inspectServer()}
            handler.post {
                if(!valid(current))return@post
                busy=false
                result.onSuccess {info->
                    snapshot=info
                    details.text=text(R.string.server_details,info.installedVersion.ifBlank{text(R.string.server_unknown)},
                        info.runningVersion.ifBlank{text(R.string.server_unknown)},info.method,info.launcher,info.realPath,
                        info.fields["runningExecutable"].orEmpty().ifBlank{"—"})
                    admin.visibility=if(info.needsAdmin)android.view.View.VISIBLE else android.view.View.GONE
                    state.text=if(info.restartRequired)text(R.string.server_restart_required) else if(info.canUpdate)text(R.string.server_ready) else text(R.string.server_readonly,info.fields["reason"].orEmpty())
                    if(target.text.isBlank())target.setText(info.installedVersion)
                    renderEnabled()
                    info.lastJob?.let {if(it!=lastTerminalJob)poll(it)}
                }.onFailure {snapshot=null;state.text=text(R.string.server_error,it.message.orEmpty());renderEnabled()}
            }
        }
    }
    private fun checkLatest() {
        if(busy || polling)return
        busy=true;state.text=text(R.string.server_checking_latest);renderEnabled()
        val current=epoch
        executor.execute {
            val result=runCatching {ServerManagement.latestVersion()}
            handler.post {
                if(!valid(current))return@post
                busy=false
                result.onSuccess{target.setText(it);state.text=text(R.string.server_latest,it)}
                    .onFailure{state.text=text(R.string.server_error,it.message.orEmpty())}
                renderEnabled()
            }
        }
    }
    private fun confirmUpdate() {
        val info=snapshot ?: return
        val version=ServerManagement.version(target.text.toString())
        if(version==null){target.error=text(R.string.server_invalid_version);return}
        if(!info.canUpdate || (info.needsAdmin && !admin.isChecked))return
        val sudo=admin.isChecked
        val confirmation=AlertDialog.Builder(activity).setTitle(R.string.server_change_version)
            .setMessage(text(R.string.server_update_confirmation,info.installedVersion,version,info.launcher)+
                if(info.needsAdmin)"\n\n"+text(R.string.server_sudo) else "")
            .setNegativeButton(R.string.cancel,null)
            .setPositiveButton(R.string.server_execute){_,_->
                if(!dialog.isShowing || busy || polling)return@setPositiveButton
                busy=true;state.text=text(R.string.server_starting_job);renderEnabled()
                val current=epoch
                executor.execute {
                    val result=runCatching{manager.startServerUpdate(info,version,sudo)}
                    handler.post {
                        if(!valid(current))return@post
                        busy=false
                        result.onSuccess{poll(it)}.onFailure{state.text=text(R.string.server_error,it.message.orEmpty());renderEnabled()}
                    }
                }
            }.create()
        showChild(confirmation)
    }
    private fun poll(job:String) {
        polling=true;state.text=text(R.string.server_job_running);renderEnabled()
        val current=epoch
        executor.execute {
            val result=runCatching{manager.serverUpdateStatus(job)}
            handler.post {
                if(!valid(current))return@post
                result.onSuccess{status->
                    when(status["jobState"]) {
                        "queued","running" -> {handler.postDelayed({if(valid(current))poll(job)},1500)}
                        else -> {
                            polling=false;lastTerminalJob=job
                            val complete=status["jobState"]=="completed"
                            val message=if(complete)text(R.string.server_job_complete) else text(R.string.server_job_failed,status["jobError"].orEmpty().ifBlank{status["jobLog"].orEmpty()})
                            state.text=message
                            renderEnabled()
                            // Refresh the executable after success/failure; never reuse a stale update plan.
                            snapshot=null
                            inspect()
                            showChild(AlertDialog.Builder(activity).setTitle(if(complete)R.string.server_management else R.string.server_job_failure_title)
                                .setMessage(message).setPositiveButton(android.R.string.ok,null).create())
                        }
                    }
                }.onFailure{polling=false;snapshot=null;state.text=text(R.string.server_error,it.message.orEmpty())+"\n"+text(R.string.server_job_recover);renderEnabled()}
            }
        }
    }
    private fun confirmRestart() {
        val info=snapshot?.takeIf{it.canRestart} ?: return
        val confirmation=AlertDialog.Builder(activity).setTitle(R.string.server_restart)
            .setMessage(text(R.string.server_restart_confirmation,info.fields["pid"].orEmpty()))
            .setNegativeButton(R.string.cancel,null)
            .setPositiveButton(R.string.server_restart){_,_->
                if(!dialog.isShowing || busy || polling)return@setPositiveButton
                busy=true;state.text=text(R.string.server_restarting);renderEnabled()
                val current=epoch
                executor.execute {
                    val result=runCatching{manager.stopManagedServer(info)}
                    handler.post {
                        if(!valid(current))return@post
                        busy=false
                        result.onSuccess{dialog.dismiss();reconnect()}
                            .onFailure{state.text=text(R.string.server_error,it.message.orEmpty());renderEnabled()}
                    }
                }
            }.create()
        showChild(confirmation)
    }
}
