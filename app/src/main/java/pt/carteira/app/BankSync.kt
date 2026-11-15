package pt.carteira.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale

internal const val BANK_API_BASE = "https://carteira-classificacao.faint-foxtail.workers.dev"

internal data class BankAccountInfo(val uid: String, val iban: String?, val name: String, val aspsp: String, val validUntil: String?)
internal data class BankTransaction(val id: String?, val date: String, val amount: Double, val description: String, val pending: Boolean = false, val bookingDate: String = date)
internal data class BankSyncResult(val imported: Int, val duplicates: Int, val unassignedExpenses: Int, val dateFrom: String, val pending: Int = 0, val confirmed: Int = 0, val received: Int = 0, val raw: Int = 0)

private const val PREFS = "carteira_prefs"

internal fun bankDeviceToken(context: Context): String {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    prefs.getString("bank_device_token", null)?.let { return it }
    val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    prefs.edit().putString("bank_device_token", token).apply()
    return token
}

internal fun bankLinkedUid(context: Context, accountId: Long): String? =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("bank_link_$accountId", null)

internal fun setBankLinkedUid(context: Context, accountId: Long, uid: String?) {
    val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
    if (uid == null) edit.remove("bank_link_$accountId").remove("bank_last_sync_$accountId") else edit.putString("bank_link_$accountId", uid)
    edit.apply()
}

internal fun bankLastSync(context: Context, accountId: Long): String? =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("bank_last_sync_$accountId", null)

internal fun setBankLastSync(context: Context, accountId: Long, day: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("bank_last_sync_$accountId", day).apply()
}

internal fun bankDayKey(millis: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(java.util.Date(millis))

internal fun bankDayToMillis(day: String): Long? = runCatching {
    SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false }.parse(day)?.time
}.getOrNull()

private fun request(path: String, method: String, body: JSONObject? = null): JSONObject {
    val connection = (URL(BANK_API_BASE + path).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 20000
        readTimeout = 60000
        setRequestProperty("Accept", "application/json")
        if (body != null) {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
    }
    try {
        if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
        if (code !in 200..299) error(json.optString("error").ifBlank { "O serviço do banco respondeu com erro ($code)." })
        return json
    } finally {
        connection.disconnect()
    }
}

internal fun fetchBanks(country: String = "PT"): List<String> {
    val banks = request("/api/bank/banks?country=" + URLEncoder.encode(country, "UTF-8"), "GET").optJSONArray("banks") ?: JSONArray()
    return (0 until banks.length()).mapNotNull { banks.optJSONObject(it)?.optString("name")?.takeIf { name -> name.isNotBlank() } }
}

internal fun startBankLink(deviceToken: String, aspsp: String, country: String = "PT"): String {
    val url = request("/api/bank/start", "POST", JSONObject().put("deviceToken", deviceToken).put("aspsp", aspsp).put("country", country)).optString("url")
    require(url.startsWith("https://")) { "O serviço do banco não devolveu uma ligação válida." }
    return url
}

internal fun fetchBankAccounts(deviceToken: String): List<BankAccountInfo> {
    val list = request("/api/bank/accounts", "POST", JSONObject().put("deviceToken", deviceToken)).optJSONArray("accounts") ?: JSONArray()
    return (0 until list.length()).mapNotNull { index ->
        val a = list.optJSONObject(index) ?: return@mapNotNull null
        val uid = a.optString("uid").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        BankAccountInfo(uid, a.optString("iban").takeIf { it.isNotBlank() && it != "null" }, a.optString("name").ifBlank { "Conta" }, a.optString("aspsp"), a.optString("validUntil").takeIf { it.isNotBlank() && it != "null" })
    }
}

internal data class BankTransactionsResponse(val transactions: List<BankTransaction>, val raw: Int)

internal fun fetchBankTransactions(deviceToken: String, uid: String, dateFrom: String): BankTransactionsResponse {
    val response = request("/api/bank/transactions", "POST", JSONObject().put("deviceToken", deviceToken).put("accountUid", uid).put("dateFrom", dateFrom))
    val list = response.optJSONArray("transactions") ?: JSONArray()
    return BankTransactionsResponse((0 until list.length()).mapNotNull { index ->
        val t = list.optJSONObject(index) ?: return@mapNotNull null
        val amount = t.optDouble("amount", Double.NaN)
        val date = t.optString("date")
        if (!amount.isFinite() || amount == 0.0 || bankDayToMillis(date) == null) return@mapNotNull null
        val booking = t.optString("bookingDate").takeIf { bankDayToMillis(it) != null } ?: date
        BankTransaction(t.optString("id").takeIf { it.isNotBlank() && it != "null" }, date, amount, t.optString("description").ifBlank { "Movimento" }, t.optBoolean("pending", false), booking)
    }, response.optInt("raw", list.length()))
}
