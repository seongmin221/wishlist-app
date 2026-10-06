package app.testutil

import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

fun assertBlockingRouteIo(register: Route.(() -> Unit) -> Unit, request: suspend HttpClient.() -> HttpResponse) {
    Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "route-execution-test") }.asCoroutineDispatcher().use { routeDispatcher ->
        val routeThread = AtomicReference<Thread>()
        val blockingThread = AtomicReference<Thread>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        testApplication {
            application {
                intercept(ApplicationCallPipeline.Call) {
                    withContext(routeDispatcher) { routeThread.set(Thread.currentThread()); proceed() }
                }
                routing {
                    register {
                        blockingThread.set(Thread.currentThread())
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                }
            }
            coroutineScope {
                val pending = async { client.request() }
                try {
                    assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                    assertNotSame(routeThread.get(), blockingThread.get(), "blocking service must leave the route executor")
                } finally { release.countDown() }
                pending.await()
            }
        }
    }
}

fun assertRouteCancellation(register: Route.(() -> Unit) -> Unit, request: suspend HttpClient.() -> HttpResponse) {
    val propagated = AtomicBoolean(false)
    testApplication {
        application {
            intercept(ApplicationCallPipeline.Call) {
                try { proceed() } catch (cause: CancellationException) { propagated.set(true); throw cause }
            }
            routing { register { throw CancellationException("request cancelled") } }
        }
        client.request()
        assertTrue(propagated.get(), "cancellation must reach the request pipeline")
    }
}
