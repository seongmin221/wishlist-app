package app

import app.ai.*
import app.analysis.GeneralWorkerService
import app.analysis.WorkerExecution
import app.budget.LlmBudgetService
import app.extraction.*
import app.http.*
import app.tasks.CloudTasksConfig
import app.tasks.CloudTasksGateway
import app.tasks.OutboxDispatcher
import app.wishlist.CreateWishlistItemService
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.tasks.v2.CloudTasksClient
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun main() {
    embeddedServer(Netty, port = System.getenv("PORT")?.toIntOrNull() ?: 8080) { module() }.start(wait = true)
}

fun Application.module(env: Map<String, String> = System.getenv(), resources: RuntimeResources = RuntimeResources()) {
    monitor.subscribe(ApplicationStopping) { resources.stopAcceptingWork() }
    monitor.subscribe(ApplicationStopped) {
        try { resources.close() } catch (_: Throwable) { log.error("Runtime resources could not all be closed") }
    }
    configureRuntime(env, resources)
}

private fun Application.configureRuntime(env: Map<String, String>, resources: RuntimeResources) {
    val runtime = RuntimeConfig.fromEnvironment(env)
    install(ContentNegotiation) { json(ApiJson) }
    installApiHttpSupport()
    if (runtime.role == RuntimeRole.LOCAL_HEALTH) {
        routing { get("/health") { call.respondText("ok") } }
        return
    }
    val source = resources.own(DatabaseFactory.pooledDataSource(env.getValue("DATABASE_URL"),
        env.getValue("DATABASE_USER"), env.getValue("DATABASE_PASSWORD"), runtime.databasePool))
    if (runtime.role == RuntimeRole.GENERAL_WORKER) {
        val catalog = TaxonomyCatalog.loadV1()
        val model = env.getValue("OPENAI_MODEL_SNAPSHOT")
        val gateway = OpenAiResponsesGateway(OpenAiConfig(model, env.getValue("OPENAI_API_KEY"), allowLocalAlias = env["APP_ENV"] != "production"))
        val classifier = AiClassificationService(source, LlmBudgetService(source, modelSnapshot = model, allowLocalAlias = env["APP_ENV"] != "production"),
            { catalog.snapshot(catalog.categories.map { it.id }.toSet()) }, gateway::classify)
        val transport = resources.own(SafeHttpTransport())
        val extractor = HttpMetadataExtractor(UrlSafetyPolicy(), transport::fetch)
        val processor = GeneralExtractionProcessor(source, extractor::extract, classifier::classify)
        val execution = resources.own(WorkerExecution())
        routing {
            get("/health") { call.respondText("ok") }
            workerRoutes(GeneralWorkerService(source, execution, processor::process))
        }
        return
    }
    val projectId = env.getValue("FIREBASE_PROJECT_ID")
    val firebase = FirebaseApp.getApps().firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME } ?: FirebaseApp.initializeApp(
        FirebaseOptions.builder().setCredentials(GoogleCredentials.getApplicationDefault()).setProjectId(projectId).build(),
    ).also { app -> resources.own(AutoCloseable { app.delete() }) }
    val resolver = FirebaseOwnerResolver(projectId) { token -> FirebaseAuth.getInstance(firebase).verifyIdToken(token).uid }
    val dispatcher = if (!env["TASKS_PROJECT_ID"].isNullOrBlank() && !env["GENERAL_WORKER_URL"].isNullOrBlank() && !env["TASKS_CALLER_SERVICE_ACCOUNT"].isNullOrBlank()) {
        val config = CloudTasksConfig(env.getValue("TASKS_PROJECT_ID"), env["TASKS_LOCATION"] ?: "asia-southeast1",
            env["GENERAL_QUEUE"] ?: "general-analysis", env["BROWSER_QUEUE"] ?: "browser-analysis", env.getValue("GENERAL_WORKER_URL"),
            env["BROWSER_WORKER_URL"] ?: env.getValue("GENERAL_WORKER_URL"), env.getValue("TASKS_CALLER_SERVICE_ACCOUNT"))
        val client = resources.own(CloudTasksClient.create(CloudTasksGateway.clientSettings()))
        OutboxDispatcher(source, CloudTasksGateway(client, config))
    } else null
    val service = CreateWishlistItemService(source) { eventId ->
        resources.runIfOpen { dispatcher?.dispatchEvent(eventId) }
    }
    val detailService = app.wishlist.GetWishlistItemService(source)
    routing {
        get("/health") { call.respondText("ok") }
        wishlistRoutes(service, detailService) { resolver.resolve(it) }
    }
}
