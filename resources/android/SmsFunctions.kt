package com.rishadblack.plugins.sylloo_app_native

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The phone's SIMs and its SMS (phone-messaging-plan.md §5). Off unless the app turns on `features.sms_send`: then the
 * build has SEND_SMS and READ_PHONE_STATE (the build hook adds them), otherwise neither, and every function here
 * answers `not_set_up`.
 */
object SmsPhone {
    const val FEATURE = "sylloo_feature_sms_send"

    fun enabled(context: Context): Boolean = SyllooFeatures.on(context, FEATURE)

    fun canSend(context: Context): Boolean = SyllooCoordinator.hasPermission(context, Manifest.permission.SEND_SMS)

    fun canListSims(context: Context): Boolean = SyllooCoordinator.hasPermission(context, Manifest.permission.READ_PHONE_STATE)

    /** The active SIMs, by slot (SIM 1 first), with the operator's name. Empty without READ_PHONE_STATE. */
    @SuppressLint("MissingPermission")
    fun sims(context: Context): List<SimInfo> {
        if (!canListSims(context)) return emptyList()
        return try {
            val manager = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
            (manager.activeSubscriptionInfoList ?: emptyList())
                .filter { it.simSlotIndex >= 0 }
                .map { SimInfo(it.simSlotIndex, (it.carrierName?.toString() ?: it.displayName?.toString() ?: "").trim(), it.subscriptionId) }
                .sortedBy { it.slot }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    fun defaultSlot(context: Context): Int? {
        val id = SubscriptionManager.getDefaultSmsSubscriptionId()
        return sims(context).firstOrNull { it.subscriptionId == id }?.slot
    }

    fun airplane(context: Context): Boolean = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    /** ServiceState's state of that SIM (0 in service, 1 out of service, 2 emergency only, 3 off), null when unknown. */
    @SuppressLint("MissingPermission")
    fun serviceState(context: Context, subscriptionId: Int): Int? = try {
        context.getSystemService(TelephonyManager::class.java)?.createForSubscriptionId(subscriptionId)?.serviceState?.state
    } catch (e: Exception) {
        null
    }

    fun readiness(context: Context, slot: Int): String? {
        val sim = SimChoice.pick(sims(context), slot)
        return SmsReadiness.reason(
            canSend = canSend(context) && canListSims(context),
            simPresent = sim != null,
            airplane = airplane(context),
            serviceState = sim?.let { serviceState(context, it.subscriptionId) },
        )
    }

    @Suppress("DEPRECATION")
    fun manager(context: Context, subscriptionId: Int): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscriptionId)
        else SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
}

/**
 * Sms.Send's sending: one text from one SIM, in parts when long, with a sent report per part (waited for here) and a
 * delivery report per part (handled later by {@see SmsResultReceiver}). The number and the text are only passed to
 * Android; nothing of them is written to a log or a file.
 */
class SmsSender(private val context: Context) {

    fun send(to: String, text: String, sim: SimInfo, messageId: String?, eventId: String? = null, timeoutMs: Long = TIMEOUT_MS): SendOutcome {
        val manager = try {
            SmsPhone.manager(context, sim.subscriptionId)
        } catch (e: Exception) {
            return SendOutcome.failed(SmsFailure.NO_SERVICE)
        }

        val parts = try {
            ArrayList(manager.divideMessage(text).ifEmpty { SmsText.split(text) })
        } catch (e: Exception) {
            ArrayList(SmsText.split(text))
        }

        val key = UUID.randomUUID().toString()
        val waiting = SmsResults.expect(key, parts.size)
        val sent = ArrayList<PendingIntent>()
        val delivered = ArrayList<PendingIntent>()

        for (part in parts.indices) {
            sent.add(SmsResultReceiver.pending(context, SmsResultReceiver.ACTION_SENT, key, messageId, eventId, part, parts.size))
            delivered.add(SmsResultReceiver.pending(context, SmsResultReceiver.ACTION_DELIVERED, key, messageId, eventId, part, parts.size))
        }

        try {
            if (parts.size == 1) manager.sendTextMessage(to, null, parts[0], sent[0], delivered[0])
            else manager.sendMultipartTextMessage(to, null, parts, sent, delivered)
        } catch (e: SecurityException) {
            SmsResults.forget(key)
            return SendOutcome.failed(SmsFailure.PERMISSION)
        } catch (e: IllegalArgumentException) {
            SmsResults.forget(key)
            return SendOutcome.failed(SmsFailure.WRONG_NUMBER)
        } catch (e: Exception) {
            SmsResults.forget(key)
            return SendOutcome.failed(SmsFailure.OTHER)
        }

        val answered = waiting.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        SmsResults.forget(key)
        if (!answered) return SendOutcome.NO_ANSWER

        val reason = SmsFailure.first(waiting.reasons.toList())
        return if (reason == null) SendOutcome.SENT else SendOutcome.failed(reason)
    }

    companion object {
        const val TIMEOUT_MS = 90_000L
    }
}

/** The sent reports of the SMS being sent now, in this process (the sender waits on them). */
object SmsResults {
    class Waiting(parts: Int) {
        val latch = CountDownLatch(parts)
        val reasons = arrayOfNulls<String>(parts)
        val seen = BooleanArray(parts)
    }

    private val waits = ConcurrentHashMap<String, Waiting>()

    fun expect(key: String, parts: Int): Waiting = Waiting(parts).also { waits[key] = it }

    fun forget(key: String) {
        waits.remove(key)
    }

    fun sent(key: String, part: Int, resultCode: Int, errorCode: Int?) {
        val waiting = waits[key] ?: return
        synchronized(waiting) {
            if (part !in waiting.seen.indices || waiting.seen[part]) return
            waiting.seen[part] = true
            waiting.reasons[part] = SmsFailure.reason(resultCode, errorCode)
        }
        waiting.latch.countDown()
    }
}

/**
 * Android's sent and delivered reports (explicit, not exported; declared by the build hook only with `sms_send` on).
 * A delivered report of an ERP message is counted per part (only the id and the counts are kept) and, once every part
 * arrived, reported to the ERP as `delivered`; for a plain Sms.Send it is the event `SmsAnswered` (`delivered`).
 */
class SmsResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val part = intent.getIntExtra(EXTRA_PART, -1)
        val parts = intent.getIntExtra(EXTRA_PARTS, 1)
        val messageId = intent.getStringExtra(EXTRA_ID)
        val eventId = intent.getStringExtra(EXTRA_EVENT)

        when (intent.action) {
            ACTION_SENT -> SmsResults.sent(key, part, resultCode, if (intent.hasExtra("errorCode")) intent.getIntExtra("errorCode", -1) else null)
            ACTION_DELIVERED -> {
                val status = deliveryStatus(intent) ?: return
                if (!DeliveryTally.isDone(status)) return
                val pending = goAsync()
                Thread {
                    try {
                        if (messageId != null && Inbox.isId(messageId)) {
                            if (MessagesDeliveries.partDone(context, messageId, part, parts)) MessagesWork.reportDelivered(context, messageId)
                        } else if (eventId != null && MessagesDeliveries.partDone(context, "event-$key", part, parts)) {
                            SyllooEvents.send("SmsAnswered", SyllooEvents.payload(eventId, "delivered"))
                        }
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
    }

    private fun deliveryStatus(intent: Intent): Int? {
        val pdu = intent.getByteArrayExtra("pdu") ?: return null
        return try {
            SmsMessage.createFromPdu(pdu, intent.getStringExtra("format") ?: SmsMessage.FORMAT_3GPP)?.status
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val ACTION_SENT = "com.rishadblack.plugins.sylloo_app_native.SMS_SENT"
        const val ACTION_DELIVERED = "com.rishadblack.plugins.sylloo_app_native.SMS_DELIVERED"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_ID = "message_id"
        private const val EXTRA_EVENT = "event_id"
        private const val EXTRA_PART = "part"
        private const val EXTRA_PARTS = "parts"
        private val codes = AtomicInteger((System.currentTimeMillis() % 100_000).toInt())

        /** Mutable, so Android can add its `errorCode` and the delivery `pdu`; explicit, so only this receiver gets it. */
        fun pending(context: Context, action: String, key: String, messageId: String?, eventId: String?, part: Int, parts: Int): PendingIntent {
            val intent = Intent(context, SmsResultReceiver::class.java).apply {
                this.action = action
                putExtra(EXTRA_KEY, key)
                putExtra(EXTRA_PART, part)
                putExtra(EXTRA_PARTS, parts)
                messageId?.let { putExtra(EXTRA_ID, it) }
                eventId?.let { putExtra(EXTRA_EVENT, it) }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            return PendingIntent.getBroadcast(context, codes.incrementAndGet(), intent, flags)
        }
    }
}

/**
 * Sms.Send / Sms.Sims / Sms.Permission (phone-messaging-plan.md §5.1), off unless `features.sms_send`.
 *
 * - Sms.Send `{to (E.164), text, slot, id}`: sends from the SIM in that slot; answers `started` and then the event
 *   `SmsAnswered` (`sent` | `failed` with `reason` | `no_answer`, later `delivered`). Messages.Work sends the ERP's
 *   messages itself; this is for a single SMS the app sends while open.
 * - Sms.Sims: `{status: listed, sims: [{slot, label, operator}], default_slot}` or `{status: permission}` before the
 *   person allowed it. Android gives no SIM number, so a SIM is named by slot and operator.
 * - Sms.Permission `{id}`: Android's own question for SEND_SMS and READ_PHONE_STATE; the answer is the event
 *   `SmsPermissionAnswered` (`granted` | `denied`).
 */
object SmsFunctions {
    private val E164 = Regex("^\\+[1-9][0-9]{6,14}$")
    const val MAX_TEXT = 2000

    private fun off(): Map<String, Any> = BridgeResponse.success(mapOf("status" to "not_set_up"))

    class Send(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            if (!SmsPhone.enabled(context)) return off()
            val id = parameters["id"] as? String
            val to = (parameters["to"] as? String)?.trim()
            val text = parameters["text"] as? String
            val slot = (parameters["slot"] as? Number)?.toInt()

            if (to == null || !E164.matches(to)) return BridgeResponse.error("INVALID_PARAMETERS", "Not a number in E.164")
            if (text.isNullOrEmpty() || text.length > MAX_TEXT) return BridgeResponse.error("INVALID_PARAMETERS", "Text missing or too long")
            if (!SmsPhone.canSend(context)) return BridgeResponse.success(mapOf("status" to "permission"))

            val sims = SmsPhone.sims(context)
            val sim = SimChoice.pick(sims, slot ?: SmsPhone.defaultSlot(context)) ?: sims.firstOrNull()
                ?: return BridgeResponse.success(mapOf("status" to "no_sim"))

            Thread {
                val outcome = SmsSender(context.applicationContext).send(to, text, sim, null, id ?: "sms")
                val payload = when {
                    !outcome.answered -> SyllooEvents.payload(id, "no_answer")
                    outcome.sent -> SyllooEvents.payload(id, "sent")
                    else -> SyllooEvents.payload(id, "failed").put("reason", outcome.reason ?: SmsFailure.OTHER)
                }
                SyllooEvents.send("SmsAnswered", payload)
            }.start()

            return BridgeResponse.success(mapOf("status" to "started", "slot" to sim.slot))
        }
    }

    class Sims(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            if (!SmsPhone.enabled(context)) return BridgeResponse.success(mapOf("status" to "not_set_up", "sims" to emptyList<Any>()))
            if (!SmsPhone.canListSims(context)) return BridgeResponse.success(mapOf("status" to "permission", "sims" to emptyList<Any>()))

            val sims = SmsPhone.sims(context).map { mapOf("slot" to it.slot, "label" to SimChoice.label(it.slot, it.label), "operator" to it.label) }
            val answer = mutableMapOf<String, Any>("status" to "listed", "sims" to sims, "can_send" to SmsPhone.canSend(context))
            SmsPhone.defaultSlot(context)?.let { answer["default_slot"] = it }
            return BridgeResponse.success(answer)
        }
    }

    class Permission(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            if (!SmsPhone.enabled(context)) return off()
            val id = parameters["id"] as? String
            val wanted = arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE)

            if (SmsPhone.canSend(context) && SmsPhone.canListSims(context)) {
                SyllooEvents.send("SmsPermissionAnswered", SyllooEvents.payload(id, "granted"))
                return BridgeResponse.success(mapOf("status" to "granted"))
            }

            val started = SyllooCoordinator.run { coordinator, _ ->
                coordinator.withPermissions(wanted) { granted ->
                    SyllooEvents.send("SmsPermissionAnswered", SyllooEvents.payload(id, if (granted) "granted" else "denied"))
                }
            }

            return BridgeResponse.success(mapOf("status" to if (started) "asking" else "failed"))
        }
    }
}
