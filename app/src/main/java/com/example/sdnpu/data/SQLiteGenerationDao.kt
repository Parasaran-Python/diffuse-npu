package com.example.sdnpu.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SQLiteGenerationDao(
    context: Context,
    dbName: String = DATABASE_NAME
) : SQLiteOpenHelper(context, dbName, null, DATABASE_VERSION), GenerationDao {

    companion object {
        const val DATABASE_NAME = "sd_generations.db"
        const val DATABASE_VERSION = 1
        const val TABLE_NAME = "generations"

        const val COLUMN_ID = "id"
        const val COLUMN_PROMPT = "prompt"
        const val COLUMN_NEGATIVE_PROMPT = "negative_prompt"
        const val COLUMN_MODEL_NAME = "model_name"
        const val COLUMN_IMAGE_PATH = "image_path"
        const val COLUMN_SEED = "seed"
        const val COLUMN_STEPS = "steps"
        const val COLUMN_CFG_SCALE = "cfg_scale"
        const val COLUMN_SAMPLER = "sampler"
        const val COLUMN_UPSCALE_MODE = "upscale_mode"
        const val COLUMN_TIMESTAMP = "timestamp"
        const val COLUMN_GENERATION_TIME_MS = "generation_time_ms"
        const val COLUMN_WIDTH = "width"
        const val COLUMN_HEIGHT = "height"
        const val COLUMN_FILE_SIZE_BYTES = "file_size_bytes"

        private const val CREATE_TABLE_GENERATIONS = """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_PROMPT TEXT NOT NULL,
                $COLUMN_NEGATIVE_PROMPT TEXT NOT NULL DEFAULT '',
                $COLUMN_MODEL_NAME TEXT NOT NULL DEFAULT 'dreamshaper_v8',
                $COLUMN_IMAGE_PATH TEXT NOT NULL,
                $COLUMN_SEED INTEGER NOT NULL DEFAULT 0,
                $COLUMN_STEPS INTEGER NOT NULL DEFAULT 20,
                $COLUMN_CFG_SCALE REAL NOT NULL DEFAULT 7.0,
                $COLUMN_SAMPLER INTEGER NOT NULL DEFAULT 0,
                $COLUMN_UPSCALE_MODE INTEGER NOT NULL DEFAULT 0,
                $COLUMN_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_GENERATION_TIME_MS INTEGER NOT NULL DEFAULT 0,
                $COLUMN_WIDTH INTEGER NOT NULL DEFAULT 512,
                $COLUMN_HEIGHT INTEGER NOT NULL DEFAULT 512,
                $COLUMN_FILE_SIZE_BYTES INTEGER NOT NULL DEFAULT 0
            )
        """

        private const val CREATE_INDEX_TIMESTAMP = """
            CREATE INDEX IF NOT EXISTS idx_generations_timestamp ON $TABLE_NAME ($COLUMN_TIMESTAMP DESC)
        """

        private const val CREATE_INDEX_PROMPT = """
            CREATE INDEX IF NOT EXISTS idx_generations_prompt ON $TABLE_NAME ($COLUMN_PROMPT)
        """
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_TABLE_GENERATIONS)
        db.execSQL(CREATE_INDEX_TIMESTAMP)
        db.execSQL(CREATE_INDEX_PROMPT)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }

    override fun insert(entity: GenerationEntity): Long {
        val values = ContentValues().apply {
            put(COLUMN_PROMPT, entity.prompt)
            put(COLUMN_NEGATIVE_PROMPT, entity.negativePrompt)
            put(COLUMN_MODEL_NAME, entity.modelName)
            put(COLUMN_IMAGE_PATH, entity.imagePath)
            put(COLUMN_SEED, entity.seed)
            put(COLUMN_STEPS, entity.steps)
            put(COLUMN_CFG_SCALE, entity.cfgScale)
            put(COLUMN_SAMPLER, entity.sampler)
            put(COLUMN_UPSCALE_MODE, entity.upscaleMode)
            put(COLUMN_TIMESTAMP, entity.timestamp)
            put(COLUMN_GENERATION_TIME_MS, entity.generationTimeMs)
            put(COLUMN_WIDTH, entity.width)
            put(COLUMN_HEIGHT, entity.height)
            put(COLUMN_FILE_SIZE_BYTES, entity.fileSizeBytes)
        }
        return writableDatabase.insert(TABLE_NAME, null, values)
    }

    override fun getAll(): List<GenerationEntity> {
        val list = mutableListOf<GenerationEntity>()
        val cursor = readableDatabase.query(
            TABLE_NAME,
            null,
            null,
            null,
            null,
            null,
            "$COLUMN_TIMESTAMP DESC"
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(cursorToEntity(c))
            }
        }
        return list
    }

    override fun getById(id: Long): GenerationEntity? {
        val cursor = readableDatabase.query(
            TABLE_NAME,
            null,
            "$COLUMN_ID = ?",
            arrayOf(id.toString()),
            null,
            null,
            null
        )
        return cursor.use { c ->
            if (c.moveToFirst()) cursorToEntity(c) else null
        }
    }

    override fun search(query: String): List<GenerationEntity> {
        val list = mutableListOf<GenerationEntity>()
        val pattern = "%$query%"
        val cursor = readableDatabase.query(
            TABLE_NAME,
            null,
            "$COLUMN_PROMPT LIKE ? OR $COLUMN_NEGATIVE_PROMPT LIKE ?",
            arrayOf(pattern, pattern),
            null,
            null,
            "$COLUMN_TIMESTAMP DESC"
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(cursorToEntity(c))
            }
        }
        return list
    }

    override fun delete(id: Long): Boolean {
        val rows = writableDatabase.delete(TABLE_NAME, "$COLUMN_ID = ?", arrayOf(id.toString()))
        return rows > 0
    }

    override fun deleteBatch(ids: Set<Long>): Int {
        if (ids.isEmpty()) return 0
        val db = writableDatabase
        db.beginTransaction()
        var count = 0
        try {
            val inClause = ids.joinToString(",") { it.toString() }
            count = db.delete(TABLE_NAME, "$COLUMN_ID IN ($inClause)", null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return count
    }

    override fun clearAll() {
        writableDatabase.delete(TABLE_NAME, null, null)
    }

    private fun cursorToEntity(c: Cursor): GenerationEntity {
        return GenerationEntity(
            id = c.getLong(c.getColumnIndexOrThrow(COLUMN_ID)),
            prompt = c.getString(c.getColumnIndexOrThrow(COLUMN_PROMPT)),
            negativePrompt = c.getString(c.getColumnIndexOrThrow(COLUMN_NEGATIVE_PROMPT)),
            modelName = c.getString(c.getColumnIndexOrThrow(COLUMN_MODEL_NAME)),
            imagePath = c.getString(c.getColumnIndexOrThrow(COLUMN_IMAGE_PATH)),
            seed = c.getLong(c.getColumnIndexOrThrow(COLUMN_SEED)),
            steps = c.getInt(c.getColumnIndexOrThrow(COLUMN_STEPS)),
            cfgScale = c.getFloat(c.getColumnIndexOrThrow(COLUMN_CFG_SCALE)),
            sampler = c.getInt(c.getColumnIndexOrThrow(COLUMN_SAMPLER)),
            upscaleMode = c.getInt(c.getColumnIndexOrThrow(COLUMN_UPSCALE_MODE)),
            timestamp = c.getLong(c.getColumnIndexOrThrow(COLUMN_TIMESTAMP)),
            generationTimeMs = c.getLong(c.getColumnIndexOrThrow(COLUMN_GENERATION_TIME_MS)),
            width = c.getInt(c.getColumnIndexOrThrow(COLUMN_WIDTH)),
            height = c.getInt(c.getColumnIndexOrThrow(COLUMN_HEIGHT)),
            fileSizeBytes = c.getLong(c.getColumnIndexOrThrow(COLUMN_FILE_SIZE_BYTES))
        )
    }
}
