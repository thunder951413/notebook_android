package io.github.notebook.android.reminder

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.notebook.android.MainActivity
import java.util.Calendar
import io.github.notebook.android.NotebookApp
import io.github.notebook.android.data.NoteEntity
import kotlinx.coroutines.*
import java.util.UUID

object Reminders {
    const val CHANNEL="notebook_reminders"
    private const val REGISTRY="scheduled_reminder_ids"
    fun createChannel(context:Context){(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL,"笔记与待办提醒",NotificationManager.IMPORTANCE_HIGH))}
    @Synchronized fun schedule(context:Context,id:String,title:String,at:Long?,recurrence:String){
        cancelAlarm(context,id);if(at==null){cancel(context,id);return};val now=System.currentTimeMillis();val triggerAt=if(at>now)at else if(recurrence=="none"){cancel(context,id);return}else nextOccurrence(at,recurrence,now)
        val token=UUID.randomUUID().toString();preferences(context).edit().putString(tokenKey(id),token).commit()
        val intent=Intent(context,ReminderReceiver::class.java).putExtra("id",id).putExtra("title",title).putExtra("recurrence",recurrence).putExtra("scheduledAt",triggerAt).putExtra("token",token)
        val pi=PendingIntent.getBroadcast(context,id.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE);val alarm=context.getSystemService(Context.ALARM_SERVICE) as AlarmManager;val exactAllowed=android.os.Build.VERSION.SDK_INT<31||alarm.canScheduleExactAlarms();if(exactAllowed)alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,triggerAt,pi)else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,triggerAt,pi);updateRegistry(context,id,true)
    }
    @Synchronized fun cancel(context:Context,id:String){cancelAlarm(context,id);NotificationManagerCompat.from(context).cancel(id.hashCode());preferences(context).edit().remove(tokenKey(id)).commit();updateRegistry(context,id,false)}
    fun reconcile(context:Context,notes:List<NoteEntity>){val active=notes.filter{it.deletedAt==null&&it.completedAt==null&&it.reminderAt!=null}.associateBy{it.id};val existing=registry(context);(existing-active.keys).forEach{cancel(context,it)};active.values.forEach{schedule(context,it.id,it.title,it.reminderAt,it.recurrence)}}
    fun nextOccurrence(scheduledAt:Long,recurrence:String,now:Long=System.currentTimeMillis()):Long{
        val origin=Calendar.getInstance().apply{timeInMillis=scheduledAt}
        if(recurrence=="monthly"){val day=origin.get(Calendar.DAY_OF_MONTH);val hour=origin.get(Calendar.HOUR_OF_DAY);val minute=origin.get(Calendar.MINUTE);val second=origin.get(Calendar.SECOND);for(offset in 1..2400){val monthIndex=origin.get(Calendar.YEAR)*12+origin.get(Calendar.MONTH)+offset;val candidate=Calendar.getInstance().apply{isLenient=false;clear();set(monthIndex/12,monthIndex%12,day,hour,minute,second)};val value=runCatching{candidate.timeInMillis}.getOrNull()?:continue;if(value>now)return value};error("无法计算月度提醒")}
        val cal=origin.clone() as Calendar;do{when(recurrence){"daily"->cal.add(Calendar.DAY_OF_YEAR,1);"weekly"->cal.add(Calendar.WEEK_OF_YEAR,1);else->return scheduledAt}}while(cal.timeInMillis<=now);return cal.timeInMillis
    }
    internal fun isCurrentToken(context:Context,id:String,token:String?)=token!=null&&preferences(context).getString(tokenKey(id),null)==token
    @Synchronized internal fun finishDelivery(context:Context,id:String){cancelAlarm(context,id);preferences(context).edit().remove(tokenKey(id)).commit();updateRegistry(context,id,false)}
    private fun cancelAlarm(context:Context,id:String){val pi=PendingIntent.getBroadcast(context,id.hashCode(),Intent(context,ReminderReceiver::class.java),PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE);if(pi!=null)(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)}
    private fun preferences(context:Context)=context.getSharedPreferences("reminders",Context.MODE_PRIVATE)
    private fun tokenKey(id:String)="reminder_token_$id"
    private fun registry(context:Context)=preferences(context).getStringSet(REGISTRY,emptySet())?.toSet().orEmpty()
    @Synchronized private fun updateRegistry(context:Context,id:String,add:Boolean){val values=registry(context).toMutableSet();if(add)values.add(id)else values.remove(id);preferences(context).edit().putStringSet(REGISTRY,values).commit()}
}
class ReminderReceiver:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){
    val id=i.getStringExtra("id")?:return;val token=i.getStringExtra("token");val pending=goAsync()
    CoroutineScope(SupervisorJob()+Dispatchers.IO).launch{try{
        val app=c.applicationContext as? NotebookApp;val note=app?.database?.dao()?.getNoteHeader(id)
        if(!Reminders.isCurrentToken(c,id,token))return@launch
        if(note==null||note.deletedAt!=null||note.completedAt!=null||note.reminderAt==null){Reminders.cancel(c,id);return@launch}
        Reminders.createChannel(c);val title=note.title.ifBlank{"Notebook 提醒"};val openIntent=Intent(c,MainActivity::class.java).putExtra("noteId",id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP);val open=PendingIntent.getActivity(c,id.hashCode(),openIntent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE);val canNotify=android.os.Build.VERSION.SDK_INT<33||c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
        if(canNotify)NotificationManagerCompat.from(c).notify(id.hashCode(),NotificationCompat.Builder(c,Reminders.CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText("点击查看详情").setAutoCancel(true).setContentIntent(open).build())
        val latest=app.database.dao().getNoteHeader(id);if(latest?.deletedAt==null&&latest?.completedAt==null&&latest?.reminderAt!=null&&latest.recurrence!="none"&&Reminders.isCurrentToken(c,id,token))Reminders.schedule(c,id,latest.title,Reminders.nextOccurrence(i.getLongExtra("scheduledAt",System.currentTimeMillis()),latest.recurrence),latest.recurrence)
        else if(latest?.recurrence=="none")Reminders.finishDelivery(c,id)
    }finally{pending.finish()}}
}}

class ReminderRestoreReceiver:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){val accepted=setOf(Intent.ACTION_BOOT_COMPLETED,"android.intent.action.MY_PACKAGE_REPLACED",Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED);if(intent.action !in accepted)return;val pending=goAsync();CoroutineScope(Dispatchers.IO).launch{try{val dao=(context.applicationContext as NotebookApp).database.dao();Reminders.reconcile(context,dao.reminders())}finally{pending.finish()}}}}
