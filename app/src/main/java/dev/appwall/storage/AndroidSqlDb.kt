package dev.appwall.storage

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement

/**
 * [SqlDb] on Android's SQLite, for one database file. The only class that uses android.database;
 * the schema comes from [onCreate], [onUpgrade] and [onDowngrade], so the SQL itself lives with
 * the code that uses it. Each runs inside the helper's own transaction.
 */
class AndroidSqlDb(
    context: Context,
    name: String,
    version: Int,
    private val onCreate: (SqlDb) -> Unit,
    private val onUpgrade: (SqlDb, from: Int, to: Int) -> Unit,
    private val onDowngrade: (SqlDb) -> Unit,
) : SqlDb {

    private val helper = object : SQLiteOpenHelper(context, name, null, version) {
        override fun onCreate(db: SQLiteDatabase) = onCreate(Connection(db))
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = onUpgrade(Connection(db), oldVersion, newVersion)
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = onDowngrade(Connection(db))
    }

    private val db: SqlDb get() = Connection(helper.writableDatabase)

    override fun execute(sql: String, args: List<Any?>) = db.execute(sql, args)
    override fun insert(sql: String, args: List<Any?>) = db.insert(sql, args)
    override fun <T> query(sql: String, args: List<Any?>, row: (SqlRow) -> T) = db.query(sql, args, row)
    override fun <T> transaction(work: () -> T) = db.transaction(work)

    /** An open database; also what [onCreate] gets, before the helper finishes opening. */
    private class Connection(private val db: SQLiteDatabase) : SqlDb {
        override fun execute(sql: String, args: List<Any?>) {
            db.compileStatement(sql).use { it.bindAll(args).execute() }
        }

        override fun insert(sql: String, args: List<Any?>): Long =
            db.compileStatement(sql).use { it.bindAll(args).executeInsert() }

        override fun <T> query(sql: String, args: List<Any?>, row: (SqlRow) -> T): List<T> =
            // Never null: match nulls with IS NULL in the SQL instead.
            db.rawQuery(sql, args.map { requireNotNull(it).toString() }.toTypedArray()).use { cursor ->
                val reader = CursorRow(cursor)
                val result = ArrayList<T>(cursor.count)
                while (cursor.moveToNext()) result += row(reader)
                result
            }

        override fun <T> transaction(work: () -> T): T {
            db.beginTransaction()
            try {
                return work().also { db.setTransactionSuccessful() }
            } finally {
                db.endTransaction()
            }
        }

        private fun SQLiteStatement.bindAll(args: List<Any?>): SQLiteStatement = apply {
            args.forEachIndexed { i, arg ->
                when (arg) {
                    null -> bindNull(i + 1)
                    is Long -> bindLong(i + 1, arg)
                    is Int -> bindLong(i + 1, arg.toLong())
                    is String -> bindString(i + 1, arg)
                    else -> error("Unsupported SQL argument: ${arg::class}")
                }
            }
        }
    }

    private class CursorRow(private val cursor: Cursor) : SqlRow {
        override fun long(column: Int) = cursor.getLong(column)
        override fun int(column: Int) = cursor.getInt(column)
        override fun string(column: Int): String? = cursor.getString(column)
        override fun isNull(column: Int) = cursor.isNull(column)
    }
}
