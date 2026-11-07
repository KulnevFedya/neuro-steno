package com.nuclearhack.steno

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import java.io.File
import kotlin.concurrent.thread

/**
 * Прогон для eval:
 *   adb shell am start -n com.nuclearhack.steno/.DebugActivity --es in <файл|каталог>
 * Число спикеров: --ei spk N (по умолчанию авто). По умолчанию in = <externalFilesDir>/in. Результаты: <externalFilesDir>/out/<имя>.json и <имя>.meta.json,
 * по окончании создаётся out/DONE.
 */
class DebugActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val base = getExternalFilesDir(null)!!
        File(base, "in").mkdirs()
        val outDir = File(base, "out").apply { mkdirs() }
        File(outDir, "DONE").delete()
        val input = File(intent.getStringExtra("in") ?: File(base, "in").path)
        val files = if (input.isDirectory) (input.listFiles() ?: emptyArray()).filter { it.isFile }.sortedBy { it.name } else listOf(input)
        val spk = intent.getIntExtra("spk", -1)
        thread {
            for (f in files) {
                try {
                    val r = Transcriber(applicationContext).run(Uri.fromFile(f), spk)
                    val name = f.nameWithoutExtension
                    File(outDir, "$name.json").writeText(TranscriptJson.build(r.utterances))
                    File(outDir, "$name.meta.json").writeText(
                        "{\"speakers\": ${r.numSpeakers}, \"audio_ms\": ${r.audioMs}, \"proc_ms\": ${r.decodeAsrMs}, \"rtf\": ${r.rtf}}\n"
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "debug run failed for ${f.name}", t)
                    File(outDir, "${f.nameWithoutExtension}.error").writeText(t.stackTraceToString())
                }
            }
            File(outDir, "DONE").writeText("ok")
            runOnUiThread { finish() }
        }
    }
}
