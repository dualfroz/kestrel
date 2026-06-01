package kestrel.cli

import java.io.File
import kestrel.persistence.Persistence

fun main(arguments: Array<String>) {
    var dataDir: File? = null
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
            else -> {
                scriptPath = argument; index += 1
            }
        }
    }
    val persistence = dataDir?.let { Persistence(it) }
    val repl = Repl(persistence = persistence)
    val reader = if (scriptPath != null) File(scriptPath).bufferedReader() else System.`in`.bufferedReader()
    reader.use { repl.run(it) }
}
