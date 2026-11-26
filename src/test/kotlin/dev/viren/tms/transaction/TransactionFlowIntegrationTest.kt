package dev.viren.tms.transaction

import dev.viren.tms.common.error.ErrorCode
import dev.viren.tms.common.error.TmsException
import dev.viren.tms.event.EventSubscriber
import dev.viren.tms.event.TransactionEvent
import dev.viren.tms.ledger.LedgerRepository
import dev.viren.tms.transaction.domain.Transaction
import dev.viren.tms.transaction.domain.TransactionRepository
import dev.viren.tms.transaction.recovery.StuckTransactionSweeper
import dev.viren.tms.transaction.domain.TransactionStatus
import dev.viren.tms.transaction.domain.TransactionType
import dev.viren.tms.transaction.handler.TransactionRequest
import dev.viren.tms.transaction.service.TransactionService
import dev.viren.tms.user.UserService
import dev.viren.tms.wallet.Wallet
import dev.viren.tms.wallet.WalletService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** End-to-end through outbox -> queue -> processor -> ledger -> fan-out, on a real SQLite DB. */
@SpringBootTest
@ActiveProfiles("test")
class TransactionFlowIntegrationTest {

    @TestConfiguration
    class Config {
        @Bean
        fun recordingSubscriber() = RecordingSubscriber()
    }

    class RecordingSubscriber : EventSubscriber {
        val events = ConcurrentLinkedQueue<TransactionEvent>()
        override val name = "test-recorder"
        override fun onEvent(event: TransactionEvent) { events += event }
    }

    @Autowired lateinit var transactions: TransactionService
    @Autowired lateinit var users: UserService
    @Autowired lateinit var wallets: WalletService
    @Autowired lateinit var ledger: LedgerRepository
    @Autowired lateinit var recorder: RecordingSubscriber
    @Autowired lateinit var transactionRepository: TransactionRepository
    @Autowired lateinit var sweeper: StuckTransactionSweeper

    @Test
    fun `concurrent opposite transfers keep balances and ledger consistent`() {
        val a = fundedWallet(100_000)
        val b = fundedWallet(100_000)

        val pool = Executors.newFixedThreadPool(16)
        val ids = ConcurrentLinkedQueue<String>()
        repeat(100) {
            pool.submit { ids += submit(TransactionType.TRANSFER, a.id, b.id, 1_000).id }
            pool.submit { ids += submit(TransactionType.TRANSFER, b.id, a.id, 500).id }
        }
        pool.shutdown()
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue()

        ids.forEach { awaitTerminal(it) }
        assertThat(ids.map { transactions.get(it).status }).containsOnly(TransactionStatus.COMPLETED)

        assertThat(wallets.get(a.id).withdrawableBalance).isEqualTo(100_000 - 100_000 + 50_000)
        assertThat(wallets.get(b.id).withdrawableBalance).isEqualTo(100_000 + 100_000 - 50_000)
        assertThat(wallets.reconcile(a.id).consistent).isTrue()
        assertThat(wallets.reconcile(b.id).consistent).isTrue()
        // Double entry: across all wallets (system ones included) the ledger nets to zero.
        assertThat(ledger.findAll().sumOf { it.signedAmount }).isZero()
    }

    @Test
    fun `insufficient balance fails the transaction without moving money`() {
        val a = fundedWallet(1_000)
        val b = fundedWallet(0)

        val txn = awaitTerminal(submit(TransactionType.TRANSFER, a.id, b.id, 5_000).id)

        assertThat(txn.status).isEqualTo(TransactionStatus.FAILED)
        assertThat(txn.failureCode).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE.name)
        assertThat(ledger.findByTransactionId(txn.id)).isEmpty()
        assertThat(wallets.get(a.id).withdrawableBalance).isEqualTo(1_000)
    }

    @Test
    fun `same idempotency key replays, different payload is rejected`() {
        val a = fundedWallet(10_000)
        val b = fundedWallet(0)
        val key = UUID.randomUUID().toString()

        val first = transactions.submit(request(TransactionType.TRANSFER, a.id, b.id, 1_000, key))
        val retry = transactions.submit(request(TransactionType.TRANSFER, a.id, b.id, 1_000, key))

        assertThat(first.replayed).isFalse()
        assertThat(retry.replayed).isTrue()
        assertThat(retry.transaction.id).isEqualTo(first.transaction.id)
        assertThatThrownBy { transactions.submit(request(TransactionType.TRANSFER, a.id, b.id, 2_000, key)) }
            .isInstanceOf(TmsException::class.java)
            .extracting("code").isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED)

        awaitTerminal(first.transaction.id)
        assertThat(wallets.get(b.id).withdrawableBalance).isEqualTo(1_000)
    }

    @Test
    fun `completed transaction is fanned out to subscribers`() {
        val wallet = fundedWallet(0)
        val txn = awaitTerminal(submit(TransactionType.DEPOSIT, null, wallet.id, 2_500).id)

        await().atMost(Duration.ofSeconds(10)).until {
            recorder.events.any { it.transactionId == txn.id && it.status == TransactionStatus.COMPLETED }
        }
    }

    @Test
    fun `sweeper recovers a transaction stranded in PROCESSING and applies it exactly once`() {
        val a = fundedWallet(10_000)
        val b = fundedWallet(0)
        // Simulates a worker that crashed after claim() committed: PROCESSING, no queue message, long untouched.
        val stranded = transactionRepository.save(
            Transaction(UUID.randomUUID().toString(), "hash", TransactionType.TRANSFER, a.id, b.id, 3_000, "INR").apply {
                startProcessing()
                updatedAt = Instant.now().minusSeconds(3_600)
            },
        )

        assertThat(sweeper.sweep()).isGreaterThanOrEqualTo(1)

        val done = awaitTerminal(stranded.id)
        assertThat(done.status).isEqualTo(TransactionStatus.COMPLETED)
        assertThat(wallets.get(a.id).withdrawableBalance).isEqualTo(7_000)
        assertThat(wallets.get(b.id).withdrawableBalance).isEqualTo(3_000)
        assertThat(ledger.findByTransactionId(stranded.id)).hasSize(2)
    }

    private fun fundedWallet(amount: Long): Wallet {
        val user = users.create("user", "${UUID.randomUUID()}@test.com")
        val wallet = wallets.createForUser(user.id, "INR")
        if (amount > 0) {
            val deposit = awaitTerminal(submit(TransactionType.DEPOSIT, null, wallet.id, amount).id)
            assertThat(deposit.status).isEqualTo(TransactionStatus.COMPLETED)
        }
        return wallet
    }

    private fun request(type: TransactionType, from: String?, to: String?, amount: Long, key: String = UUID.randomUUID().toString()) =
        TransactionRequest(type, from, to, amount, "INR", key)

    private fun submit(type: TransactionType, from: String?, to: String?, amount: Long) =
        transactions.submit(request(type, from, to, amount)).transaction

    private fun awaitTerminal(id: String) = await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50))
        .until({ transactions.get(id) }, { it.status.isTerminal })
}
