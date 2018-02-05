package net.dhleong.shoutengai.exc

/**
 * @author dhleong
 */
class BillingUnavailableException(
    val responseCode: Int
) : Exception()