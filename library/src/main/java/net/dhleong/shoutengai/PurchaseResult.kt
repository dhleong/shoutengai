package net.dhleong.shoutengai

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase

/**
 * @author dhleong
 */
data class PurchaseResult(
    @BillingClient.BillingResponse val responseCode: Int,
    val purchases: List<Purchase>
)