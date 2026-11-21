package com.example.jarvis

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import org.json.JSONArray
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel

class WakeEngine(
    private val model: Model,
    private val spk: SpeakerModel?,
    private val level: Int,
    private val onSegment: (String, Int) -> Unit,
    private val onInfo: (String) -> Unit
) {
    @Volatile private var run = false

    fun start() {
        if (run) return
        run = true
        val t = Thread { loop() }
        t.isDaemon = true
        t.start()
    }

    fun stop() {
        run = false
    }

    private fun grammar(): String = JSONArray(listOf(
        "jarvis", "[unk]", "service", "travis", "harvest", "nervous", "hello", "okay", "yes", "no",
        "hey", "the", "and", "you", "what", "why", "this", "that", "there", "have", "was", "are",
        "for", "with", "not", "but", "can", "just", "like", "now", "come", "go", "ask", "carpet",
        "charge", "garage", "marvel", "mavis", "jervis", "chess", "jar", "bus", "gas", "vice",
        "advice", "marvin", "arvind", "starts", "darts", "parts")).toString()

    private fun loop() {
        val rate = 16000
        val chunk = 1600
        var ar: AudioRecord? = null
        var rec: Recognizer? = null
        var agc: AutomaticGainControl? = null
        var ns: NoiseSuppressor? = null
        try {
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT)
            val a = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, chunk * 8))
            ar = a
            if (a.state != AudioRecord.STATE_INITIALIZED) {
                onInfo("Mic open hoyni")
                return
            }
            try {
                if (AutomaticGainControl.isAvailable()) {
                    agc = AutomaticGainControl.create(a.audioSessionId)
                    agc?.setEnabled(true)
                }
            } catch (e: Exception) {
            }
            try {
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(a.audioSessionId)
                    ns?.setEnabled(true)
                }
            } catch (e: Exception) {
            }
            val r = Recognizer(model, 16000f, grammar())
            rec = r
            r.setWords(true)
            if (spk != null) r.setSpeakerModel(spk)
            a.startRecording()

            val buf = ShortArray(chunk)
            val pre = ArrayList<ShortArray>()
            var noise = 60f
            var warm = 0
            var inSp = false
            var quiet = 0
            var segN = 0
            var gain = 1f
            var bad = 0
            val f = when (level) { 0 -> 1.8f; 2 -> 2.8f; else -> 2.2f }
            val gmax = when (level) { 0 -> 12f; 2 -> 5f; else -> 8f }
            val floor = if (level == 0) 80f else 110f

            fun feed(src: ShortArray, len: Int, g: Float): Boolean {
                val out = ShortArray(len)
                for (i in 0 until len) {
                    val v = src[i] * g
                    out[i] = if (v > 32767f) Short.MAX_VALUE
                    else if (v < -32768f) Short.MIN_VALUE
                    else v.toInt().toShort()
                }
                return r.acceptWaveForm(out, len)
            }

            while (run) {
                val n = a.read(buf, 0, chunk)
                if (n <= 0) {
                    bad++
                    if (bad > 50) {
                        onInfo("Mic bondho hoye gese. Jarvis abar chalu koro")
                        break
                    }
                    Thread.sleep(20)
                    continue
                }
                bad = 0
                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                val rms = Math.sqrt(sum / n).toFloat()
                if (warm < 5) {
                    noise = (noise * warm + rms) / (warm + 1)
                    warm++
                    continue
                }
                val voiced = rms > maxOf(noise * f, floor)
                if (!inSp) {
                    if (voiced) {
                        inSp = true
                        quiet = 0
                        segN = 1
                        gain = (2400f / maxOf(rms, 1f)).coerceIn(1f, gmax)
                        var ep = false
                        for (p in pre) if (feed(p, p.size, gain)) ep = true
                        pre.clear()
                        if (feed(buf, n, gain)) ep = true
                        if (ep) {
                            onSegment(r.result, segN * 100)
                            inSp = false
                        }
                    } else {
                        noise = noise * 0.97f + rms * 0.03f
                        pre.add(buf.copyOf(n))
                        if (pre.size > 3) pre.removeAt(0)
                    }
                } else {
                    val g = (2400f / maxOf(rms, 1f)).coerceIn(1f, gmax)
                    gain = gain * 0.7f + g * 0.3f
                    val ep = feed(buf, n, gain)
                    segN++
                    if (voiced) quiet = 0 else quiet++
                    if (ep) {
                        onSegment(r.result, segN * 100)
                        inSp = false
                    } else if (quiet >= 5 || segN > 30) {
                        val res = r.finalResult
                        if (segN <= 30) onSegment(res, segN * 100)
                        inSp = false
                    }
                }
            }
        } catch (e: Exception) {
            onInfo("Wake error: " + (e.message ?: ""))
        } finally {
            try { ar?.stop() } catch (e: Exception) {}
            try { ar?.release() } catch (e: Exception) {}
            try { agc?.release() } catch (e: Exception) {}
            try { ns?.release() } catch (e: Exception) {}
            try { rec?.close() } catch (e: Exception) {}
            run = false
        }
    }
}
