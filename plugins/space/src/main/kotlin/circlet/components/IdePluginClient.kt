package circlet.components

import circlet.*
import circlet.login.*
import circlet.reactive.*
import circlet.utils.*
import com.intellij.concurrency.*
import com.intellij.notification.*
import com.intellij.openapi.components.*
import com.intellij.openapi.project.*
import com.intellij.util.ui.*
import com.intellij.xml.util.*
import klogging.*
import nl.komponents.kovenant.*
import runtime.*
import runtime.klogger.*
import runtime.lifetimes.*
import java.util.concurrent.*
import kotlin.concurrent.*

private val log = KLoggers.logger("plugin/IdePluginClient.kt")

enum class ConnectingState {
    Disconnected,
    Connected,
    AuthFailed,
    TryConnect
}

class IdePluginClient(val project : Project) :
    AbstractProjectComponent(project),
    ILifetimedComponent by LifetimedComponent(project) {

    val loginDataComponent = component<CircletLoginComponent>()

    val state = Property(ConnectingState.Disconnected)
    val askPasswordExplicitlyAllowed = Property(false)
    val client = Property<CircletClient?>(null)

    init {

        loginDataComponent.enabled.whenTrue(componentLifetime) { enabledLt ->
            state.value = ConnectingState.TryConnect

            enabledLt.add {
                disconnect()
            }
        }

        state.view(componentLifetime) { lt, state ->
            when(state){
                ConnectingState.TryConnect -> tryReconnect(lt)
                ConnectingState.AuthFailed -> authCheckFailedNotification()
                ConnectingState.Connected -> notifyConnected()
                ConnectingState.Disconnected -> notifyDisconnected(lt)
            }
        }

        loginDataComponent.credentialsUpdated.advise(componentLifetime, {
            state.value = ConnectingState.TryConnect
        })

/*
        ProgressManager.getInstance().run(externalTask(myProject, true, object: IExternalTask {
            override val lifetime: Lifetime get() = connectingProgressLifetimeDef.lifetime
            override val cancel: () -> Unit get() = { disconnect() }
            override val title: String get() = connectionState?.message ?: "Connecting"
            override val header: String get() = connectionState?.message ?: "Connecting"
            override val description: String get() = connectionState?.message ?: "Connecting"
            override val isIndeterminate: Boolean get() = true
            override val progress: Double get() = 0.5
        }))
*/
    }

    fun enable() {
        askPasswordExplicitlyAllowed.value = true
        loginDataComponent.enabled.value = true
    }

    fun disable() {
        loginDataComponent.enabled.value = false
    }

    private fun tryReconnect(lifetime: Lifetime) {

        val ask = askPasswordExplicitlyAllowed.value
        askPasswordExplicitlyAllowed.value = false

        val loginComponent = component<CircletLoginComponent>()
        val modality = application.currentModalityState

        task {
            log.catch {
                loginComponent.
                    getAccessToken(loginComponent.login, loginComponent.pass).
                    thenLater(lifetime, modality) {
                        val errorMessage = it.errorMessage
                        if (errorMessage == null || errorMessage.isEmpty()) {
                            state.value = ConnectingState.Connected
                            val token = it.token!!
                            connectWithToken(lifetime, token)
                        } else {
                            state.value = ConnectingState.AuthFailed
                            if (ask)
                                askPassword()
                        }
                    }. failureLater(lifetime, modality) {
                        JobScheduler.getScheduler().schedule({
                            if (!lifetime.isTerminated)
                                tryReconnect(lifetime)
                        }, 5000, TimeUnit.MILLISECONDS)
                        state.value = ConnectingState.TryConnect
                }
            }
        } success {

        }
    }

    fun askPassword() {
        LoginDialog(LoginDialogViewModel(component<CircletLoginComponent>())).show()
    }

    private fun connectWithToken(lifetime : Lifetime, token: String) {
        val clientLocal = CircletClient("ws://localhost:8084/api/v1/connect", token)
        client.value = clientLocal
        clientLocal.profile.isMyProfileReady()
            .flatMap {
                if (!it) {
                    clientLocal.profile.createMyProfile("Hey! ${Random.nextBytes(10)}")
                } else {
                    clientLocal.profile.getMyProfile().map {
                        log.debug { "My Profile: $it" }
                    }
                    clientLocal.profile.editMyName("Hey! ${Random.nextBytes(10)}")
                }
            }
            .then {
                log.debug { "Result: $it" }
            }
            .failure {
                log.debug { "Error: $it" }
            }
    }

    fun disconnect() {
        state.value = ConnectingState.Disconnected
    }

    fun notifyDisconnected(lt: Lifetime) {
        val notification = Notification(
            "IdePLuginClient.notifyDisconnected",
            "Circlet",
            XmlStringUtil.wrapInHtml("<a href=\"update\">Click enable</a> to continue using Circlet integration"),
            NotificationType.INFORMATION,
            { a, b -> enable() })
        notification.notify(lt, project)
    }

    fun notifyConnected() {
        val notification = Notification(
            "IdePLuginClient.notifyDisconnected",
            "Circlet",
            XmlStringUtil.wrapInHtml("Logged in as ${loginDataComponent.login}"),
            NotificationType.INFORMATION,
            { a, b -> enable() })
        notification.notify(project)
    }

    private fun authCheckFailedNotification() {
        Notification(
            "IdePLuginClient.authCheckFailedNotification",
            "Circlet",
            XmlStringUtil.wrapInHtml("Authorization failed. Please <a href=\"update\">re-enter credentials</a>"),
            NotificationType.INFORMATION,
            { a, b -> project.component<IdePluginClient>().askPassword() })
            .notify(project)
    }
}


