package ph.agritourism.tools

import ph.agritourism.data.Database
import java.io.File

/**
 * Creates a pre-seeded SQLite database that ships with release builds.
 *
 * The app normally seeds `~/.agritourism/agritourism.db` on first launch; this
 * entry point produces the same database ahead of time so it can be attached
 * to a GitHub release (and opened with any SQLite client).
 *
 * Usage: SeedDatabaseKt <output.db>
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: SeedDatabaseKt <output.db>" }
    val out = File(args[0]).absoluteFile
    out.parentFile?.mkdirs()

    listOf(out, File("${out.path}-wal"), File("${out.path}-shm")).forEach { it.delete() }

    val db = Database(out)
    db.conn.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
    db.close()

    listOf(File("${out.path}-wal"), File("${out.path}-shm")).forEach { if (it.length() == 0L) it.delete() }
    println("Seeded database written to ${out.absolutePath} (${out.length()} bytes)")
}
