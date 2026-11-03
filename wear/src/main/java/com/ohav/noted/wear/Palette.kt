package com.ohav.noted.wear

/** The phone app's colours as ARGB ints (shared by the Compose UI and the tile). */
object Palette {
    const val BG = 0xFF000000.toInt()
    const val SURFACE = 0xFF1C1A17.toInt()
    const val SURFACE2 = 0xFF2A2723.toInt()
    const val INK = 0xFFF4EFE7.toInt()
    const val INK2 = 0xA6F4EFE7.toInt()
    const val INK3 = 0x61F4EFE7.toInt()
    const val NOTE_INK = 0xFF1D1A15.toInt()
    const val NOTE_INK2 = 0x9E1D1A15.toInt()

    /** Note pastels, same names as the phone's NoteColor. */
    private val notes = mapOf(
        "Butter" to 0xFFFAE5A0, "Peach" to 0xFFF9CFB6, "Rose" to 0xFFF5C6D2, "Lilac" to 0xFFDACDF6,
        "Sky" to 0xFFC4DDF5, "Mint" to 0xFFC6EAD5, "Sage" to 0xFFDBE4C0, "Sand" to 0xFFECE1D2,
    )

    fun note(name: String): Int = (notes.entries.firstOrNull { it.key.equals(name, true) }?.value ?: 0xFFECE1D2).toInt()

    /** The phone's accent, its dark-theme shade (the watch is always dark). */
    fun accent(name: String?): Int = when (name) {
        "Forest" -> 0xFF45C891
        "Ink" -> 0xFF8A95FF
        "Plum" -> 0xFFCF93F0
        else -> 0xFFFF7A4F
    }.toInt()

    /** Ink on top of the accent. */
    fun onAccent(name: String?): Int = when (name) {
        "Forest" -> 0xFF06170F
        "Ink" -> 0xFF0B0E2B
        "Plum" -> 0xFF1E0B29
        else -> 0xFF1B0E08
    }.toInt()
}
