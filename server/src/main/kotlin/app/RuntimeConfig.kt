package app

enum class RuntimeRole { LOCAL_HEALTH, API }

data class RuntimeConfig(val role: RuntimeRole) {
    companion object {
        fun fromEnvironment(env: Map<String,String>): RuntimeConfig {
            val environment = env["APP_ENV"] ?: "local"
            require(environment in setOf("local","production")) { "Unsupported APP_ENV" }
            val requestedRole = env["APP_ROLE"] ?: "api"
            require(requestedRole == "api") { "Worker role is not wired; refusing to start" }
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
