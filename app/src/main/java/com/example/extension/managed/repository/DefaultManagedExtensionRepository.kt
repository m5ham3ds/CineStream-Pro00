package com.example.extension.managed.repository

import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.ManagedExtension
import com.example.extension.managed.model.ManagedExtensionValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Standard implementation of ManagedExtensionRepository.
 * Enforces:
 * 1. Untrusted remote snapshot validation via ManagedExtensionValidator
 * 2. Fault tolerance: malformed documents are skipped, not crashing the list
 * 3. Deterministic deduplication
 * 4. Priority tiering: Fresh remote -> Validated cache -> Domain error
 * 5. Mapping of Firestore exceptions to domain ExtensionErrors
 */
class DefaultManagedExtensionRepository(
    private val remoteDataSource: ManagedExtensionRemoteDataSource,
    private val cache: ManagedExtensionCache = SafeLocalMetadataCache(),
    private val userPreferences: ExtensionUserPreferences = InMemoryExtensionUserPreferences(),
    private val bundledDefaults: List<ManagedExtension> = emptyList()
) : ManagedExtensionRepository {

    override suspend fun getExtensions(forceRefresh: Boolean): Result<List<ManagedExtension>> =
        withContext(Dispatchers.IO) {
            // 1. Check cache if not forcing refresh
            if (!forceRefresh && !cache.isExpired()) {
                val cached = cache.getCached()
                if (cached != null && cached.isNotEmpty()) {
                    val updatedWithPreferences = cached.map { ext ->
                        ext.copy(userEnabled = userPreferences.isExtensionEnabled(ext.id))
                    }
                    com.example.extension.managed.trace.Phase05GLogger.log("CACHE", "repo", "Cache hit with ${cached.size} items")
                    return@withContext Result.success(updatedWithPreferences)
                }
            }

            // 2. Fetch fresh remote data from Firestore
            val remoteResult = remoteDataSource.fetchManagedExtensionDtos()

            if (remoteResult.isSuccess) {
                val dtos = remoteResult.getOrNull() ?: emptyList()
                com.example.extension.managed.trace.Phase05GLogger.log("REMOTE_COUNT", "repo", "Received ${dtos.size} DTOs from Firestore")

                if (dtos.isEmpty()) {
                    // Genuine empty catalog from remote
                    cache.saveCache(emptyList())
                    return@withContext Result.success(emptyList())
                }

                val validExtensions = mutableListOf<ManagedExtension>()
                val rejectedIds = mutableSetOf<String>()

                for (dto in dtos) {
                    val localEnabled = userPreferences.isExtensionEnabled(dto.id.orEmpty())
                    val domainExt = ManagedExtensionMapper.toDomain(dto, localUserEnabled = localEnabled)

                    // Gate: strict validation per Phase 05G FIX 3
                    when (val validation = ManagedExtensionValidator.validateRemoteEntry(domainExt)) {
                        is ManagedExtensionValidator.ValidationResult.Valid -> {
                            validExtensions.add(domainExt)
                        }
                        is ManagedExtensionValidator.ValidationResult.Invalid -> {
                            val id = domainExt.id
                            if (id.isNotBlank()) {
                                rejectedIds.add(id)
                            }
                            com.example.extension.managed.trace.Phase05GLogger.log(
                                "VALIDATED_COUNT",
                                id.ifBlank { "unknown" },
                                "Rejected remote document: ${validation.error}"
                            )
                        }
                    }
                }

                // If remote sent documents, but ALL were invalid/incompatible -> structurally unusable remote configuration
                if (validExtensions.isEmpty() && dtos.isNotEmpty()) {
                    com.example.extension.managed.trace.Phase05GLogger.log(
                        "VALIDATED_COUNT",
                        "repo",
                        "All ${dtos.size} remote documents rejected; falling back to healthy cache / bundled defaults"
                    )
                    val cachedFallback = cache.getCached()
                    if (cachedFallback != null && cachedFallback.isNotEmpty()) {
                        return@withContext Result.success(cachedFallback.map { ext ->
                            ext.copy(userEnabled = userPreferences.isExtensionEnabled(ext.id))
                        })
                    }
                    if (bundledDefaults.isNotEmpty()) {
                        return@withContext Result.success(bundledDefaults.map { ext ->
                            ext.copy(userEnabled = userPreferences.isExtensionEnabled(ext.id))
                        })
                    }
                }

                // Deterministic deduplication
                val deduplicatedValid = validExtensions
                    .groupBy { it.id }
                    .map { (_, group) ->
                        group.maxWithOrNull(compareBy({ it.updatedAt }, { it.priority })) ?: group.first()
                    }

                // For partially invalid remote documents: retain healthy candidate if remote was rejected
                val finalCatalog = mutableListOf<ManagedExtension>()
                finalCatalog.addAll(deduplicatedValid)

                for (rejectedId in rejectedIds) {
                    // Do not allow an invalid remote document to destroy a healthy bundled runtime candidate
                    val healthyCandidate = bundledDefaults.firstOrNull { it.id == rejectedId }
                    if (healthyCandidate != null && finalCatalog.none { it.id == rejectedId }) {
                        finalCatalog.add(healthyCandidate.copy(userEnabled = userPreferences.isExtensionEnabled(rejectedId)))
                    }
                }

                finalCatalog.sortByDescending { it.priority }

                com.example.extension.managed.trace.Phase05GLogger.log(
                    "VALIDATED_COUNT",
                    "repo",
                    "Final catalog contains ${finalCatalog.size} extensions (validRemote=${deduplicatedValid.size}, retainedHealthyCandidates=${finalCatalog.size - deduplicatedValid.size})"
                )

                // Save validated items to cache
                cache.saveCache(finalCatalog)

                return@withContext Result.success(finalCatalog)
            }

            // 3. Fallback to cache on remote failure (e.g. offline/network failure/permission error)
            com.example.extension.managed.trace.Phase05GLogger.log(
                "FIREBASE",
                "repo",
                "Remote fetch failed: ${remoteResult.exceptionOrNull()?.message}; attempting fallback"
            )
            val cachedFallback = cache.getCached()
            if (cachedFallback != null && cachedFallback.isNotEmpty()) {
                val updatedWithPreferences = cachedFallback.map { ext ->
                    ext.copy(userEnabled = userPreferences.isExtensionEnabled(ext.id))
                }
                return@withContext Result.success(updatedWithPreferences)
            }

            // 4. Fallback to bundled defaults when no cache is available on remote failure
            if (bundledDefaults.isNotEmpty()) {
                com.example.extension.managed.trace.Phase05GLogger.log(
                    "REGISTRY",
                    "repo",
                    "No cache available; falling back to ${bundledDefaults.size} bundled defaults"
                )
                val updatedDefaults = bundledDefaults.map { ext ->
                    ext.copy(userEnabled = userPreferences.isExtensionEnabled(ext.id))
                }
                return@withContext Result.success(updatedDefaults)
            }

            // 5. No cache and no bundled defaults available -> map remote failure to domain ExtensionError
            val error = remoteResult.exceptionOrNull()
            val domainError = mapToDomainError(error)
            Result.failure(domainError)
        }

    override suspend fun getExtensionById(id: String, forceRefresh: Boolean): Result<ManagedExtension> =
        withContext(Dispatchers.IO) {
            val listResult = getExtensions(forceRefresh)
            if (listResult.isFailure) {
                return@withContext Result.failure(listResult.exceptionOrNull()!!)
            }

            val extensions = listResult.getOrNull() ?: emptyList()
            val match = extensions.firstOrNull { it.id == id }

            if (match != null) {
                Result.success(match)
            } else {
                Result.failure(
                    ExtensionError.ExtractionFailed("Managed extension with ID '$id' was not found or is invalid")
                )
            }
        }

    private fun mapToDomainError(throwable: Throwable?): Throwable {
        if (throwable == null) {
            return ExtensionError.ExtractionFailed("Unknown repository failure occurred")
        }

        val message = throwable.message.orEmpty().lowercase()
        return if (throwable is IOException ||
            message.contains("unavailable") ||
            message.contains("offline") ||
            message.contains("network") ||
            message.contains("no internet")
        ) {
            ExtensionError.NoInternet(
                detail = "No internet connection available and no local cache present",
                cause = throwable
            )
        } else {
            ExtensionError.ExtractionFailed(
                detail = "Failed to retrieve managed extensions: ${throwable.message}",
                cause = throwable
            )
        }
    }
}
