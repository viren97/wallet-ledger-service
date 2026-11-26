package dev.viren.tms.wallet

import dev.viren.tms.common.error.TmsException
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.ConcurrentHashMap

@ConfigurationProperties("tms.system-wallets")
data class SystemWalletProperties(val currencies: List<String> = listOf("INR"))

/** Resolves (and caches) the ids of system wallets such as BANK_SETTLEMENT per currency. */
@Component
class SystemWalletRegistry(
    private val wallets: WalletRepository,
    private val properties: SystemWalletProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cache = ConcurrentHashMap<Pair<SystemWalletCode, String>, String>()

    @Transactional
    fun ensureSystemWallets() {
        for (currency in properties.currencies) {
            for (code in SystemWalletCode.entries) {
                if (wallets.findBySystemCodeAndCurrency(code, currency) == null) {
                    wallets.save(Wallet.system(code, currency))
                    log.info("Created system wallet {} for {}", code, currency)
                }
            }
        }
    }

    fun walletId(code: SystemWalletCode, currency: String): String =
        cache.getOrPut(code to currency) {
            wallets.findBySystemCodeAndCurrency(code, currency)?.id
                ?: throw TmsException.invalid("Currency '$currency' is not supported")
        }
}

/**
 * Separate bean on purpose: calling a @Transactional method from inside the same class
 * bypasses the Spring proxy, so the runner lives outside the registry.
 */
@Component
class SystemWalletBootstrap(private val registry: SystemWalletRegistry) : ApplicationRunner {
    override fun run(args: ApplicationArguments) = registry.ensureSystemWallets()
}
