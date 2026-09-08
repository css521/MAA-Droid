package com.aliothmoon.maadroid.manager

import com.aliothmoon.maadroid.domain.models.RemoteBackend
import com.aliothmoon.maadroid.remote.RemoteServiceImpl

object RootRemoteServiceConnector : ProcessServiceConnectorBackend(SuSpawner) {

    override val backend = RemoteBackend.ROOT
    override val eventPrefix = "ROOT"
    override val processNameSuffix = "root_service"
    override val serviceClass: Class<*> = RemoteServiceImpl::class.java
    override val logFileName = "root_launch_debug.log"
    override val keepRoot: Boolean get() = keepRootForInputInjection
}
