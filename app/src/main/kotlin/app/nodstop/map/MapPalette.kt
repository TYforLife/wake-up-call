package app.nodstop.map

/**
 * The map in Google Maps' colours (PLAN Run 11, B2; the owner: "make it look more like Google Maps"). The base is
 * OpenFreeMap's `liberty` style; each of its layers is given a [Role] from its type and ID, and each role a colour,
 * by day or by night. Plain Kotlin (colours as ARGB), so the mapping is tested on the JVM; `RideMap` applies it.
 */
object MapPalette {
    enum class Role {
        LAND, WATER, PARK, BUILDING, STREET, STREET_CASING, MAJOR, MAJOR_CASING, MOTORWAY, MOTORWAY_CASING,
        PATH, RAIL, BOUNDARY, LABEL, LABEL_PLACE, LABEL_WATER, LABEL_HALO,
    }

    /** One colour scheme: the base map, then Nodstop's own marks drawn in Google's style. */
    data class Colors(
        val base: Map<Role, Long>,
        /** Your stop: Google's red pin, and its dark centre. */
        val pin: Long,
        val pinDot: Long,
        /** The learned route: Google's route blue, with a darker edge. */
        val route: Long,
        val routeCasing: Long,
        /** You: the blue dot (also the heading beam and the circle's tint). */
        val me: Long,
        /** Bus stops on the map. */
        val busStop: Long,
        /** The other stops of this trip. */
        val otherStop: Long,
    )

    val day = Colors(
        base = mapOf(
            Role.LAND to 0xFFF2EFE9, Role.WATER to 0xFFAADAFF, Role.PARK to 0xFFC5E8C5, Role.BUILDING to 0xFFE8E4DE,
            Role.STREET to 0xFFFFFFFF, Role.STREET_CASING to 0xFFDDD8D0,
            Role.MAJOR to 0xFFFDE6A1, Role.MAJOR_CASING to 0xFFF0C45A,
            Role.MOTORWAY to 0xFFF9C55B, Role.MOTORWAY_CASING to 0xFFE19B32,
            Role.PATH to 0xFFFBFAF8, Role.RAIL to 0xFFC7C2BB, Role.BOUNDARY to 0xFFB9B4AE,
            Role.LABEL to 0xFF5F6368, Role.LABEL_PLACE to 0xFF3C4043, Role.LABEL_WATER to 0xFF4A7FB0, Role.LABEL_HALO to 0xFFFFFFFF,
        ),
        pin = 0xFFEA4335, pinDot = 0xFFB31412, route = 0xFF4285F4, routeCasing = 0xFF1967D2,
        me = 0xFF1A73E8, busStop = 0xFF1A73E8, otherStop = 0xFF5F6368,
    )

    /** Google's night map: navy land, dark teal parks, brown-grey main roads. */
    val night = Colors(
        base = mapOf(
            Role.LAND to 0xFF242F3E, Role.WATER to 0xFF17263C, Role.PARK to 0xFF263C3F, Role.BUILDING to 0xFF2B3544,
            Role.STREET to 0xFF38414E, Role.STREET_CASING to 0xFF212A37,
            Role.MAJOR to 0xFF746855, Role.MAJOR_CASING to 0xFF1F2835,
            Role.MOTORWAY to 0xFF8A7A5E, Role.MOTORWAY_CASING to 0xFF1F2835,
            Role.PATH to 0xFF2C3644, Role.RAIL to 0xFF2F3948, Role.BOUNDARY to 0xFF4B6878,
            Role.LABEL to 0xFF9CA5B3, Role.LABEL_PLACE to 0xFFD59563, Role.LABEL_WATER to 0xFF515C6D, Role.LABEL_HALO to 0xFF17263C,
        ),
        pin = 0xFFEA4335, pinDot = 0xFF8C1007, route = 0xFF669DF6, routeCasing = 0xFF1A56B8,
        me = 0xFF4285F4, busStop = 0xFF8AB4F8, otherStop = 0xFF9CA5B3,
    )

    fun colors(dark: Boolean): Colors = if (dark) night else day

    private fun has(id: String, vararg words: String) = words.any { it in id }

    /**
     * The role of a base-style layer, from its type ("background", "fill", "line", "symbol") and ID, or null to
     * leave it as the style has it. IDs follow OpenMapTiles styles: `road_motorway`, `bridge_primary_casing`…
     */
    fun role(type: String, layerId: String): Role? {
        val id = layerId.lowercase()
        return when (type) {
            "background" -> Role.LAND
            "fill" -> when {
                has(id, "water", "ocean", "lake") -> Role.WATER
                has(id, "park", "wood", "grass", "forest", "green", "cemetery", "pitch", "garden", "scrub", "landcover") -> Role.PARK
                has(id, "building") -> Role.BUILDING
                else -> Role.LAND
            }
            "line" -> when {
                has(id, "water", "river", "stream", "canal") -> Role.WATER
                has(id, "boundary", "admin") -> Role.BOUNDARY
                has(id, "rail", "transit", "subway", "tram") -> Role.RAIL
                has(id, "path", "pedestrian", "footway", "cycleway", "track", "steps") -> Role.PATH
                has(id, "motorway") -> if (has(id, "casing")) Role.MOTORWAY_CASING else Role.MOTORWAY
                has(id, "trunk", "primary", "secondary") -> if (has(id, "casing")) Role.MAJOR_CASING else Role.MAJOR
                has(id, "road", "street", "highway", "tertiary", "minor", "service", "bridge", "tunnel") ->
                    if (has(id, "casing")) Role.STREET_CASING else Role.STREET
                else -> null
            }
            "symbol" -> when {
                has(id, "water", "ocean", "lake", "river", "marine") -> Role.LABEL_WATER
                has(id, "place", "city", "town", "suburb", "country", "state") -> Role.LABEL_PLACE
                else -> Role.LABEL
            }
            else -> null
        }
    }

    // ---- bus stops (PLAN B2: "with bus stops visible") ----

    /** The base map's vector source and its points of interest (OpenMapTiles schema). */
    const val SOURCE = "openmaptiles"
    const val POI_LAYER = "poi"

    /** Bus stops are POIs of class "bus" (subclass `bus_stop`, `bus_station`). Shown from this zoom, as on Google. */
    const val BUS_STOP_CLASS = "bus"
    val BUS_STOP_SUBCLASSES = listOf("bus_stop", "bus_station")
    const val BUS_STOP_MIN_ZOOM = 14.5f
    /** Their names, closer in. */
    const val BUS_STOP_LABEL_MIN_ZOOM = 16.5f

    fun isBusStop(cls: String?, subclass: String?): Boolean = cls == BUS_STOP_CLASS || subclass in BUS_STOP_SUBCLASSES
}
