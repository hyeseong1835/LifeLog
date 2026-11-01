package dev.hyeseong.lifelog.collector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.hyeseong.lifelog.collector.core.Config

/** 부팅 뒤 자동 시작. 사용자가 마지막으로 '수집 시작'을 눌러 둔 상태(autostart)일 때만. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val cfg = try { Config(context) } catch (e: Exception) { CollectorService.log(context, "부팅 자동 시작: 설정을 못 읽음 $e"); return }
        if (!cfg.autostart || !cfg.isComplete) return
        try {
            context.startForegroundService(Intent(context, CollectorService::class.java))
            CollectorService.log(context, "부팅 자동 시작")
        } catch (e: Exception) { CollectorService.log(context, "부팅 자동 시작 실패: $e") }
    }
}
