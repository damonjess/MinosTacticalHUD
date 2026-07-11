package com.minos.hud

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

class CyberSecSoundManager(context: Context) {
    private val soundPool: SoundPool
    private var clickSoundId: Int = 0
    private var alarmSoundId: Int = 0
    private var scanSoundId: Int = 0

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
            
        soundPool = SoundPool.Builder()
            .setMaxStreams(5)
            .setAudioAttributes(attrs)
            .build()

        // Place your short raw .wav or .mp3 files inside app/src/main/res/raw/
        // If files are missing, these will fail gracefully without crashing
        val pkg = context.packageName
        
        val clickId = context.resources.getIdentifier("terminal_click", "raw", pkg)
        if (clickId != 0) clickSoundId = soundPool.load(context, clickId, 1)
        
        val alarmId = context.resources.getIdentifier("breach_alarm", "raw", pkg)
        if (alarmId != 0) alarmSoundId = soundPool.load(context, alarmId, 1)
        
        val scanId = context.resources.getIdentifier("radar_blip", "raw", pkg)
        if (scanId != 0) scanSoundId = soundPool.load(context, scanId, 1)
    }

    fun playClick() { if (clickSoundId != 0) soundPool.play(clickSoundId, 0.6f, 0.6f, 1, 0, 1.0f) }
    fun playAlarm() { if (alarmSoundId != 0) soundPool.play(alarmSoundId, 0.8f, 0.8f, 2, 0, 1.0f) }
    fun playScan()  { if (scanSoundId != 0) soundPool.play(scanSoundId, 0.3f, 0.3f, 1, 0, 0.9f) }
    
    fun release() { soundPool.release() }
}
