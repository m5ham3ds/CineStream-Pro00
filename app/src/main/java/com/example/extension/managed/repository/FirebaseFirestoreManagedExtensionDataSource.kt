package com.example.extension.managed.repository

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Production implementation of ManagedExtensionRemoteDataSource querying Firestore at canonical path:
 * /managed_extensions/{extensionId} with seamless backward/cross-platform compatibility for
 * /extensions/{extensionId} (CineStream Admin Dashboard contract).
 */
class FirebaseFirestoreManagedExtensionDataSource(
    private val firestoreProvider: () -> FirebaseFirestore = { FirebaseFirestore.getInstance() }
) : ManagedExtensionRemoteDataSource {

    companion object {
        const val COLLECTION_PATH = "managed_extensions"
        const val LEGACY_COLLECTION_PATH = "extensions"
    }

    override suspend fun fetchManagedExtensionDtos(): Result<List<ManagedExtensionDto>> = try {
        val firestore = firestoreProvider()
        val dtosMap = LinkedHashMap<String, ManagedExtensionDto>()

        // 1. Fetch from canonical managed_extensions collection
        try {
            val managedSnap = firestore.collection(COLLECTION_PATH).get().await()
            for (doc in managedSnap.documents) {
                try {
                    val dto = ManagedExtensionDto.fromDocument(doc)
                    if (dto.id != null) {
                        dtosMap[dto.id] = dto
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // 2. Fetch from shared extensions collection (for Admin Dashboard contract compatibility)
        try {
            val legacySnap = firestore.collection(LEGACY_COLLECTION_PATH).get().await()
            for (doc in legacySnap.documents) {
                try {
                    val dto = ManagedExtensionDto.fromDocument(doc)
                    if (dto.id != null && !dtosMap.containsKey(dto.id)) {
                        dtosMap[dto.id] = dto
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        Result.success(dtosMap.values.toList())
    } catch (e: Exception) {
        Result.failure(e)
    }
}
