package com.maadroid.app.manager

import com.maadroid.app.domain.models.RemoteBackend
import com.maadroid.app.remote.MaaDroidRemoteService

object RootRemoteServiceConnector : ProcessServiceConnectorBackend(SuSpawner) {

    override val backend = RemoteBackend.ROOT
    override val eventPrefix = "ROOT"
    override val processNameSuffix = "root_service"
    override val serviceClass: Class<*> = MaaDroidRemoteService::class.java
    override val logFileName = "root_launch_debug.log"
    override val keepRoot: Boolean get() = keepRootForInputInjection
}
