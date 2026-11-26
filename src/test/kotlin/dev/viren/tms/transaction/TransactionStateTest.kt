package dev.viren.tms.transaction

import dev.viren.tms.common.error.ErrorCode
import dev.viren.tms.common.error.TmsException
import dev.viren.tms.transaction.domain.Transaction
import dev.viren.tms.transaction.domain.TransactionStatus
import dev.viren.tms.transaction.domain.TransactionType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TransactionStateTest {

    private fun newTxn() = Transaction("key", "hash", TransactionType.TRANSFER, "a", "b", 100, "INR")

    @Test
    fun `happy path PENDING to PROCESSING to COMPLETED`() {
        val txn = newTxn()
        txn.startProcessing()
        txn.complete()
        assertThat(txn.status).isEqualTo(TransactionStatus.COMPLETED)
    }

    @Test
    fun `failure records code and reason`() {
        val txn = newTxn().apply { startProcessing() }
        txn.fail(ErrorCode.INSUFFICIENT_BALANCE, "not enough")
        assertThat(txn.status).isEqualTo(TransactionStatus.FAILED)
        assertThat(txn.failureCode).isEqualTo("INSUFFICIENT_BALANCE")
        assertThat(txn.failureReason).isEqualTo("not enough")
    }

    @Test
    fun `cannot complete a transaction that was never processed`() {
        assertIllegal { newTxn().complete() }
    }

    @Test
    fun `terminal states reject every transition`() {
        val completed = newTxn().apply { startProcessing(); complete() }
        assertIllegal { completed.fail(ErrorCode.INTERNAL_ERROR, "late failure") }
        assertIllegal { completed.startProcessing() }

        val failed = newTxn().apply { fail(ErrorCode.LIMIT_VIOLATION, "too big") }
        assertIllegal { failed.complete() }
    }

    @Test
    fun `processing can be requeued to pending and claimed again`() {
        val txn = newTxn().apply { startProcessing() }
        txn.requeue()
        assertThat(txn.status).isEqualTo(TransactionStatus.PENDING)
        txn.startProcessing()
        assertThat(txn.processingAttempts).isEqualTo(2)
    }

    @Test
    fun `only processing transactions can be requeued`() {
        assertIllegal { newTxn().requeue() }
        assertIllegal { newTxn().apply { startProcessing(); complete() }.requeue() }
    }

    private fun assertIllegal(action: () -> Unit) {
        assertThatThrownBy(action)
            .isInstanceOf(TmsException::class.java)
            .extracting("code").isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION)
    }
}
