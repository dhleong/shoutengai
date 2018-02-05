package net.dhleong.shoutengai

import android.content.ContentValues.TAG
import android.content.Context
import android.support.annotation.UiThread
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.FlowableEmitter
import io.reactivex.Single
import io.reactivex.processors.ReplayProcessor
import io.reactivex.schedulers.Schedulers
import net.dhleong.shoutengai.exc.BillingUnavailableException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * @author dhleong
 */
class Shoutengai @UiThread constructor(
    context: Context,
    private val base64EncodedPublicKey: String
) {

    private val purchaseUpdates: ReplayProcessor<PurchaseResult> =
        ReplayProcessor.createWithTime(1, TimeUnit.SECONDS, Schedulers.io())

    private val purchasesListener: PurchasesUpdatedListener =
        PurchasesUpdatedListener { responseCode, purchases ->
            purchaseUpdates.onNext(PurchaseResult(
                responseCode,
                purchases ?: emptyList()
            ))
        }

    private val client: Single<BillingClient> =
        BillingClient.newBuilder(context)
            .setListener(purchasesListener)
            .build()
            .let { client -> Single.create<BillingClient> { emitter ->
                client.startConnection(object : BillingClientStateListener {
                    override fun onBillingServiceDisconnected() {
                        // Try to restart the connection on the next request to
                        // Google Play by calling the startConnection() method.
                    }

                    override fun onBillingSetupFinished(responseCode: Int) {
                        if (responseCode == BillingClient.BillingResponse.OK) {
                            emitter.onSuccess(client)
                        } else {
                            emitter.onError(BillingUnavailableException(responseCode))
                        }
                    }

                })
            }.cache() }

    /**
     * Reactive version of [BillingClient.queryPurchases] that uses cached data
     */
    fun queryPurchases(@BillingClient.SkuType type: String): Flowable<Purchase> {
        return client.flatMapPublisher { client ->
            val result = client.queryPurchases(type)
            when {
                result.responseCode != BillingClient.BillingResponse.OK ->
                    Flowable.error(
                        BillingUnavailableException(result.responseCode)
                    )

                result.purchasesList == null -> Flowable.empty()

                else -> Flowable.fromIterable(
                    result.purchasesList.filter(this::verifyPurchase)
                )
            }
        }.filter(this::verifyPurchase)
    }

    /**
     * Reactive version of [BillingClient.queryPurchaseHistoryAsync] that fetches
     *  the most recent purchase for all SKUs, even if they were consumed or canceled.
     */
    fun queryPurchaseHistoryAsync(@BillingClient.SkuType type: String): Flowable<Purchase> {
        return client.flatMapPublisher { client -> Flowable.create({ emitter: FlowableEmitter<Purchase> ->
            client.queryPurchaseHistoryAsync(type, { responseCode, purchasesList ->
                if (responseCode == BillingClient.BillingResponse.OK) {
                    purchasesList.forEach {
                        if (verifyPurchase(it)) {
                            emitter.onNext(it)
                        }
                    }
                    emitter.onComplete()
                } else {
                    emitter.onError(BillingUnavailableException(responseCode))
                }
            })
        }, BackpressureStrategy.BUFFER) }
    }

    // NOTE: this is inlined to experimentally make it easier to obfuscate
    //  in production apps. May not be worth it
    @Suppress("NOTHING_TO_INLINE")
    private inline fun verifyPurchase(purchase: Purchase): Boolean = try {
        val signedData = purchase.originalJson
        val signature = purchase.signature
        BillingSecurity.verifyPurchase(base64EncodedPublicKey, signedData, signature)
    } catch (e: IOException) {
        Log.e(TAG, "Got an exception trying to validate a purchase: " + e)
        false
    }
}