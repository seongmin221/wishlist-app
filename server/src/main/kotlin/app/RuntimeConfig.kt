package app

enum class RuntimeRole(val defaultPoolSize: Int) { LOCAL_HEALTH(5), API(5), GENERAL_WORKER(2), BROWSER_WORKER(2), MAINTENANCE(2) }

/** Where user images live. Production always uses a bucket; local falls back to files served by the API. */
sealed interface MediaStorageConfig {
    data class Gcs(val bucket: String) : MediaStorageConfig
    data class Local(val directory: String, val baseUrl: String) : MediaStorageConfig
    val exposesLocalRoutes: Boolean get() = this is Local

    companion object {
        fun fromEnvironment(env: Map<String, String>, environment: String): MediaStorageConfig {
            val bucket = env["MEDIA_BUCKET"]
            if (bucket != null || environment == "production") {
                require(!bucket.isNullOrBlank()) { "MEDIA_BUCKET is required" }
                return Gcs(bucket)
            }
            return Local(env["MEDIA_LOCAL_DIR"] ?: "build/local-media", env["MEDIA_LOCAL_BASE_URL"] ?: "http://localhost:${env["PORT"] ?: "8080"}")
        }
    }
}

data class RuntimeConfig(val role: RuntimeRole, val databasePool: DatabasePoolConfig = DatabasePoolConfig(role.defaultPoolSize),
                         val media: MediaStorageConfig? = null) {
    companion object {
        fun fromEnvironment(env: Map<String,String>): RuntimeConfig {
            val override = env["DB_POOL_MAX_SIZE"]?.let {
                requireNotNull(it.toIntOrNull()?.takeIf { value -> value > 0 }) { "Invalid DB_POOL_MAX_SIZE" }
            }
            val environment = env["APP_ENV"] ?: "local"
            require(environment in setOf("local","production")) { "Unsupported APP_ENV" }
            fun configured(role: RuntimeRole) = RuntimeConfig(role, DatabasePoolConfig(override ?: role.defaultPoolSize),
                if (role == RuntimeRole.API || role == RuntimeRole.MAINTENANCE) MediaStorageConfig.fromEnvironment(env, environment) else null)
            val requestedRole = env["APP_ROLE"] ?: "api"
            require(requestedRole in setOf("api", "general-worker", "browser-worker", "maintenance")) { "Unsupported APP_ROLE" }
            val tasks = listOf("TASKS_PROJECT_ID","GENERAL_WORKER_URL","BROWSER_WORKER_URL","TASKS_CALLER_SERVICE_ACCOUNT")
            if (requestedRole == "maintenance") {
                require(listOf("DATABASE_URL", "DATABASE_USER", "DATABASE_PASSWORD").all { !env[it].isNullOrBlank() }) { "Incomplete maintenance configuration" }
                // Locally the queue is injected or absent (publication and lookups then fail safely).
                require(environment == "local" || tasks.all { !env[it].isNullOrBlank() }) { "Incomplete production Cloud Tasks configuration" }
                return configured(RuntimeRole.MAINTENANCE)
            }
            if (requestedRole == "general-worker" || requestedRole == "browser-worker") {
                // Both worker lanes classify with the same OpenAI configuration.
                val required = listOf("DATABASE_URL", "DATABASE_USER", "DATABASE_PASSWORD", "OPENAI_API_KEY", "OPENAI_MODEL_SNAPSHOT")
                require(required.all { !env[it].isNullOrBlank() }) { "Incomplete worker configuration" }
                require(environment == "local" || env["OPENAI_MODEL_SNAPSHOT"] != "gpt-5.6-luna") { "A pinned model snapshot is required" }
                return configured(if (requestedRole == "general-worker") RuntimeRole.GENERAL_WORKER else RuntimeRole.BROWSER_WORKER)
            }
            val credentials = listOf("DATABASE_URL","DATABASE_USER","DATABASE_PASSWORD","FIREBASE_PROJECT_ID")
            val present = credentials.count { !env[it].isNullOrBlank() }
            if (environment == "local" && present == 0) return configured(RuntimeRole.LOCAL_HEALTH)
            require(present == credentials.size) { "Incomplete API database or Firebase configuration" }
            if (environment == "production") {
                require(tasks.all { !env[it].isNullOrBlank() }) { "Incomplete production Cloud Tasks configuration" }
            }
            return configured(RuntimeRole.API)
        }
    }
}
