package af.rahyab.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** ذخیره خانه، محل کار، علاقه‌مندی‌ها و مکان‌های اخیر فقط روی خود گوشی */
class PlacesStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("places", Context.MODE_PRIVATE)

    private fun enc(p: Place) = JSONObject().put("n", p.name).put("a", p.lat).put("o", p.lon)
    private fun dec(o: JSONObject) = Place(o.getString("n"), o.getDouble("a"), o.getDouble("o"))

    private fun readOne(key: String): Place? = try {
        sp.getString(key, null)?.let { dec(JSONObject(it)) }
    } catch (e: Exception) { null }

    private fun readList(key: String): MutableList<Place> {
        val out = ArrayList<Place>()
        try {
            val arr = JSONArray(sp.getString(key, "[]"))
            for (i in 0 until arr.length()) out.add(dec(arr.getJSONObject(i)))
        } catch (e: Exception) { }
        return out
    }

    private fun writeList(key: String, list: List<Place>) {
        val arr = JSONArray()
        list.forEach { arr.put(enc(it)) }
        sp.edit().putString(key, arr.toString()).apply()
    }

    var home: Place?
        get() = readOne("home")
        set(v) { sp.edit().putString("home", v?.let { enc(it).toString() }).apply() }

    var work: Place?
        get() = readOne("work")
        set(v) { sp.edit().putString("work", v?.let { enc(it).toString() }).apply() }

    fun favorites(): List<Place> = readList("fav")
    fun recents(): List<Place> = readList("recent")

    fun addFavorite(p: Place) {
        val l = readList("fav"); l.removeAll { it.lat == p.lat && it.lon == p.lon }; l.add(0, p); writeList("fav", l)
    }

    fun addRecent(p: Place) {
        val l = readList("recent"); l.removeAll { it.lat == p.lat && it.lon == p.lon }; l.add(0, p)
        writeList("recent", l.take(15))
    }

    fun clearRecents() = writeList("recent", emptyList())

    /** جست‌وجوی آفلاین بین مکان‌های ذخیره‌شده */
    fun searchLocal(q: String): List<Place> {
        val all = ArrayList<Place>()
        home?.let { all.add(it) }; work?.let { all.add(it) }
        all.addAll(favorites()); all.addAll(recents())
        return all.filter { it.name.contains(q, ignoreCase = true) }.distinctBy { it.lat to it.lon }
    }
}
