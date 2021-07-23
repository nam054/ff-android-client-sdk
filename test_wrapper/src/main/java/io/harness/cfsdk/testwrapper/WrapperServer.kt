package io.harness.cfsdk.testwrapper

import com.sun.net.httpserver.HttpServer
import io.harness.cfsdk.testwrapper.capability.Initialization
import io.harness.cfsdk.testwrapper.capability.Status
import io.harness.cfsdk.testwrapper.capability.Termination
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

class WrapperServer(port: Int) : Initialization, Termination, Status {

    private val running = AtomicBoolean()
    private val server: HttpServer = HttpServer.create(InetSocketAddress(port), 0)

    override fun init(): Boolean {

        server.start()
        running.set(true)
        return running.get()
    }

    override fun shutdown(): Boolean {

        running.set(false)
        server.stop(0)
        return isNotActive()
    }

    override fun isActive() = running.get()
}