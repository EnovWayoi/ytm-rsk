package dev.enov.ytm

import org.json.JSONObject

object NativeCore {
    init { System.loadLibrary("ytm_core") }
    private external fun call(input: String): String
    fun run(op: String, vararg fields: Pair<String, Any>): Any {
        val request = JSONObject().put("op", op)
        fields.forEach { (k, v) -> request.put(k, v) }
        val response = JSONObject(call(request.toString()))
        if (response.has("error")) error(response.getString("error"))
        return response.get("data")
    }
}
