package com.aspora.tms.notification

import com.aspora.tms.event.EventSubscriber
import com.aspora.tms.event.TransactionEvent
import com.aspora.tms.wallet.WalletRepository
import org.springframework.stereotype.Component

/** Notifies the users that own the wallets involved in a transaction. */
@Component
class NotificationSubscriber(
    private val notifications: NotificationService,
    private val wallets: WalletRepository,
) : EventSubscriber {
    override val name = "user-notifications"

    override fun onEvent(event: TransactionEvent) {
        val title = "${event.type} ${event.status}"
        val message = buildString {
            append("Transaction ${event.transactionId} of ${event.amount} ${event.currency} is ${event.status}")
            event.failureReason?.let { append(": $it") }
        }
        wallets.findAllById(listOf(event.sourceWalletId, event.destinationWalletId))
            .mapNotNull { it.userId }
            .distinct()
            .forEach { notifications.notify(Notification(it, title, message)) }
    }
}
