package kz.juzym.core

import kz.juzym.app.Application

fun main() {
    val application = Application()
    val context = application.start()
    Runtime.getRuntime().addShutdownHook(Thread { context.close() })
}
