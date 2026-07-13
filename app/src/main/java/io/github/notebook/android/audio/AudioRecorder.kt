package io.github.notebook.android.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class AudioRecorder(private val context:Context){
    private var recorder:MediaRecorder?=null
    var isRecording=false;private set
    fun start(output:File){check(!isRecording);output.parentFile?.mkdirs();val r=if(Build.VERSION.SDK_INT>=31)MediaRecorder(context)else @Suppress("DEPRECATION") MediaRecorder();r.setAudioSource(MediaRecorder.AudioSource.MIC);r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);r.setAudioEncodingBitRate(128_000);r.setAudioSamplingRate(44_100);r.setOutputFile(output.absolutePath);r.prepare();r.start();recorder=r;isRecording=true}
    fun stop():Boolean{val r=recorder?:return false;return try{r.stop();true}catch(_:RuntimeException){false}finally{r.release();recorder=null;isRecording=false}}
    fun release(){if(isRecording)runCatching{recorder?.stop()};recorder?.release();recorder=null;isRecording=false}
}
