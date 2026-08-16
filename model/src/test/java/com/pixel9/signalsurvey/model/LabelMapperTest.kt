package com.pixel9.signalsurvey.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapper is where a generic vocabulary meets an RF ontology, and it is easy to get wrong
 * in two opposite directions: silently dropping labels it cannot map, or mapping them to
 * something more specific than the labeller could possibly have known.
 */
class LabelMapperTest {

    @Test
    fun `maps everyday labels into the ontology`() {
        listOf(
            "Television" to "smart_tv",
            "Computer monitor" to "display_screen",
            "Loudspeaker" to "smart_speaker",
            "Laptop" to "laptop",
            "Printer" to "printer",
            "Mobile phone" to "smartphone",
            "Headphones" to "headphones",
            "Light bulb" to "smart_bulb",
            "Refrigerator" to "major_appliance",
        ).forEach { (raw, expected) ->
            val mapped = LabelMapper.map(listOf(raw to 0.9f))
            assertNotNull("$raw produced no mapping", mapped)
            assertEquals("$raw mapped wrong", expected, mapped!!.ontologyLabel)
        }
    }

    @Test
    fun `every mapped label resolves to a real ontology entry`() {
        // A mapping that points at a label DeviceOntology doesn't define would silently
        // produce a target with no RF expectations at all.
        listOf(
            "Television", "Computer monitor", "Loudspeaker", "Laptop", "Printer",
            "Mobile phone", "Headphones", "Light bulb", "Refrigerator", "Camera",
            "Remote control", "Computer keyboard", "Car", "Watch", "Game console",
            "Personal computer",
        ).forEach { raw ->
            val ontologyLabel = LabelMapper.map(listOf(raw to 0.9f))?.ontologyLabel
            if (ontologyLabel != null) {
                assertNotNull(
                    "$raw maps to '$ontologyLabel', which DeviceOntology does not define",
                    DeviceOntology.forLabel(ontologyLabel),
                )
            }
        }
    }

    @Test
    fun `matching survives wording changes in the label model`() {
        // The labeller's exact strings vary by model version; pinning them would mean a
        // model update silently stops matching.
        assertEquals("display_screen", LabelMapper.map(listOf("Monitor" to 0.8f))?.ontologyLabel)
        assertEquals("display_screen", LabelMapper.map(listOf("computer monitor" to 0.8f))?.ontologyLabel)
        assertEquals("smart_speaker", LabelMapper.map(listOf("Sound bar" to 0.8f))?.ontologyLabel)
        assertEquals("smart_speaker", LabelMapper.map(listOf("soundbar" to 0.8f))?.ontologyLabel)
    }

    @Test
    fun `unmapped labels are still named, not discarded`() {
        val mapped = LabelMapper.map(listOf("Bookcase" to 0.72f))
        assertNotNull(mapped)
        assertNull("Bookcase has no RF profile and must not claim one", mapped!!.ontologyLabel)
        assertEquals("Bookcase", mapped.displayName)
        assertEquals(0.72f, mapped.confidence, 0.001f)
    }

    @Test
    fun `an ontology hit outranks a more confident dead end`() {
        // "Product" is noise; "Loudspeaker" carries RF expectations even at lower confidence.
        val mapped = LabelMapper.map(listOf("Product" to 0.95f, "Loudspeaker" to 0.61f))
        assertEquals("smart_speaker", mapped?.ontologyLabel)
    }

    @Test
    fun `noise-only candidates produce nothing`() {
        assertNull(LabelMapper.map(listOf("Furniture" to 0.9f, "Room" to 0.8f)))
        assertNull(LabelMapper.map(emptyList()))
    }

    @Test
    fun `camera stays generic rather than claiming to be a security camera`() {
        val mapped = LabelMapper.map(listOf("Camera" to 0.9f))
        assertEquals("camera_device", mapped?.ontologyLabel)
        // Confidence is scaled down: the labeller cannot tell a DSLR from a wall-mounted
        // security camera, and the two have very different RF profiles.
        assertTrue("loose match should not read as certain", mapped!!.confidence < 0.9f)
    }

    @Test
    fun `raw label is preserved for the audit trail`() {
        val mapped = LabelMapper.map(listOf("Television" to 0.88f))
        assertEquals("Television", mapped?.rawLabel)
        assertEquals("Smart TV / Streaming Device", mapped?.displayName)
    }
}
