package com.example.sdnpu.data

interface GenerationDao {
    fun insert(entity: GenerationEntity): Long
    fun getAll(): List<GenerationEntity>
    fun getById(id: Long): GenerationEntity?
    fun search(query: String): List<GenerationEntity>
    fun delete(id: Long): Boolean
    fun deleteBatch(ids: Set<Long>): Int
    fun clearAll()
}
