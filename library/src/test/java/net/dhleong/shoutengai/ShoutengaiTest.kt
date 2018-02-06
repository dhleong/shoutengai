package net.dhleong.shoutengai

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.argumentCaptor
import com.nhaarman.mockito_kotlin.doAnswer
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.stub
import com.nhaarman.mockito_kotlin.times
import com.nhaarman.mockito_kotlin.verify
import org.junit.Before
import org.junit.Test

/**
 * @author dhleong
 */
@Suppress("IllegalIdentifier")
class ShoutengaiTest {

    private lateinit var billingClient: BillingClient
    private lateinit var stateListener: BillingClientStateListener
    private lateinit var purchasesUpdatedListener: PurchasesUpdatedListener
    private lateinit var service: Shoutengai

    @Before fun setUp() {
        billingClient = mock {
            val captor = argumentCaptor<BillingClientStateListener>()
            on { startConnection(captor.capture()) } doAnswer {
                stateListener = captor.lastValue
                captor.lastValue.onBillingSetupFinished(BillingClient.BillingResponse.OK)
            }
        }

        service = Shoutengai("") { listener ->
            purchasesUpdatedListener = listener

            // return our mocked client
            billingClient
        }
    }

    @Test fun `Basic query`() {
        val purchases = listOf(
            PurchaseWithSku()
        ).also(this::stubPurchasesToReturn)

        service.queryPurchases("inapp")
            .test()
            .assertValue { it == purchases[0] }
    }

    @Test fun `Re-connect on query after disconnect`() {
        val purchases = listOf(
            PurchaseWithSku()
        ).also(this::stubPurchasesToReturn)

        // NOTE: no eager connection
        verify(billingClient, times(0)).startConnection(any())

        // initiating a query starts the connection
        service.queryPurchases("inapp")
            .test()
            .assertValue { it == purchases[0] }
        verify(billingClient, times(1)).startConnection(any()) // just one

        // receive "disconnected" notice
        stateListener.onBillingServiceDisconnected()
        verify(billingClient, times(1)).startConnection(any()) // still just one

        // a new query comes along...
        service.queryPurchases("inapp")
            .test()
            .assertValue { it == purchases[0] }

        // ... and triggers a new connection
        verify(billingClient, times(2)).startConnection(any())
    }

    private fun stubPurchasesToReturn(purchases: List<Purchase>) {
        billingClient.stub {
            on { queryPurchases(any()) } doAnswer {
                mock {
                    on { purchasesList } doReturn purchases
                }
            }
        }
    }

    @Suppress("TestFunctionName")
    private fun PurchaseWithSku(sku: String = "android.test.purchase") =
        mock<Purchase> {
            on { getSku() } doReturn sku
        }
}