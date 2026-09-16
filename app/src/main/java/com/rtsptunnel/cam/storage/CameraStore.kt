package com.rtsptunnel.cam.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SavedCamera(
    val id: Long,
    val name: String,
    val url: String,
    val proxy: String,
    val user: String?,
    val pass: String?
)

/**
 * Persistenza LOCALE delle telecamere salvate (SharedPreferences + JSON):
 * nessuna dipendenza cloud, nessun database centrale, nessun permesso extra.
 * Le credenziali sono opzionali e salvate in chiaro sul dispositivo
 * (il checkbox nell'UI avvisa l'utente del rischio).
 */
class CameraStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun list(): List<SavedCamera> {
        val arr = runCatching { JSONArray(prefs.getString(KEY, "[]") ?: "[]") }
            .getOrDefault(JSONArray())
        val out = ArrayList<SavedCamera>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                SavedCamera(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    url = o.getString("url"),
                    proxy = o.optString("proxy", ""),
                    user = if (o.has("user") && !o.isNull("user")) o.getString("user") else null,
                    pass = if (o.has("pass") && !o.isNull("pass")) o.getString("pass") else null
                )
            )
        }
        return out
    }

    /** Salva (o sovrascrive, se stesso URL) una telecamera. */
    fun save(name: String, url: String, proxy: String, user: String?, pass: String?): SavedCamera {
        val cams = list().toMutableList()
        cams.removeAll { it.url == url }
        val cam = SavedCamera(System.currentTimeMillis(), name, url, proxy, user, pass)
        cams.add(cam)
        persist(cams)
        return cam
    }

    fun delete(id: Long) {
        val cams = list().toMutableList()
        cams.removeAll { it.id == id }
        persist(cams)
    }

    private fun persist(cams: List<SavedCamera>) {
        val arr = JSONArray()
        for (c in cams) {
            val o = JSONObject()
            o.put("id", c.id)
            o.put("name", c.name)
            o.put("url", c.url)
            o.put("proxy", c.proxy)
            o.put("user", c.user ?: JSONObject.NULL)
            o.put("pass", c.pass ?: JSONObject.NULL)
            arr.put(o)
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        const val PREFS_NAME = "rtspcam_store"
        const val KEY = "cameras"
    }
}
