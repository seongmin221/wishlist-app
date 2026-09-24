package app

enum class RuntimeRole { LOCAL_HEALTH, API, GENERAL_WORKER }

data class RuntimeConfig(val role: RuntimeRole) {
    companion object {
        fun fromEnvironment(env: Map<String,String>): RuntimeConfig {
            val environment = env["APP_ENV"] ?: "local"
            require(environment in setOf("local","production")) { "Unsupported APP_ENV" }
            val requestedRole = env["APP_ROLE"] ?: "api"
            require(requestedRole in setOf("api", "general-worker")) { "Unsupported APP_ROLE" }
            if (requestedRole == "general-worker") {
                val required = listOf("DATABASE_URL", "DATABASE_USER", "DATABASE_PASSWORD", "OPENAI_API_KEY", "OPENAI_MODEL_SNAPSHOT")
                require(required.all { !env[it].isNullOrBlank() }) { "Incomplete general worker configuration" }
                require(environment == "local" || env["OPENAI_MODEL_SNAPSHOT"] != "gpt-5.6-luna") { "A pinned model snapshot is required" }
                return RuntimeConfig(RuntimeRole.GENERAL_WORKER)
            }
            val credentials = listOf("DATABASE_URL","DATABASE_USER","DATABASE_PASSWORD","FIREBASE_PROJECT_ID")
            val present = credentials.count { !env[it].isNullOrBlank() }
            if (environment == "local" && present == 0) return RuntimeConfig(RuntimeRole.LOCAL_HEALTH)
            require(present == credentials.size) { "Incomplete API database or Firebase configuration" }
            if (environment == "production") {
                val tasks = listOf("TASKS_PROJECT_ID","GENERAL_WORKER_URL","BROWSER_WORKER_URL","TASKS_CALLER_SERVICE_ACCOUNT")
                require(tasks.all { !env[it].isNullOrBlank() }) { "Incomplete production Cloud Tasks configuration" }
            }
            return RuntimeConfig(RuntimeRole.API)
        }
    }
}
