package com.dailyapp.probe

import android.app.Person
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/** 把 Bundle 轉成 JSON，盡量保留所有欄位；無法序列化的只記錄型別。 */
object Dump {
    private const val MAX_DEPTH = 5

    fun bundle(b: Bundle?, depth: Int = 0): JSONObject {
        val o = JSONObject()
        if (b == null) return o
        for (k in b.keySet()) {
            val v = try {
                @Suppress("DEPRECATION")
                b.get(k)
            } catch (e: Exception) {
                "<error ${e.javaClass.simpleName}>"
            }
            o.put(k, value(v, depth + 1))
        }
        return o
    }

    private fun value(v: Any?, depth: Int): Any {
        if (v == null) return JSONObject.NULL
        if (depth > MAX_DEPTH) return "<depth-limit ${v.javaClass.name}>"
        return when (v) {
            is CharSequence -> v.toString()
            is Number, is Boolean -> v
            is Bundle -> bundle(v, depth)
            is Person -> JSONObject()
                .put("_type", "Person")
                .put("name", v.name?.toString() ?: JSONObject.NULL)
                .put("key", v.key ?: JSONObject.NULL)
                .put("uri", v.uri ?: JSONObject.NULL)
                .put("isBot", v.isBot)
                .put("isImportant", v.isImportant)
            is Bitmap -> "<Bitmap ${v.width}x${v.height}>"
            is Icon -> "<Icon>"
            is Array<*> -> JSONArray().apply { v.forEach { put(value(it, depth + 1)) } }
            is IntArray -> JSONArray(v.toList())
            is LongArray -> JSONArray(v.toList())
            is BooleanArray -> JSONArray(v.toList())
            is Iterable<*> -> JSONArray().apply { v.forEach { put(value(it, depth + 1)) } }
            else -> "<${v.javaClass.name}> ${v.toString().take(200)}"
        }
    }
}
