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
import app.wishlist.CreateWishlistItemService
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth

fun main() {
    embeddedServer(Netty, port = System.getenv("PORT")?.toIntOrNull() ?: 8080) {
        module()
    }.start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) { json() }
    routing {
        get("/health") { call.respondText("ok") }
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
            wishlistRoutes(CreateWishlistItemService(DatabaseFactory.dataSource(databaseUrl, databaseUser, databasePassword))) {
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
