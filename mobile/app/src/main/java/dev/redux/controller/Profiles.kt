// SPDX-License-Identifier: LGPL-3.0-or-later
package dev.redux.controller

import android.content.Context
import org.json.JSONObject

/** One overlay plate: the artwork (or a drawn keypad) and twelve key labels, keys 1-9, *, 0, #. */
data class Plate(val id: String, val name: String, val image: String?, val template: String, val labels: List<String>)

/** A game profile: one or more plates sharing the same Windows buttons 17–28. */
data class Profile(val id: String, val name: String, val subtitle: String, val plates: List<Plate>)

object Profiles {
    const val DEFAULT = "jaguar"

    fun load(context: Context): List<Profile> = parse(context.assets.open("profiles.json").bufferedReader().use { it.readText() })

    fun parse(json: String): List<Profile> {
        val root = JSONObject(json).getJSONArray("profiles")
        val profiles = ArrayList<Profile>()
        for (i in 0 until root.length()) {
            val p = root.getJSONObject(i)
            val plates = ArrayList<Plate>()
            val array = p.getJSONArray("plates")
            for (j in 0 until array.length()) {
                val plate = array.getJSONObject(j)
                val labels = plate.getJSONArray("labels")
                require(labels.length() == 12) { "Plate ${plate.getString("id")} needs 12 labels" }
                plates.add(Plate(plate.getString("id"), plate.getString("name"), plate.optString("image").ifEmpty { null },
                    plate.optString("template", "drawn"), List(12) { labels.getString(it) }))
            }
            require(plates.isNotEmpty()) { "Profile ${p.getString("id")} has no plates" }
            profiles.add(Profile(p.getString("id"), p.getString("name"), p.optString("subtitle"), plates))
        }
        require(profiles.any { it.id == DEFAULT }) { "The default profile is missing" }
        return profiles
    }
}

/**
 * Where the twelve keys sit on each plate template, as fractions of the drawn plate.
 * Columns run left to right, rows top to bottom: key index = row * 3 + column.
 */
object KeypadGeometry {
    class Template(val aspect: Float, val columns: FloatArray, val rows: FloatArray, val cellX: FloatArray, val cellY: FloatArray, val pillWidth: Float, val pillHeight: Float)

    /** The 512-pixel renders, cropped to the plate with its side tabs (462 × 338). */
    val RENDER = Template(462f / 338f,
        floatArrayOf(0.260f, 0.513f, 0.764f), floatArrayOf(0.175f, 0.391f, 0.610f, 0.825f),
        floatArrayOf(0.136f, 0.387f, 0.638f, 0.892f), floatArrayOf(0.095f, 0.308f, 0.521f, 0.737f, 0.959f),
        0.169f, 0.107f)

    /** The AtariAge 400-pixel scans (plate fills the image). */
    val SCAN = Template(400f / 289f,
        floatArrayOf(0.2375f, 0.4875f, 0.7375f), floatArrayOf(0.151f, 0.370f, 0.589f, 0.812f),
        floatArrayOf(0.11f, 0.36f, 0.61f, 0.86f), floatArrayOf(0.05f, 0.27f, 0.49f, 0.71f, 0.925f),
        0.175f, 0.105f)

    fun of(template: String): Template = when (template) { "scan" -> SCAN; else -> RENDER }

    /** Key index under a point given as fractions of the plate, or -1 outside every cell. */
    fun hit(template: Template, fx: Float, fy: Float): Int {
        val column = (0 until 3).firstOrNull { fx >= template.cellX[it] && fx < template.cellX[it + 1] } ?: return -1
        val row = (0 until 4).firstOrNull { fy >= template.cellY[it] && fy < template.cellY[it + 1] } ?: return -1
        return row * 3 + column
    }
}
