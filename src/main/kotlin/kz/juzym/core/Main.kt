package kz.juzym.core

import kz.juzym.app.Application
import kz.juzym.config.AppConfigLoader
import kz.juzym.config.Environment
import kz.juzym.http.HttpServer

fun main() {
    val application = Application(AppConfigLoader.load(Environment.TEST))
    val context = application.start()
    val server = HttpServer(context)

    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop()
    })

    try {
        server.start(wait = true)
    } finally {
        context.close()
    }
}
