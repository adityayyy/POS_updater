/*
 * Copyright (C) 2017-2022 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.pixelos.ota

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.BaseColumns
import net.pixelos.ota.model.Update
import java.io.File

class UpdatesDbHelper(context: Context?) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_ENTRIES)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL(SQL_DELETE_ENTRIES)
        onCreate(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onUpgrade(db, oldVersion, newVersion)
    }

    fun addUpdateWithOnConflict(update: Update, conflictAlgorithm: Int) {
        val db = writableDatabase
        val values = ContentValues()
        fillContentValues(update, values)
        db.insertWithOnConflict(UpdateEntry.TABLE_NAME, null, values, conflictAlgorithm)
    }

    fun removeUpdate(downloadId: String) {
        val db = writableDatabase
        val selection = UpdateEntry.COLUMN_NAME_DOWNLOAD_ID + " = ?"
        val selectionArgs = arrayOf(downloadId)
        db.delete(UpdateEntry.TABLE_NAME, selection, selectionArgs)
    }

    fun changeUpdateStatus(update: Update) {
        val selection = UpdateEntry.COLUMN_NAME_DOWNLOAD_ID + " = ?"
        val selectionArgs = arrayOf(update.downloadId)
        changeUpdateStatus(selection, selectionArgs, update.persistentStatus)
    }

    private fun changeUpdateStatus(selection: String, selectionArgs: Array<String>, status: Int) {
        val db = writableDatabase
        val values = ContentValues()
        values.put(UpdateEntry.COLUMN_NAME_STATUS, status)
        db.update(UpdateEntry.TABLE_NAME, values, selection, selectionArgs)
    }

    val updates: List<Update>
        get() = getUpdates(null, null)

    fun getUpdates(selection: String?, selectionArgs: Array<String?>?): List<Update> {
        val db = readableDatabase
        val projection =
            arrayOf(
                UpdateEntry.COLUMN_NAME_PATH,
                UpdateEntry.COLUMN_NAME_DOWNLOAD_ID,
                UpdateEntry.COLUMN_NAME_TIMESTAMP,
                UpdateEntry.COLUMN_NAME_VERSION,
                UpdateEntry.COLUMN_NAME_STATUS,
                UpdateEntry.COLUMN_NAME_SIZE,
            )
        val sort = UpdateEntry.COLUMN_NAME_TIMESTAMP + " DESC"
        val cursor =
            db.query(UpdateEntry.TABLE_NAME, projection, selection, selectionArgs, null, null, sort)
        val updates: MutableList<Update> = ArrayList()
        while (cursor.moveToNext()) {
            val update = Update()
            var index = cursor.getColumnIndex(UpdateEntry.COLUMN_NAME_PATH)
            update.file = File(cursor.getString(index))
            update.name = update.file.name
            index = cursor.getColumnIndex(UpdateEntry.COLUMN_NAME_DOWNLOAD_ID)
            update.downloadId = cursor.getString(index)
            index = cursor.getColumnIndex(UpdateEntry.COLUMN_NAME_TIMESTAMP)
            update.timestamp = cursor.getLong(index)
            index = cursor.getColumnIndex(UpdateEntry.COLUMN_NAME_VERSION)
            update.version = cursor.getString(index)
            index = cursor.getColumnIndex(UpdateEntry.COLUMN_NAME_STATUS)
            update.persistentStatus = cursor.getInt(index)
            index = cursor.getColumnIndex(UpdateEntry.COLUMN_NAME_SIZE)
            update.fileSize = cursor.getLong(index)
            updates.add(update)
        }
        cursor.close()
        return updates
    }

    object UpdateEntry : BaseColumns {
        const val TABLE_NAME: String = "updates"
        const val COLUMN_NAME_STATUS: String = "status"
        const val COLUMN_NAME_PATH: String = "path"
        const val COLUMN_NAME_DOWNLOAD_ID: String = "download_id"
        const val COLUMN_NAME_TIMESTAMP: String = "timestamp"
        const val COLUMN_NAME_VERSION: String = "version"
        const val COLUMN_NAME_SIZE: String = "size"
    }

    companion object {
        const val DATABASE_VERSION: Int = 1
        const val DATABASE_NAME: String = "updates.db"
        private const val SQL_CREATE_ENTRIES =
            "CREATE TABLE " +
                    UpdateEntry.TABLE_NAME +
                    " (" +
                    BaseColumns._ID +
                    " INTEGER PRIMARY KEY," +
                    UpdateEntry.COLUMN_NAME_STATUS +
                    " INTEGER," +
                    UpdateEntry.COLUMN_NAME_PATH +
                    " TEXT," +
                    UpdateEntry.COLUMN_NAME_DOWNLOAD_ID +
                    " TEXT NOT NULL UNIQUE," +
                    UpdateEntry.COLUMN_NAME_TIMESTAMP +
                    " INTEGER," +
                    UpdateEntry.COLUMN_NAME_VERSION +
                    " TEXT," +
                    UpdateEntry.COLUMN_NAME_SIZE +
                    " INTEGER)"
        private const val SQL_DELETE_ENTRIES = "DROP TABLE IF EXISTS " + UpdateEntry.TABLE_NAME

        private fun fillContentValues(update: Update, values: ContentValues) {
            values.put(UpdateEntry.COLUMN_NAME_STATUS, update.persistentStatus)
            values.put(UpdateEntry.COLUMN_NAME_PATH, update.file.absolutePath)
            values.put(UpdateEntry.COLUMN_NAME_DOWNLOAD_ID, update.downloadId)
            values.put(UpdateEntry.COLUMN_NAME_TIMESTAMP, update.timestamp)
            values.put(UpdateEntry.COLUMN_NAME_VERSION, update.version)
            values.put(UpdateEntry.COLUMN_NAME_SIZE, update.fileSize)
        }
    }
}
