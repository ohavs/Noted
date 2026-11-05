package com.ohav.noted.data

/**
 * Kinds of place. Each picks the geofence radius a new place starts with: a home is a building,
 * a supermarket has a car park, a city is not a point (150 m round its centre would ring only on
 * the town-hall square). Keys are stored — never rename them.
 */
enum class PlaceKind(val key: String, val label: String, val radius: Int, val device: Boolean = false) {
    Home("home", "בית", 100),
    Work("work", "עבודה", 150),
    Super("super", "סופר", 150),
    Pharmacy("pharmacy", "בית מרקחת", 100),
    Family("family", "משפחה", 100),
    Sport("sport", "ספורט", 150),
    Cafe("cafe", "בית קפה", 100),
    Food("food", "מסעדה", 100),
    Study("study", "לימודים", 200),
    Shop("shop", "חנות", 100),
    Park("park", "פארק", 250),
    Pray("pray", "בית כנסת", 100),
    City("city", "עיר", 1000),
    Other("other", "מקום", 150),

    // Bluetooth devices: arriving = connected, leaving = disconnected (no radius)
    Car("car", "רכב", 0, device = true),
    Headphones("headphones", "אוזניות", 0, device = true),
    Speaker("speaker", "רמקול", 0, device = true),
    Gadget("gadget", "מכשיר", 0, device = true),
    ;

    companion object {
        fun of(key: String): PlaceKind = entries.firstOrNull { it.key == key } ?: Other

        /** Kinds of a point on the map — the place form offers only these. */
        val geo: List<PlaceKind> get() = entries.filter { !it.device }
        val devices: List<PlaceKind> get() = entries.filter { it.device }

        /** Radii offered when adjusting by hand (metres). */
        val RADII = listOf(100, 150, 250, 500, 1000, 2000)
    }
}
