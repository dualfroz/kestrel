package kestrel.cli

import java.io.File
import kestrel.persistence.Persistence
import kestrel.store.sql.DiskCatalog

fun main(arguments: Array<String>) {
    var dataDir: File? = null
    var diskDir: File? = null
    var scriptPath: String? = null
    var index = 0
    while (index < arguments.size) {
        val argument = arguments[index]
        when {
            argument == "--data" -> {
                dataDir = File(arguments[index + 1]); index += 2
            }
            argument.startsWith("--data=") -> {
                dataDir = File(argument.substringAfter('=')); index += 1
            }
            argument == "--disk" -> {
                diskDir = File(arguments[index + 1]); index += 2
            }
            argument.startsWith("--disk=") -> {
                diskDir = File(argument.substringAfter('=')); index += 1
            }
            else -> {
                scriptPath = argument; index += 1
            }
        }
    }
    if (dataDir != null && diskDir != null) {
        System.err.println("Error: --data (in-memory engine, text WAL) and --disk (disk-backed engine) are mutually exclusive")
        return
    }
    val persistence = dataDir?.let { Persistence(it) }
    val diskCatalog = diskDir?.let {
        it.mkdirs()
        DiskCatalog.open(File(it, "kestrel.data"), File(it, "kestrel.wal"))
    }
    val repl = Repl(persistence = persistence, diskCatalog = diskCatalog)
    val reader = if (scriptPath != null) File(scriptPath).bufferedReader() else System.`in`.bufferedReader()
    reader.use { repl.run(it) }
}
