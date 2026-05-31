package kestrel.persistence

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class Persistence(private val dataDir: File) {
    private var generation: Int

    init {
        dataDir.mkdirs()
        generation = readManifest() ?: run { writeManifest(0); 0 }
    }

    fun load(): String {
        val snapshot = snapshotFile(generation).let { if (it.exists()) it.readText() else "" }
        val wal = walFile(generation).let { if (it.exists()) it.readText() else "" }
        return buildString {
            append(snapshot)
            if (snapshot.isNotEmpty() && !snapshot.endsWith("\n")) append('\n')
            append(wal)
        }
    }

    fun appendWal(statements: List<String>) {
        if (statements.isEmpty()) return
        FileOutputStream(walFile(generation), true).use { output ->
            output.write(statements.joinToString("\n", postfix = "\n").toByteArray())
            output.flush()
            output.fd.sync()
        }
    }

    fun checkpoint(dumpSql: String) {
        val next = generation + 1
        writeSynced(snapshotFile(next), dumpSql)
        writeSynced(walFile(next), "")
        writeManifest(next)
        snapshotFile(generation).delete()
        walFile(generation).delete()
        generation = next
    }

    private fun manifestFile() = File(dataDir, "manifest")

    private fun snapshotFile(gen: Int) = File(dataDir, "snapshot.$gen.sql")

    private fun walFile(gen: Int) = File(dataDir, "wal.$gen.log")

    private fun readManifest(): Int? =
        manifestFile().takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()

    private fun writeManifest(gen: Int) {
        val temp = File(dataDir, "manifest.tmp")
        writeSynced(temp, gen.toString())
        Files.move(
            temp.toPath(),
            manifestFile().toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )
    }

    private fun writeSynced(file: File, text: String) {
        FileOutputStream(file).use { output ->
            output.write(text.toByteArray())
            output.flush()
            output.fd.sync()
        }
    }
}
