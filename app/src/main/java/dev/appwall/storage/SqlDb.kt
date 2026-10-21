package dev.appwall.storage

/**
 * Just enough of a SQL database for the block log: run statements and read rows. On the phone
 * it's Android's SQLite ([AndroidSqlDb]); in tests, SQLite on the JVM, so the log's SQL is tested
 * without a phone.
 *
 * Arguments are Long, Int, String or null. Query arguments are passed to SQLite as text, as
 * Android's rawQuery does, and SQLite converts them where a column needs a number.
 */
interface SqlDb {
    fun execute(sql: String, args: List<Any?> = emptyList())

    /** Runs an INSERT and returns the new row's id. */
    fun insert(sql: String, args: List<Any?>): Long

    fun <T> query(sql: String, args: List<Any?> = emptyList(), row: (SqlRow) -> T): List<T>

    /** Runs [work] as one transaction: all of its writes are kept, or (if it throws) none. */
    fun <T> transaction(work: () -> T): T
}

/** One result row; columns by position, from 0. */
interface SqlRow {
    fun long(column: Int): Long
    fun int(column: Int): Int
    fun string(column: Int): String?
    fun isNull(column: Int): Boolean
}
