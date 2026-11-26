package dev.viren.tms.notification

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

data class Notification(val userId: String, val title: String, val message: String)

/** A delivery channel (email, SMS, push...). Add a bean to add a channel. */
interface NotificationChannel {
    val name: String
    fun send(notification: Notification)
}

@Component
class LoggingNotificationChannel : NotificationChannel {
    private val log = LoggerFactory.getLogger(javaClass)
    override val name = "log"
    override fun send(notification: Notification) =
        log.info("[notify user={}] {} - {}", notification.userId, notification.title, notification.message)
}

@Service
class NotificationService(private val channels: List<NotificationChannel>) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun notify(notification: Notification) = channels.forEach { channel ->
        runCatching { channel.send(notification) }
            .onFailure { log.warn("Channel {} failed for user {}: {}", channel.name, notification.userId, it.message) }
    }
}
