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

fun main() {
    embeddedServer(Netty, port = System.getenv("PORT")?.toIntOrNull() ?: 8080) {
        module()
    }.start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) { json() }
    routing { get("/health") { call.respondText("ok") } }
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
