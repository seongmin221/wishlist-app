package app

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.flywaydb.core.Flyway
import javax.sql.DataSource
import org.postgresql.ds.PGSimpleDataSource
import app.http.FirebaseOwnerResolver
import app.http.wishlistRoutes
import app.http.workerRoutes
import app.analysis.GeneralWorkerService
import app.extraction.GeneralExtractionProcessor
import app.extraction.HttpMetadataExtractor
import app.extraction.SafeHttpTransport
import app.extraction.UrlSafetyPolicy
import app.ai.AiClassificationService
import app.ai.OpenAiConfig
import app.ai.OpenAiResponsesGateway
import app.ai.TaxonomyCatalog
import app.budget.LlmBudgetService
import app.wishlist.CreateWishlistItemService
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import app.tasks.CloudTasksConfig
import app.tasks.CloudTasksGateway
import app.tasks.OutboxDispatcher
import com.google.cloud.tasks.v2.CloudTasksClient

fun main() {
    embeddedServer(Netty, port = System.getenv("PORT")?.toIntOrNull() ?: 8080) {
        module()
    }.start(wait = true)
}

fun Application.module() {
    val env = System.getenv()
    val runtime = RuntimeConfig.fromEnvironment(env)
    install(ContentNegotiation) { json() }
    routing {
        get("/health") { call.respondText("ok") }
        if (runtime.role == RuntimeRole.GENERAL_WORKER) {
            val source = DatabaseFactory.dataSource(env.getValue("DATABASE_URL"), env.getValue("DATABASE_USER"), env.getValue("DATABASE_PASSWORD"))
            val catalog = TaxonomyCatalog.loadV1()
            val model = env.getValue("OPENAI_MODEL_SNAPSHOT")
            val gateway = OpenAiResponsesGateway(OpenAiConfig(model, env.getValue("OPENAI_API_KEY"), allowLocalAlias = env["APP_ENV"] != "production"))
            val classifier = AiClassificationService(source, LlmBudgetService(source, modelSnapshot = model, allowLocalAlias = env["APP_ENV"] != "production"),
                { catalog.snapshot(catalog.categories.map { it.id }.toSet()) }, gateway::classify)
            val transport = SafeHttpTransport()
            val extractor = HttpMetadataExtractor(UrlSafetyPolicy(), transport::fetch)
            val processor = GeneralExtractionProcessor(source, extractor::extract, classifier::classify)
            workerRoutes(GeneralWorkerService(source, processor::process))
            return@routing
        }
        val databaseUrl = System.getenv("DATABASE_URL")
        val databaseUser = System.getenv("DATABASE_USER")
        val databasePassword = System.getenv("DATABASE_PASSWORD")
        val projectId = System.getenv("FIREBASE_PROJECT_ID")
        if (databaseUrl != null && databaseUser != null && databasePassword != null && projectId != null) {
            if (FirebaseApp.getApps().isEmpty()) {
                FirebaseApp.initializeApp(
                    FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.getApplicationDefault())
                        .setProjectId(projectId)
                        .build(),
                )
            }
            val resolver = FirebaseOwnerResolver(projectId) { token -> FirebaseAuth.getInstance().verifyIdToken(token).uid }
            val dataSource = DatabaseFactory.dataSource(databaseUrl, databaseUser, databasePassword)
            val dispatchAfterCommit: () -> Unit = {
                val queueProject = System.getenv("TASKS_PROJECT_ID")
                val workerUrl = System.getenv("GENERAL_WORKER_URL")
                val caller = System.getenv("TASKS_CALLER_SERVICE_ACCOUNT")
                if (queueProject != null && workerUrl != null && caller != null) {
                    val config = CloudTasksConfig(
                        queueProject,
                        System.getenv("TASKS_LOCATION") ?: "asia-southeast1",
                        System.getenv("GENERAL_QUEUE") ?: "general-analysis",
                        System.getenv("BROWSER_QUEUE") ?: "browser-analysis",
                        workerUrl,
                        System.getenv("BROWSER_WORKER_URL") ?: workerUrl,
                        caller,
                    )
                    CloudTasksClient.create().use { client ->
                        OutboxDispatcher(dataSource, CloudTasksGateway(client, config)).dispatchPending(1)
                    }
                }
            }
            wishlistRoutes(CreateWishlistItemService(dataSource, dispatchAfterCommit)) {
                resolver.resolve(it)
            }
        }
    }
}

object DatabaseFactory {
    fun dataSource(url: String, username: String, password: String): DataSource = PGSimpleDataSource().apply {
        setURL(url)
        user = username
        this.password = password
    }

    fun migrate(url: String, username: String, password: String) {
        Flyway.configure()
            .dataSource(url, username, password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }
}
