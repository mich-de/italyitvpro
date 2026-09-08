package com.michde.italyitv.data.parser

import android.util.Xml
import com.michde.italyitv.data.model.Programme
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

data class EpgChannel(
    val id: String,
    val names: List<String>,
    val icon: String?,
)

data class EpgData(
    val channels: List<EpgChannel>,
    val programmes: List<Programme>,
)

/** Streaming XMLTV pull-parser. Keeps memory bounded. */
object XmltvParser {

    private val FMT_TZ = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    private val FMT_NOTZ = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)

    private fun parseTime(v: String?): Long {
        if (v.isNullOrBlank()) return 0L
        val s = v.trim()
        return runCatching {
            if (s.length > 14) FMT_TZ.parse(s)!!.time else FMT_NOTZ.parse(s.take(14))!!.time
        }.getOrDefault(0L)
    }

    fun parse(input: InputStream, keepProgrammes: Boolean = true): EpgData {
        val channels = ArrayList<EpgChannel>()
        val programmes = ArrayList<Programme>()
        input.use { stream ->
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(stream, null)

            var curChannelId: String? = null
            var curNames: MutableList<String>? = null
            var curIcon: String? = null

            var prgChannel: String? = null
            var prgStart = 0L
            var prgStop = 0L
            var prgTitle: String? = null
            var prgDesc: StringBuilder? = null
            var textTarget: ((String) -> Unit)? = null

            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "channel" -> {
                            curChannelId = p.getAttributeValue(null, "id")
                            curNames = ArrayList()
                            curIcon = null
                        }
                        "display-name" -> if (curNames != null) {
                            textTarget = { s -> if (s.isNotBlank()) curNames!!.add(s.trim()) }
                        }
                        "icon" -> {
                            val src = p.getAttributeValue(null, "src")
                            if (curChannelId != null && curIcon == null && !src.isNullOrBlank()) curIcon = src
                        }
                        "programme" -> {
                            prgChannel = p.getAttributeValue(null, "channel")
                            prgStart = parseTime(p.getAttributeValue(null, "start"))
                            prgStop = parseTime(p.getAttributeValue(null, "stop"))
                            prgTitle = null
                            prgDesc = null
                        }
                        "title" -> if (prgChannel != null) textTarget = { s -> if (prgTitle == null && s.isNotBlank()) prgTitle = s.trim() }
                        "desc" -> if (prgChannel != null) {
                            prgDesc = StringBuilder()
                            textTarget = { s -> prgDesc?.append(s) }
                        }
                    }
                    XmlPullParser.TEXT -> textTarget?.invoke(p.text ?: "")
                    XmlPullParser.END_TAG -> {
                        when (p.name) {
                            "display-name", "title", "desc" -> textTarget = null
                            "channel" -> {
                                val id = curChannelId
                                if (id != null) channels.add(EpgChannel(id, curNames?.toList() ?: emptyList(), curIcon))
                                curChannelId = null; curNames = null; curIcon = null
                            }
                            "programme" -> {
                                val ch = prgChannel
                                if (keepProgrammes && ch != null && prgStop > prgStart && !prgTitle.isNullOrBlank()) {
                                    programmes.add(
                                        Programme(ch, prgStart, prgStop, prgTitle!!, prgDesc?.toString()?.trim()?.takeIf { it.isNotBlank() })
                                    )
                                }
                                prgChannel = null
                            }
                        }
                    }
                }
                event = p.next()
            }
        }
        return EpgData(channels, programmes)
    }
}
