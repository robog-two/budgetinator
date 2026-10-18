package net.robog.budgetinator.persist

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.robog.budgetinator.model.*
import net.robog.budgetinator.validation.Step
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Thrown when a draft can't be opened; [message] is written for the student, not a developer. */
class DraftFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What a draft file holds besides the request itself. */
data class Draft(
    val request: BudgetRequest,
    val step: Step? = null,
    /** Autosave only: the .budget file the work belonged to, so recovery can re-associate it. */
    val originalFile: File? = null,
)

/**
 * The `.budget` draft format: a zip with `request.json` (see [DraftDto]) plus one stored entry per
 * supporting-document image under `docs/`. Images stay as raw bytes so the JSON stays small and
 * the archive can be inspected with any unzip tool.
 *
 * Versioning: [FORMAT_VERSION] is bumped only for changes old readers can't safely ignore. Adding
 * optional fields does not need a bump (readers ignore unknown keys); files with a newer version
 * are rejected with a "please update" message instead of being half-read.
 */
object DraftFile {
    const val EXTENSION = "budget"
    const val FORMAT_VERSION = 1
    private const val JSON_ENTRY = "request.json"
    private const val MAX_JSON_BYTES = 16L * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 64L * 1024 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    /** "Org - Event.budget", mirroring the export's naming. */
    fun defaultFileName(r: BudgetRequest): String {
        val base = listOf(r.header.organization, r.header.eventName).filter { it.isNotBlank() }.joinToString(" - ")
            .replace(Regex("""[\\/:*?"<>|]"""), "").trim().ifBlank { "Budget Request" }
        return "$base.$EXTENSION"
    }

    fun withExtension(f: File): File = if (f.extension.equals(EXTENSION, ignoreCase = true)) f else File(f.parentFile, "${f.name}.$EXTENSION")

    // ---------------------------------------------------------------- write

    /** Writes atomically: a temp file in the same directory, then a rename over [file]. */
    fun write(file: File, draft: Draft) {
        val dir = file.absoluteFile.parentFile ?: error("No parent directory for $file")
        dir.mkdirs()
        val tmp = File.createTempFile(".${file.name}.", ".tmp", dir)
        try {
            tmp.outputStream().buffered().use { write(it, draft) }
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tmp.delete()
        }
    }

    fun write(out: OutputStream, draft: Draft) {
        val r = draft.request
        val docEntries = r.docs.associate { it.id to "docs/${it.id}.${it.format.ext}" }
        val dto = DraftDto(
            formatVersion = FORMAT_VERSION,
            savedAt = Instant.now().toString(),
            step = draft.step?.name,
            originalFile = draft.originalFile?.absolutePath,
            header = r.header.toDto(),
            items = r.items.map { it.toDto() },
            funding = r.funding.map { it.toDto() },
            docs = r.docs.map { it.toDto(docEntries.getValue(it.id)) },
        )
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(JSON_ENTRY))
            zip.write(json.encodeToString(DraftDto.serializer(), dto).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (d in r.docs) {
                // Images are already compressed; STORED avoids burning CPU on every autosave.
                val crc = CRC32().apply { update(d.bytes) }
                zip.putNextEntry(ZipEntry(docEntries.getValue(d.id)).apply {
                    method = ZipEntry.STORED
                    size = d.bytes.size.toLong()
                    compressedSize = size
                    this.crc = crc.value
                })
                zip.write(d.bytes)
                zip.closeEntry()
            }
        }
    }

    // ---------------------------------------------------------------- read

    fun read(file: File): Draft = try {
        file.inputStream().buffered().use { read(it) }
    } catch (e: DraftFormatException) {
        throw e
    } catch (e: java.io.IOException) {
        throw DraftFormatException("${file.name} couldn’t be read: ${e.message}", e)
    }

    fun read(input: InputStream): Draft {
        var jsonBytes: ByteArray? = null
        val blobs = HashMap<String, ByteArray>()
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    when {
                        e.name == JSON_ENTRY -> jsonBytes = zip.readCapped(MAX_JSON_BYTES)
                        e.name.startsWith("docs/") -> blobs[e.name] = zip.readCapped(MAX_IMAGE_BYTES)
                        // anything else: written by a newer version, ignore
                    }
                }
            }
        } catch (e: java.util.zip.ZipException) {
            throw DraftFormatException("This isn’t a Budgetinator draft (or it’s damaged).", e)
        }
        val text = jsonBytes?.toString(Charsets.UTF_8)
            ?: throw DraftFormatException("This isn’t a Budgetinator draft (or it’s damaged).")

        val version = try {
            json.parseToJsonElement(text).jsonObject["formatVersion"]?.jsonPrimitive?.intOrNull
        } catch (e: Exception) {
            throw DraftFormatException("This draft is damaged and can’t be opened.", e)
        } ?: throw DraftFormatException("This isn’t a Budgetinator draft (no format version).")
        if (version > FORMAT_VERSION) throw DraftFormatException(
            "This draft was saved by a newer version of Budgetinator (file format $version). " +
                "Please update Budgetinator to open it."
        )
        if (version < 1) throw DraftFormatException("This draft has an unknown file format ($version).")

        val dto = try {
            json.decodeFromString(DraftDto.serializer(), text)
        } catch (e: Exception) {
            throw DraftFormatException("This draft is damaged and can’t be opened.", e)
        }

        val docs = dto.docs.map { d ->
            val bytes = blobs[d.entry] ?: throw DraftFormatException("The draft is missing the image “${d.fileName}”.")
            val format = enumOrNull<ImageFormat>(d.format)
                ?: ImageFormat.entries.firstOrNull { it.ext.equals(d.format, true) || it.mime.equals(d.format, true) }
                ?: throw DraftFormatException("“${d.fileName}” uses an image format this version can’t handle (${d.format}).")
            SupportingDoc(d.id, d.fileName, bytes, format, d.itemIds.toSet(), d.note)
        }
        return Draft(
            request = BudgetRequest(
                header = dto.header.toModel(),
                items = dto.items.map { it.toModel() },
                funding = dto.funding.map { it.toModel() },
                docs = docs,
            ),
            step = enumOrNull<Step>(dto.step),
            originalFile = dto.originalFile?.let(::File),
        )
    }

    private fun InputStream.readCapped(max: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw DraftFormatException("This draft contains an entry that is too large to open.")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

/** Enums are stored by their constant name; unknown names (e.g. from a newer version) read as null. */
private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } }

// -------------------------------------------------------------------- DTOs (format v1)
// Deliberately separate from the Compose-facing model so either can change independently.
// Every field has a default so missing keys never fail a load.

@Serializable
internal data class DraftDto(
    val formatVersion: Int,
    val app: String = "Budgetinator",
    val savedAt: String? = null,
    val step: String? = null,
    val originalFile: String? = null,
    val header: HeaderDto = HeaderDto(),
    val items: List<ItemDto> = emptyList(),
    val funding: List<FundingDto> = emptyList(),
    val docs: List<DocDto> = emptyList(),
)

@Serializable
internal data class HeaderDto(
    val type: String? = null,
    val semester: String? = null,
    val organization: String = "",
    val eventName: String = "",
    val eventDate: String = "",
    val location: String = "",
    val tripSize: String = "",
    val expectedAttendance: String = "",
    val heldBefore: Boolean? = null,
    val pastAttendance: String = "",
    val pastCost: String = "",
    val usesCollegeServices: Boolean? = null,
    val collegeServices: List<String> = emptyList(),
    val hasAdditionalFunding: Boolean? = null,
)

@Serializable
internal data class ItemDto(
    val id: Long,
    val type: String? = null,
    val name: String = "",
    val location: String = "",
    val reasoning: String = "",
    val quantity: String = "",
    val unitPrice: String = "",
)

@Serializable
internal data class FundingDto(
    val id: Long,
    val source: String = "",
    val rationale: String = "",
    val status: String? = null,
    val amount: String = "",
)

@Serializable
internal data class DocDto(
    val id: Long,
    val fileName: String = "",
    val format: String,
    /** Zip entry holding the image bytes. */
    val entry: String,
    val itemIds: List<Long> = emptyList(),
    val note: String = "",
)

private fun Header.toDto() = HeaderDto(
    type?.name, semester?.name, organization, eventName, eventDate, location, tripSize, expectedAttendance,
    heldBefore, pastAttendance, pastCost, usesCollegeServices, collegeServices.map { it.name }, hasAdditionalFunding,
)

private fun HeaderDto.toModel() = Header(
    type = enumOrNull<BudgetType>(type),
    semester = enumOrNull<Semester>(semester),
    organization = organization, eventName = eventName, eventDate = eventDate, location = location,
    tripSize = tripSize, expectedAttendance = expectedAttendance, heldBefore = heldBefore,
    pastAttendance = pastAttendance, pastCost = pastCost, usesCollegeServices = usesCollegeServices,
    collegeServices = collegeServices.mapNotNull { enumOrNull<CollegeService>(it) }.toSet(),
    hasAdditionalFunding = hasAdditionalFunding,
)

private fun LineItem.toDto() = ItemDto(id, type?.name, name, location, reasoning, quantity, unitPrice)
private fun ItemDto.toModel() = LineItem(id, enumOrNull<ItemType>(type), name, location, reasoning, quantity, unitPrice)

private fun FundingSource.toDto() = FundingDto(id, source, rationale, status?.name, amount)
private fun FundingDto.toModel() = FundingSource(id, source, rationale, enumOrNull<FundingStatus>(status), amount)

private fun SupportingDoc.toDto(entry: String) = DocDto(id, fileName, format.name, entry, itemIds.sorted(), note)
