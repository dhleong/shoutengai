package net.dhleong.shoutengai

import android.app.Activity
import android.content.ContentValues.TAG
import android.content.Context
import android.support.annotation.UiThread
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.FlowableEmitter
import io.reactivex.Single
import io.reactivex.processors.ReplayProcessor
import io.reactivex.schedulers.Schedulers
import net.dhleong.shoutengai.exc.BillingException
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * This class is the primary entry point to the Reactive Billing Library.
 *  You may pass any valid context; it will hold a strong reference to
 *  the Application context, not necessarily the one passed in.
 *
 * You should reuse a single instance as much as possible for proper behavior.
 *
 * @author dhleong
 */
class Shoutengai internal constructor(
    private val base64EncodedPublicKey: String,
    clientFactory: (PurchasesUpdatedListener) -> BillingClient
) {

    /**
     * Default public constructor
     */
    @UiThread
    constructor(
        context: Context,
        base64EncodedPublicKey: String
    ) : this(base64EncodedPublicKey, { listener ->
        BillingClient.newBuilder(context)
            .setListener(listener)
            .build()
    })

    private val purchaseUpdates: ReplayProcessor<PurchaseResult> =
        ReplayProcessor.createWithTimeAndSize(
            1, TimeUnit.SECONDS, Schedulers.io(),
            1
        )

    private val purchasesListener: PurchasesUpdatedListener =
        PurchasesUpdatedListener { responseCode, purchases ->
            purchaseUpdates.onNext(PurchaseResult(
                responseCode,
                purchases ?: emptyList()
            ))
        }

    private val client: Single<BillingClient> =
        clientFactory(purchasesListener).let { client ->
            val isConnected = AtomicBoolean(false)

            Single.defer {
                if (isConnected.get()) Single.just(client)
                else Single.create<BillingClient> { emitter ->
                    // eagerly set "true" so we don't try to connect
                    //  multiple times for rapid requests
                    isConnected.set(true)

                    // start connect
                    client.startConnection(object : BillingClientStateListener {
                        override fun onBillingServiceDisconnected() {
                            // Try to restart the connection on the next request to
                            // Google Play by calling the startConnection() method.
                            isConnected.set(false)
                        }

                        override fun onBillingSetupFinished(responseCode: Int) {
                            if (responseCode == BillingClient.BillingResponse.OK) {
                                emitter.onSuccess(client)
                            } else {
                                emitter.onError(BillingException(responseCode))
                            }
                        }

                    })
                }
            }
        }

    /**
     * A Flowable of all [PurchaseResult]s provided to us by Google Play,
     *  INCLUDING results emitted by [launchBillingFlow].
     */
    fun purchaseUpdates(): Flowable<PurchaseResult> = purchaseUpdates

    /**
     * Reactive version of [BillingClient.launchBillingFlow] that emits the
     *  the result.
     */
    fun launchBillingFlow(
        activity: Activity,
        params: BillingFlowParams
    ): Single<PurchaseResult> = client.flatMap { client ->
        val lastValue = purchaseUpdates.value
        val result = client.launchBillingFlow(activity, params)
        if (result != BillingClient.BillingResponse.OK) {
            Single.error(BillingException(result))
        } else {
            purchaseUpdates
                .filter {
                    // since purchaseUpdates is a ReplayProcessor,
                    //  when we first subscribe it could be an old
                    //  value so we want to ignore that.
                    // Otherwise, we only want *verified* purchase
                    //  updates that contain the sku we requested.
                    it !== lastValue && it.purchases.asSequence()
                        .filter(this::verifyPurchase)
                        .any {
                            it.sku == params.sku
                        }
                }
                .take(1)
                .singleOrError()
        }
    }

    /**
     * Reactive version of [BillingClient.queryPurchases] that uses cached data
     */
    fun queryPurchases(@BillingClient.SkuType type: String): Flowable<Purchase> {
        return client.flatMapPublisher { client ->
            val result = client.queryPurchases(type)
            when {
                result.responseCode != BillingClient.BillingResponse.OK ->
                    Flowable.error(
                        BillingException(result.responseCode)
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
                    emitter.onError(BillingException(responseCode))
                }
            })
        }, BackpressureStrategy.BUFFER) }
    }

    // NOTE: this is inlined to experimentally make it easier to obfuscate
    //  in production apps. May not be worth it
    @Suppress("NOTHING_TO_INLINE")
    private inline fun verifyPurchase(purchase: Purchase): Boolean = try {
        if (BuildConfig.DEBUG && purchase.sku == "android.test.purchase") {
            // this branch should get compiled out in a release build
            true
        } else {
            val signedData = purchase.originalJson
            val signature = purchase.signature
            BillingSecurity.verifyPurchase(base64EncodedPublicKey, signedData, signature)
        }
    } catch (e: IOException) {
        Log.e(TAG, "Got an exception trying to validate a purchase: " + e)
        false
    }
}
