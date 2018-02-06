package net.dhleong.shoutengai.exc

/**
 * @author dhleong
 */
class BillingException(
    val responseCode: Int
) : Exception()