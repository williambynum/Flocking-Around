package com.pixel9.signalsurvey.model

import java.util.Locale

/**
 * Translates a generic image-labelling vocabulary into this app's device ontology.
 *
 * ML Kit's image labeller emits everyday nouns — "Television", "Loudspeaker", "Laptop" — not
 * the RF-relevant classes the ontology is keyed on. This is the adapter between the two, and
 * it is deliberately generous about what counts as a match.
 *
 * Two things it will not do:
 *
 * **It will not invent precision.** A generic labeller cannot tell a Wi-Fi router from a set
 * top box; it has no such concept. Anything mapped here is mapped to the *coarsest* class that
 * is actually true, so the RF expectations attached to it stay defensible.
 *
 * **It will not throw away a label it cannot map.** An unmapped "Bookcase" is still displayed
 * as "Bookcase" — a real observation with no RF ontology behind it, which is worth far more to
 * a reader than "unknown device".
 */
object LabelMapper {

    /**
     * Matching is by normalised substring rather than exact string.
     *
     * The labeller's exact wording varies by model version ("Computer monitor" vs "Monitor"),
     * and pinning exact strings means a model update silently stops matching. Substrings over a
     * normalised form survive that.
     */
    private data class Rule(
        val ontologyLabel: String,
        val aliases: List<String>,
        /** Multiplies the labeller's confidence — a loose match should not read as certain. */
        val confidenceScale: Float = 1.0f,
    )

    private val rules: List<Rule> = listOf(
        // --- screens -------------------------------------------------------
        Rule("smart_tv", listOf("television", "tv set")),
        Rule("display_screen", listOf("computer monitor", "monitor", "display", "screen", "projector")),

        // --- audio ---------------------------------------------------------
        Rule("smart_speaker", listOf("loudspeaker", "speaker", "subwoofer", "sound bar", "soundbar")),
        Rule("headphones", listOf("headphones", "headset", "earphone", "earbud")),

        // --- computing -----------------------------------------------------
        Rule("laptop", listOf("laptop", "netbook")),
        Rule("desktop_computer", listOf("personal computer", "desktop computer", "computer case", "server")),
        Rule("smartphone", listOf("mobile phone", "smartphone", "telephone", "cellular")),
        Rule("smartwatch", listOf("watch", "wristwatch", "fitness tracker")),
        Rule("printer", listOf("printer", "scanner", "photocopier")),
        Rule("peripheral", listOf("computer keyboard", "keyboard", "mouse", "gamepad", "joystick", "game controller")),

        // --- cameras -------------------------------------------------------
        // Deliberately generic: the labeller cannot distinguish a security camera from a DSLR,
        // and calling a photographer's camera a "security camera" would be a fabrication.
        Rule("camera_device", listOf("camera", "webcam", "camcorder"), confidenceScale = 0.85f),

        // --- lighting and appliances ---------------------------------------
        Rule("smart_bulb", listOf("light bulb", "lightbulb", "lamp", "ceiling fixture", "chandelier")),
        Rule("major_appliance", listOf(
            "refrigerator", "washing machine", "dishwasher", "microwave", "oven",
            "coffeemaker", "coffee maker", "air conditioner", "clothes dryer",
        )),

        // --- vehicles ------------------------------------------------------
        Rule("vehicle", listOf("car", "vehicle", "truck", "van", "motorcycle", "bus")),

        // --- misc RF-relevant ----------------------------------------------
        Rule("remote_control", listOf("remote control", "remote")),
        Rule("iot_hub", listOf("set top box", "media player", "game console", "video game console")),
    )

    /** Terms that carry no device meaning; matching them adds noise, not information. */
    private val ignored = setOf(
        "furniture", "room", "wall", "floor", "ceiling", "table", "desk", "shelf",
        "person", "hand", "plant", "font", "material property", "rectangle", "product",
        "electronic device", "technology", "electronics", "gadget", "office",
    )

    data class Mapped(
        /** An ontology label when one matched, otherwise null. */
        val ontologyLabel: String?,
        /** Always populated — the raw label, title-cased, when nothing mapped. */
        val displayName: String,
        val confidence: Float,
        /** The labeller's own output, kept for the export so the inference is auditable. */
        val rawLabel: String,
    )

    /**
     * Map the labeller's ranked output to the best available identity.
     *
     * @param candidates label/confidence pairs, best first.
     */
    fun map(candidates: List<Pair<String, Float>>): Mapped? {
        if (candidates.isEmpty()) return null

        val usable = candidates.filterNot { (label, _) -> normalise(label) in ignored }
        if (usable.isEmpty()) return null

        // Prefer the highest-confidence candidate that maps into the ontology, even if a
        // stronger unmapped label outranks it — an ontology hit carries RF expectations and
        // is worth more than a marginally more confident dead end.
        usable.forEach { (label, confidence) ->
            val normalised = normalise(label)
            rules.firstOrNull { rule -> rule.aliases.any { normalised.contains(it) } }?.let { rule ->
                return Mapped(
                    ontologyLabel = rule.ontologyLabel,
                    displayName = DeviceOntology.forLabel(rule.ontologyLabel)?.displayName
                        ?: titleCase(label),
                    confidence = (confidence * rule.confidenceScale).coerceIn(0f, 1f),
                    rawLabel = label,
                )
            }
        }

        // Nothing mapped. Return the strongest label anyway — a named object with no RF
        // profile still tells the reader what they are looking at.
        val (label, confidence) = usable.first()
        return Mapped(
            ontologyLabel = null,
            displayName = titleCase(label),
            confidence = confidence,
            rawLabel = label,
        )
    }

    private fun normalise(raw: String): String =
        raw.lowercase(Locale.US).replace('-', ' ').replace('_', ' ').trim()

    private fun titleCase(raw: String): String =
        raw.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
}
