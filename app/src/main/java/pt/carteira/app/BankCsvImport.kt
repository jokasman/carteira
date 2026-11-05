package pt.carteira.app

import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.text.Normalizer
import java.text.ParsePosition
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal data class BankCsvMovement(
    val date: Long,
    val description: String,
    val signedAmount: Double,
    val balance: Double?,
    val occurrence: Int = 0
)

internal data class ParsedBankCsv(
    val movements: List<BankCsvMovement>,
    val skippedRows: Int
) {
    val expenses: Int get() = movements.count { it.signedAmount < 0 }
    val income: Int get() = movements.size - expenses
}

internal data class CsvImportResult(
    val imported: Int,
    val duplicates: Int,
    val unassignedExpenses: Int,
    val skippedRows: Int
)

internal fun normalizeMovementKey(value: String): String {
    val ascii = Normalizer.normalize(value.trim().uppercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
    return ascii
        .replace(Regex("\\b\\d{6,}\\b"), " ")
        .replace(Regex("[^A-Z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}

internal fun parseMillenniumCsv(bytes: ByteArray): ParsedBankCsv {
    require(bytes.isNotEmpty()) { "O ficheiro está vazio." }
    val text = decodeCsv(bytes)
    val selected = listOf(';', '\t', ',', '|').firstNotNullOfOrNull { delimiter ->
        val rows = parseCsvRows(text, delimiter)
        val header = rows.mapIndexedNotNull { index, row ->
            val columns = row.map(::normalizeMovementKey)
            val descriptionIndex = columns.indexOfFirst { it in setOf("DESCRICAO", "DESCRICAO DO MOVIMENTO", "MOVIMENTO") }
            val amountIndex = columns.indexOfFirst { it in setOf("MONTANTE", "VALOR", "IMPORTANCIA") }
            if (descriptionIndex >= 0 && amountIndex >= 0) CsvHeader(index, row, descriptionIndex, amountIndex) else null
        }.firstOrNull()
        header?.let { it to rows }
    } ?: throw IllegalArgumentException("Não encontrei as colunas de descrição e montante neste CSV.")
    val candidate = selected.first
    val rows = selected.second

    val headerNames = candidate.values.map(::normalizeMovementKey)
    val dateIndex = headerNames.indexOfFirst { it in setOf("DATA LANCAMENTO", "DATA DE LANCAMENTO", "DATA MOVIMENTO", "DATA") }
    val typeIndex = headerNames.indexOfFirst { it in setOf("TIPO", "TIPO MOVIMENTO") }
    val balanceIndex = headerNames.indexOfFirst { it in setOf("SALDO", "SALDO APOS MOVIMENTO") }
    require(dateIndex >= 0) { "O CSV não contém uma coluna de data reconhecida." }

    var skipped = 0
    val movements = mutableListOf<BankCsvMovement>()
    val repeatedRows = mutableMapOf<String, Int>()
    for (row in rows.drop(candidate.headerIndex + 1)) {
        if (row.all { it.isBlank() }) continue
        if (row.map(::normalizeMovementKey) == headerNames) continue
        val date = row.getOrNull(dateIndex)?.let(::parseBankDate)
        val amount = row.getOrNull(candidate.amountIndex)?.let(::parseBankAmount)
        val description = row.getOrNull(candidate.descriptionIndex)?.trim().orEmpty()
        if (date == null || amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
            skipped++
            continue
        }
        val type = row.getOrNull(typeIndex).orEmpty().let(::normalizeMovementKey)
        val isExpense = when {
            type.startsWith("DEBITO") -> true
            type.startsWith("CREDITO") -> false
            else -> amount.signum() < 0
        }
        val absolute = amount.abs().setScale(2, RoundingMode.HALF_UP).toDouble()
        val signed = if (isExpense) -absolute else absolute
        val parsedBalance = row.getOrNull(balanceIndex)?.let(::parseBankAmount)
        val balance = parsedBalance?.toDouble()
        val repeatedKey = listOf(
            date.time.toString(),
            normalizeMovementKey(description),
            signed.toString(),
            parsedBalance?.stripTrailingZeros()?.toPlainString().orEmpty()
        ).joinToString("|")
        val occurrence = repeatedRows.getOrDefault(repeatedKey, 0)
        repeatedRows[repeatedKey] = occurrence + 1
        val calendar = Calendar.getInstance().apply {
            timeInMillis = date.time
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        movements += BankCsvMovement(calendar.timeInMillis, description.ifBlank { "Movimento importado" }, signed, balance, occurrence)
    }
    require(movements.isNotEmpty()) { "Não encontrei movimentos válidos no CSV." }
    return ParsedBankCsv(movements, skipped)
}

private data class CsvHeader(
    val headerIndex: Int,
    val values: List<String>,
    val descriptionIndex: Int,
    val amountIndex: Int
)

private fun decodeCsv(bytes: ByteArray): String {
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
        return bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
    }
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
        return bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
    }
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
        return bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)
    }
    val zeroBytes = bytes.count { it == 0.toByte() }
    if (zeroBytes > bytes.size / 10) return bytes.toString(Charsets.UTF_16LE)
    return runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrElse { bytes.toString(Charset.forName("windows-1252")) }
}

private fun parseCsvRows(text: String, delimiter: Char): List<List<String>> {
    val result = mutableListOf<List<String>>()
    val row = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var index = 0
    while (index < text.length) {
        val character = text[index]
        when {
            character == '"' && quoted && index + 1 < text.length && text[index + 1] == '"' -> {
                cell.append('"')
                index++
            }
            character == '"' -> quoted = !quoted
            character == delimiter && !quoted -> {
                row += cell.toString()
                cell.setLength(0)
            }
            (character == '\n' || character == '\r') && !quoted -> {
                row += cell.toString()
                cell.setLength(0)
                if (row.any { it.isNotBlank() }) result += row.toList()
                row.clear()
                if (character == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
            }
            else -> cell.append(character)
        }
        index++
    }
    if (cell.isNotEmpty() || row.isNotEmpty()) {
        row += cell.toString()
        if (row.any { it.isNotBlank() }) result += row.toList()
    }
    return result
}

private fun parseBankDate(value: String): Date? {
    val formats = listOf("dd-MM-yyyy", "dd/MM/yyyy", "yyyy-MM-dd", "dd.MM.yyyy")
    return formats.firstNotNullOfOrNull { format ->
        runCatching {
            val trimmed = value.trim()
            val position = ParsePosition(0)
            SimpleDateFormat(format, Locale("pt", "PT")).apply { isLenient = false }
                .parse(trimmed, position)
                ?.takeIf { position.index == trimmed.length }
        }.getOrNull()
    }
}

private fun parseBankAmount(value: String): BigDecimal? {
    var clean = value.trim()
        .replace("\u00A0", "")
        .replace("\u202F", "")
        .replace(" ", "")
        .replace("€", "")
        .replace("EUR", "", ignoreCase = true)
        .replace("−", "-")
    if (clean.startsWith("(") && clean.endsWith(")")) clean = "-${clean.substring(1, clean.length - 1)}"
    val comma = clean.lastIndexOf(',')
    val dot = clean.lastIndexOf('.')
    clean = when {
        comma >= 0 && dot >= 0 && comma > dot -> clean.replace(".", "").replace(',', '.')
        comma >= 0 && dot >= 0 -> clean.replace(",", "")
        comma >= 0 -> clean.replace('.', ' ').replace(" ", "").replace(',', '.')
        clean.count { it == '.' } > 1 -> {
            val lastDot = clean.lastIndexOf('.')
            clean.substring(0, lastDot).replace(".", "") + clean.substring(lastDot)
        }
        clean.count { it == '.' } == 1 && clean.substringAfter('.').length == 3 -> clean.replace(".", "")
        else -> clean
    }
    return clean.toBigDecimalOrNull()
}
