package com.nuclearhack.steno

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import java.io.File

/**
 * Пакетный прогон для eval (работает и в release):
 *   adb shell am start -n com.nuclearhack.steno/.DebugActivity --es in <файл|каталог>
 * Файл *.json (расшифровка) -> только резюме. Резюме после каждого файла: --ez summary true. Число спикеров: --ei spk N (по умолчанию авто). Язык: --es lang en (по умолчанию ru).
 * По умолчанию in = <externalFilesDir>/in. Результаты: <externalFilesDir>/out/<имя>.json и <имя>.meta.json, по окончании out/DONE.
 * Сама работа идёт в [ProcessingService] — переживает блокировку экрана.
 */
class DebugActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val base = getExternalFilesDir(null)!!
        File(base, "in").mkdirs()
        val outDir = File(base, "out").apply { mkdirs() }
        File(outDir, "DONE").delete()
        Models.preload(applicationContext)
        ProcessingService.start(this, Intent(ProcessingService.ACTION_BATCH)
            .putExtra(ProcessingService.EXTRA_IN, intent.getStringExtra("in") ?: File(base, "in").path)
            .putExtra(ProcessingService.EXTRA_SPK, intent.getIntExtra("spk", -1))
            .putExtra(ProcessingService.EXTRA_LANG, intent.getStringExtra("lang") ?: "ru")
            .putExtra(ProcessingService.EXTRA_SUMMARY, intent.getBooleanExtra("summary", false)))
        finish()
    }
}
