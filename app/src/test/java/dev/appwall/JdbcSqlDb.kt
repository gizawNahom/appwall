package dev.appwall

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/**
 * [SqlDb] on SQLite through JDBC, in memory, for tests. Binds like [AndroidSqlDb]: statements
 * get typed arguments, queries get text (Android's rawQuery only takes strings), so the SQL is
 * tested the way the phone runs it.
 */
class JdbcSqlDb : SqlDb, AutoCloseable {
    val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    override fun execute(sql: String, args: List<Any?>) {
        connection.prepareStatement(sql).use { it.bindTyped(args).executeUpdate() }
    }

    override fun insert(sql: String, args: List<Any?>): Long {
        connection.prepareStatement(sql).use { it.bindTyped(args).executeUpdate() }
        return connection.createStatement().use { s -> s.executeQuery("SELECT last_insert_rowid()").use { it.next(); it.getLong(1) } }
    }

    override fun <T> query(sql: String, args: List<Any?>, row: (SqlRow) -> T): List<T> =
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { i, arg -> statement.setString(i + 1, requireNotNull(arg).toString()) }
            statement.executeQuery().use { results ->
                val reader = ResultRow(results)
                val list = ArrayList<T>()
                while (results.next()) list += row(reader)
                list
            }
        }

    override fun close() = connection.close()

    private fun PreparedStatement.bindTyped(args: List<Any?>): PreparedStatement = apply {
        args.forEachIndexed { i, arg ->
            when (arg) {
                null -> setNull(i + 1, Types.NULL)
                is Long -> setLong(i + 1, arg)
                is Int -> setLong(i + 1, arg.toLong())
                is String -> setString(i + 1, arg)
                else -> error("Unsupported SQL argument: ${arg::class}")
            }
        }
    }

    private class ResultRow(private val results: ResultSet) : SqlRow {
        override fun long(column: Int) = results.getLong(column + 1)
        override fun int(column: Int) = results.getInt(column + 1)
        override fun string(column: Int): String? = results.getString(column + 1)
        override fun isNull(column: Int) = results.getObject(column + 1) == null
    }
}
