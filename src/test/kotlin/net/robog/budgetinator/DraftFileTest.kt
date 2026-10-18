package net.robog.budgetinator

import net.robog.budgetinator.model.*
import net.robog.budgetinator.persist.Autosave
import net.robog.budgetinator.persist.Draft
import net.robog.budgetinator.persist.DraftFile
import net.robog.budgetinator.persist.DraftFormatException
import net.robog.budgetinator.validation.Step
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

class DraftFileTest {
    private fun png(w: Int = 40, h: Int = 30): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    private fun sample() = BudgetRequest(
        header = Header(
            type = BudgetType.PROGRAMMING_NEXT, semester = Semester.FALL_OPERATIONAL_NEXT,
            organization = "Robotics & <Friends>", eventName = "Movie \"Night\" — été 🎬",
            eventDate = "Fall 2030", location = "Textor 102", tripSize = "", expectedAttendance = "40",
            heldBefore = true, pastAttendance = "35", pastCost = "250.5", usesCollegeServices = true,
            collegeServices = setOf(CollegeService.PRINT_SHOP, CollegeService.CCES), hasAdditionalFunding = true,
        ),
        items = listOf(
            LineItem(3, ItemType.FOOD, "Pizza", "Wegmans", "line1\nline2", "4", "25.00"),
            LineItem(7, null, "", "", "", "", "half typed $"),
        ),
        funding = listOf(FundingSource(9, "Dept", "because", FundingStatus.APPROVED, "1,200")),
        docs = listOf(
            SupportingDoc(11, "quote.png", png(), ImageFormat.PNG, setOf(3, 7), "page 2"),
            SupportingDoc(12, "receipt.jpg", byteArrayOf(1, 2, 3, 4, 5), ImageFormat.JPEG),
        ),
    )

    private fun bytesOf(d: Draft) = ByteArrayOutputStream().also { DraftFile.write(it, d) }.toByteArray()

    private fun zipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val m = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; m[e.name] = z.readBytes() } }
        return m
    }

    private fun zipOf(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bos ->
        ZipOutputStream(bos).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }.toByteArray()

    private fun assertSameRequest(a: BudgetRequest, b: BudgetRequest) {
        assertEquals(a.header, b.header)
        assertEquals(a.items, b.items)
        assertEquals(a.funding, b.funding)
        assertEquals(a.docs.size, b.docs.size)
        a.docs.zip(b.docs).forEach { (x, y) ->
            assertEquals(x.id, y.id); assertEquals(x.fileName, y.fileName); assertEquals(x.format, y.format)
            assertEquals(x.itemIds, y.itemIds); assertEquals(x.note, y.note)
            assertContentEquals(x.bytes, y.bytes)
        }
    }

    @Test
    fun roundTripPreservesEverything() {
        val r = sample()
        val back = DraftFile.read(bytesOf(Draft(r, Step.FUNDING)).inputStream())
        assertSameRequest(r, back.request)
        assertEquals(Step.FUNDING, back.step)
        assertNull(back.originalFile)
    }

    @Test
    fun roundTripThroughFileIsAtomicAndLeavesNoTempFiles() {
        val dir = Files.createTempDirectory("drafttest").toFile()
        try {
            val f = File(dir, "x.budget")
            DraftFile.write(f, Draft(sample()))
            DraftFile.write(f, Draft(sample(), originalFile = File("/tmp/orig.budget"))) // overwrite
            assertEquals(listOf("x.budget"), dir.list()!!.toList())
            val back = DraftFile.read(f)
            assertSameRequest(sample(), back.request)
            assertEquals(File("/tmp/orig.budget"), back.originalFile)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun imagesAreSeparateEntriesNotBase64() {
        val e = zipEntries(bytesOf(Draft(sample())))
        assertEquals(setOf("request.json", "docs/11.png", "docs/12.jpeg"), e.keys)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), e["docs/12.jpeg"])
        val json = e.getValue("request.json").toString(Charsets.UTF_8)
        assertContains(json, "\"formatVersion\": 1")
        assertContains(json, "\"PROGRAMMING_NEXT\"") // enums by stable name, not label/ordinal
        assertTrue(json.length < 5000)
    }

    @Test
    fun emptyRequestRoundTrips() {
        val back = DraftFile.read(bytesOf(Draft(BudgetRequest())).inputStream())
        assertSameRequest(BudgetRequest(), back.request)
        assertNull(back.step)
    }

    @Test
    fun toleratesUnknownFieldsEntriesAndEnumValues() {
        val json = """
            {"formatVersion": 1, "futureTopLevel": {"a": [1,2]},
             "header": {"type": "PROGRAMMING", "semester": "SUMMER_FROM_THE_FUTURE", "organization": "Org",
                        "collegeServices": ["CCES", "DRONES"], "newField": true},
             "items": [{"id": 5, "type": "FOOD", "name": "Pizza", "colour": "red"}],
             "docs": [{"id": 6, "fileName": "a.png", "format": "PNG", "entry": "docs/6.png", "itemIds": [5], "crop": {}}]}
        """.trimIndent()
        val bytes = zipOf(mapOf("request.json" to json.toByteArray(), "docs/6.png" to png(), "thumbnails/6.png" to byteArrayOf(0)))
        val d = DraftFile.read(bytes.inputStream())
        assertEquals("Org", d.request.header.organization)
        assertEquals(BudgetType.PROGRAMMING, d.request.header.type)
        assertNull(d.request.header.semester)
        assertEquals(setOf(CollegeService.CCES), d.request.header.collegeServices)
        assertEquals(LineItem(5, ItemType.FOOD, "Pizza"), d.request.items.single())
        assertEquals(setOf(5L), d.request.docs.single().itemIds)
        assertTrue(d.request.funding.isEmpty())
    }

    @Test
    fun rejectsNewerFormatVersionWithClearMessage() {
        val bytes = zipOf(mapOf("request.json" to """{"formatVersion": 2, "header": {"organization": 5}}""".toByteArray()))
        val e = assertFailsWith<DraftFormatException> { DraftFile.read(bytes.inputStream()) }
        assertContains(e.message!!, "newer version of Budgetinator")
    }

    @Test
    fun rejectsNonDraftsAndMissingImages() {
        assertFailsWith<DraftFormatException> { DraftFile.read("hello, not a zip".toByteArray().inputStream()) }
        assertFailsWith<DraftFormatException> { DraftFile.read(zipOf(mapOf("other.txt" to byteArrayOf(1))).inputStream()) }
        assertFailsWith<DraftFormatException> { DraftFile.read(zipOf(mapOf("request.json" to "{not json".toByteArray())).inputStream()) }
        val missing = """{"formatVersion":1,"docs":[{"id":1,"format":"PNG","entry":"docs/1.png"}]}"""
        assertFailsWith<DraftFormatException> { DraftFile.read(zipOf(mapOf("request.json" to missing.toByteArray())).inputStream()) }
    }

    @Test
    fun defaultFileNameAndExtension() {
        assertEquals("Robotics - Movie Night.budget",
            DraftFile.defaultFileName(BudgetRequest(Header(organization = "Robotics", eventName = "Movie Night"))))
        assertEquals("Budget Request.budget", DraftFile.defaultFileName(BudgetRequest()))
        assertEquals("a: b.budget".replace(":", ""), DraftFile.defaultFileName(BudgetRequest(Header(organization = "a: b"))))
        assertEquals(File("/x/y.budget"), DraftFile.withExtension(File("/x/y")))
        assertEquals(File("/x/y.budget"), DraftFile.withExtension(File("/x/y.budget")))
    }

    @Test
    fun loadBumpsIdsPastLoadedOnes() {
        val store = BudgetStore()
        store.load(sample(), Step.ITEMS, File("/tmp/x.budget"))
        assertEquals(Step.ITEMS, store.step)
        assertEquals(setOf(Step.BASICS, Step.DETAILS), store.visited)
        store.addItem(); store.addFunding()
        val newIds = listOf(store.request.items.last().id, store.request.funding.last().id)
        assertTrue(newIds.all { it > 12 }, "new ids $newIds must be past the max loaded id 12")
        assertEquals(newIds.size, newIds.toSet().size)
    }

    @Test
    fun dirtyAndEditedTracking() {
        val store = BudgetStore()
        assertFalse(store.isDirty); assertFalse(store.isEdited)
        store.updateHeader { it.copy(organization = "Org") }
        assertTrue(store.isDirty); assertTrue(store.isEdited)

        store.markSaved(File("/tmp/a.budget"))
        assertFalse(store.isDirty); assertFalse(store.isEdited)
        assertEquals(File("/tmp/a.budget"), store.currentFile)

        store.updateHeader { it.copy(eventName = "E") }
        store.markExported()
        assertFalse(store.isDirty, "an export counts as safe for quitting")
        assertTrue(store.isEdited, "but the draft file is still behind")

        store.load(sample(), file = File("/tmp/b.budget"))
        assertFalse(store.isDirty); assertFalse(store.isEdited)
        store.load(sample(), edited = true)
        assertTrue(store.isDirty, "a recovered autosave is unsaved"); assertNull(store.currentFile)

        store.newRequest()
        assertFalse(store.isDirty); assertNull(store.currentFile)
        assertEquals(Step.BASICS, store.step); assertTrue(store.visited.isEmpty())
        assertEquals(1, store.request.items.size)
        assertTrue(store.request.docs.isEmpty())
    }

    @Test
    fun autosaveShutdownDeletesAndBlocksLateWrites() {
        val dir = Files.createTempDirectory("autosavetest").toFile()
        try {
            val a = Autosave(File(dir, "sub/autosave.budget"))
            assertNull(a.read())
            a.write(Draft(sample(), Step.DOCS))
            assertTrue(a.exists())
            assertEquals(Step.DOCS, a.read()!!.step)
            a.shutdown()
            assertFalse(a.exists())
            a.write(Draft(sample()))
            assertFalse(a.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun corruptAutosaveIsDiscarded() {
        val dir = Files.createTempDirectory("autosavetest").toFile()
        try {
            val f = File(dir, "autosave.budget").apply { writeText("garbage") }
            assertNull(Autosave(f).read())
            assertFalse(f.exists())
        } finally { dir.deleteRecursively() }
    }
}
