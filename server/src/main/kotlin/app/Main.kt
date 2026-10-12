package app

import app.category.CategoryService
import app.purpose.PurposeService

import app.ai.*
import app.analysis.AnalysisJobReconciler
import app.analysis.GeneralWorkerService
import app.analysis.PendingJobRecovery
import app.analysis.WorkerExecution
import app.browser.BrowserRenderProcessor
import app.browser.BrowserWorkerService
import app.browser.EgressProxy
import app.browser.PlaywrightGateway
import app.budget.BudgetMaintenanceService
import app.budget.LlmBudgetService
import app.maintenance.MaintenanceReport
import app.maintenance.MaintenanceService
import app.extraction.*
import app.http.*
import app.tasks.CloudTasksConfig
import app.tasks.CloudTasksGateway
import app.tasks.OutboxDispatcher
import app.tasks.TaskGateway
import io.ktor.server.routing.Route
import java.util.UUID
import javax.sql.DataSource
import app.wishlist.CreateWishlistItemService
import app.wishlist.WishlistReadService
import app.home.HomeReadService
import app.wishlist.GetWishlistItemService
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

fun Application.module(env: Map<String, String> = System.getenv(), resources: RuntimeResources = RuntimeResources(), taskGateway: TaskGateway? = null) {
    monitor.subscribe(ApplicationStopping) { resources.stopAcceptingWork() }
    monitor.subscribe(ApplicationStopped) {
        try { resources.close() } catch (_: Throwable) { log.error("Runtime resources could not all be closed") }
    }
    configureRuntime(env, resources, taskGateway)
}

private fun Application.configureRuntime(env: Map<String, String>, resources: RuntimeResources, taskGateway: TaskGateway?) {
    val runtime = RuntimeConfig.fromEnvironment(env)
    install(ContentNegotiation) { json(ApiJson) }
    installApiHttpSupport()
    if (runtime.role == RuntimeRole.LOCAL_HEALTH) {
        routing { get("/health") { call.respondText("ok") } }
        return
    }
    val source = resources.own(DatabaseFactory.pooledDataSource(env.getValue("DATABASE_URL"),
        env.getValue("DATABASE_USER"), env.getValue("DATABASE_PASSWORD"), runtime.databasePool))
    if (runtime.role == RuntimeRole.GENERAL_WORKER || runtime.role == RuntimeRole.BROWSER_WORKER) {
        val classifier = workerClassifier(env, source)
        // A browser page resolves many subresource hosts at once (route checks and proxy pinning, two renders),
        // so its resolver gets more room before saturation turns into a rejected request.
        val resolver = if (runtime.role == RuntimeRole.BROWSER_WORKER) BoundedResolver(threads = 8, queueCapacity = 128) else BoundedResolver()
        val safety = UrlSafetyPolicy(resources.own(resolver))
        val execution = resources.own(WorkerExecution())
        routing {
            get("/health") { call.respondText("ok") }
            if (runtime.role == RuntimeRole.GENERAL_WORKER) {
                val transport = resources.own(SafeHttpTransport())
                val extractor = HttpMetadataExtractor(safety, transport::fetch)
                val processor = GeneralExtractionProcessor(source, extractor::extract, classifier::classify)
                generalWorkerRoute(GeneralWorkerService(source, execution, processor::process)::runGeneral)
            } else {
                val gateway = PlaywrightGateway(safety) { EgressProxy(safety) }
                val renderer = BrowserRenderProcessor(source, gateway::render)
                browserWorkerRoute(BrowserWorkerService(source, renderer::render, classifier::classify, execution)::runBrowser)
            }
        }
        return
    }
    if (runtime.role == RuntimeRole.MAINTENANCE) {
        val tasks = taskGateway ?: cloudTasksConfig(env)?.let { config ->
            CloudTasksGateway(resources.own(CloudTasksClient.create(CloudTasksGateway.clientSettings())), config)
        } ?: TaskGateway { error("Cloud Tasks is not configured") }
        val dispatcher = OutboxDispatcher(source, tasks)
        val reconciler = AnalysisJobReconciler(source)
        val pending = PendingJobRecovery(source, tasks)
        val budget = BudgetMaintenanceService(source) { alert ->
            // Infrastructure routes these structured records to the alert channel.
            log.warn("LLM_BUDGET_ALERT id={} window={} start={} threshold={}", alert.id, alert.windowType, alert.windowStart, alert.thresholdPercent)
        }
        val service = MaintenanceService({ limit, deadline -> dispatcher.dispatchPending(limit, deadline) },
            reconciler::reconcileExpired, pending::recover, budget::runOnce)
        routing {
            get("/health") { call.respondText("ok") }
            maintenanceRoutes {
                var report: MaintenanceReport? = null
                resources.runIfOpen { report = service.runOnce() }
                report
            }
        }
        return
    }
    val projectId = env.getValue("FIREBASE_PROJECT_ID")
    val firebase = FirebaseApp.getApps().firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME } ?: FirebaseApp.initializeApp(
        FirebaseOptions.builder().setCredentials(GoogleCredentials.getApplicationDefault()).setProjectId(projectId).build(),
    ).also { app -> resources.own(AutoCloseable { app.delete() }) }
    val resolver = FirebaseOwnerResolver(projectId) { token -> FirebaseAuth.getInstance(firebase).verifyIdToken(token).uid }
    val dispatcher = cloudTasksConfig(env)?.let { config ->
        val client = resources.own(CloudTasksClient.create(CloudTasksGateway.clientSettings()))
        OutboxDispatcher(source, CloudTasksGateway(client, config))
    }
    routing {
        get("/health") { call.respondText("ok") }
        apiRoutes(source, { eventId -> resources.runIfOpen { dispatcher?.dispatchEvent(eventId) } }) { resolver.resolve(it) }
    }
}

/** Public product API only; internal Worker and maintenance routes belong to their own runtime roles. */
fun Route.apiRoutes(source: DataSource, dispatchAfterCommit: (UUID) -> Unit, resolve: suspend (ApplicationCall) -> UUID?) {
    val readService = WishlistReadService(source)
    wishlistRoutes(CreateWishlistItemService(source, dispatchAfterCommit), GetWishlistItemService(source), resolve)
    wishlistReadRoutes(readService, resolve)
    homeActionRoutes(readService, resolve)
    homeSummaryRoutes(HomeReadService(source), resolve)
    categoryRoutes(CategoryService(source), resolve)
    purposeRoutes(PurposeService(source), resolve)
}

private fun cloudTasksConfig(env: Map<String, String>): CloudTasksConfig? {
    if (env["TASKS_PROJECT_ID"].isNullOrBlank() || env["GENERAL_WORKER_URL"].isNullOrBlank() || env["TASKS_CALLER_SERVICE_ACCOUNT"].isNullOrBlank()) return null
    return CloudTasksConfig(env.getValue("TASKS_PROJECT_ID"), env["TASKS_LOCATION"] ?: "asia-southeast1",
        env["GENERAL_QUEUE"] ?: "general-analysis", env["BROWSER_QUEUE"] ?: "browser-analysis", env.getValue("GENERAL_WORKER_URL"),
        env["BROWSER_WORKER_URL"] ?: env.getValue("GENERAL_WORKER_URL"), env.getValue("TASKS_CALLER_SERVICE_ACCOUNT"))
}

/** Both worker lanes share the OpenAI gateway, budget and owner candidate supply. */
private fun workerClassifier(env: Map<String, String>, source: javax.sql.DataSource): AiClassificationService {
    val model = env.getValue("OPENAI_MODEL_SNAPSHOT")
    val local = env["APP_ENV"] != "production"
    val gateway = OpenAiResponsesGateway(OpenAiConfig(model, env.getValue("OPENAI_API_KEY"), allowLocalAlias = local))
    return AiClassificationService(source, LlmBudgetService(source, modelSnapshot = model, allowLocalAlias = local),
        CategoryCandidateProvider(), gateway::classify)
}
