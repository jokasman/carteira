package pt.carteira.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal const val CLASSIFICATION_REQUEST_SCHEMA = "pt.carteira.classification-request"
internal const val CLASSIFICATION_RESPONSE_SCHEMA = "pt.carteira.classification-response"
internal const val CLASSIFICATION_SCHEMA_VERSION = 2

internal data class ClassificationBudgetOption(val id: Long, val name: String)

internal data class ClassificationRequestItem(
    val transactionId: Long,
    val date: Long,
    val title: String,
    val displayTitle: String,
    val amount: Double,
    val budgets: List<ClassificationBudgetOption>,
    val active: Boolean,
    val settlementIncomeId: Long?
)

internal data class ClassificationRequestIncome(
    val transactionId: Long,
    val date: Long,
    val title: String,
    val displayTitle: String,
    val amount: Double,
    val active: Boolean,
    val previousExpenseIds: List<Long>
)

internal data class ClassificationRequest(
    val requestId: String,
    val createdAt: Long,
    val expenses: List<ClassificationRequestItem>,
    val incomes: List<ClassificationRequestIncome>
)

internal data class ClassificationResponseAssignment(val transactionId: Long, val budgetId: Long)
internal data class ClassificationResponseSettlement(val incomeId: Long, val expenseIds: List<Long>)
internal data class ClassificationResponseExpensePart(val title: String, val amount: Double, val budgetId: Long?)
internal data class ClassificationResponseExpenseSplit(val transactionId: Long, val parts: List<ClassificationResponseExpensePart>)

internal data class ClassificationResponse(
    val requestId: String,
    val assignments: List<ClassificationResponseAssignment>,
    val settlements: List<ClassificationResponseSettlement>,
    val expenseSplits: List<ClassificationResponseExpenseSplit> = emptyList()
)

internal data class ClassificationImportAssignment(
    val transactionId: Long,
    val budgetId: Long,
    val movementTitle: String,
    val budgetName: String
)

internal data class ClassificationImportSettlement(
    val incomeId: Long,
    val incomeTitle: String,
    val incomeAmount: Double,
    val expenseIds: List<Long>,
    val expenseTitles: List<String>
)

internal data class ClassificationImportExpensePart(
    val title: String,
    val amount: Double,
    val budgetId: Long?,
    val budgetName: String?
)

internal data class ClassificationImportExpenseSplit(
    val transactionId: Long,
    val sourceTitle: String,
    val parts: List<ClassificationImportExpensePart>
)

internal data class ClassificationImportPreview(
    val requestId: String,
    val assignments: List<ClassificationImportAssignment>,
    val settlements: List<ClassificationImportSettlement>,
    val expenseSplits: List<ClassificationImportExpenseSplit> = emptyList()
)

internal data class ValidatedClassificationChanges(
    val budgetUpdates: List<FinanceTransaction>,
    val incomeUpdates: List<FinanceTransaction>,
    val settlementExpenseUpdates: List<FinanceTransaction>,
    val expensePartUpdates: List<FinanceTransaction>,
    val preview: ClassificationImportPreview
)

internal fun ClassificationRequest.toJson(): JSONObject = JSONObject()
    .put("schema", CLASSIFICATION_REQUEST_SCHEMA)
    .put("version", CLASSIFICATION_SCHEMA_VERSION)
    .put("requestId", requestId)
    .put("createdAt", createdAt)
    .put("expenses", JSONArray().apply {
        expenses.forEach { item ->
            put(JSONObject()
                .put("transactionId", item.transactionId)
                .put("date", item.date)
                .put("title", item.title)
                .put("displayTitle", item.displayTitle)
                .put("amount", item.amount)
                .put("active", item.active)
                .put("settlementIncomeId", item.settlementIncomeId ?: JSONObject.NULL)
                .put("budgets", JSONArray().apply {
                    item.budgets.forEach { budget ->
                        put(JSONObject().put("id", budget.id).put("name", budget.name))
                    }
                })
            )
        }
    })
    .put("incomes", JSONArray().apply {
        incomes.forEach { income ->
            put(JSONObject()
                .put("transactionId", income.transactionId)
                .put("date", income.date)
                .put("title", income.title)
                .put("displayTitle", income.displayTitle)
                .put("amount", income.amount)
                .put("active", income.active)
                .put("previousExpenseIds", JSONArray().apply { income.previousExpenseIds.forEach { put(it) } })
            )
        }
    })

internal fun parseClassificationRequest(raw: String): ClassificationRequest {
    val root = JSONObject(raw)
    require(root.optString("schema") == CLASSIFICATION_REQUEST_SCHEMA) { "Este ficheiro não é um pedido Carteira válido." }
    require(root.optInt("version") == CLASSIFICATION_SCHEMA_VERSION) { "A versão deste pedido não é compatível com a Carteira." }
    val requestId = canonicalRequestId(root.optString("requestId"))
    val expensesJson = root.optJSONArray("expenses") ?: error("O pedido não contém despesas.")
    val incomesJson = root.optJSONArray("incomes") ?: JSONArray()
    require(expensesJson.length() in 1..500 && expensesJson.length() + incomesJson.length() <= 500) {
        "O pedido deve conter entre 1 e 500 movimentos."
    }
    val seenIds = mutableSetOf<Long>()
    val expenses = (0 until expensesJson.length()).map { index ->
        val value = expensesJson.getJSONObject(index)
        val transactionId = value.getLong("transactionId")
        require(transactionId > 0 && seenIds.add(transactionId)) { "O pedido contém um movimento inválido ou repetido." }
        val title = value.optString("title")
        val displayTitle = value.optString("displayTitle", title).ifBlank { "Despesa" }
        val amount = value.getDouble("amount")
        require(amount.isFinite() && amount >= 0.01) { "O pedido contém um valor inválido." }
        val budgetsJson = value.optJSONArray("budgets") ?: error("Faltam opções de orçamento num movimento.")
        require(budgetsJson.length() <= 100) { "O pedido contém demasiadas opções de orçamento." }
        val seenBudgetIds = mutableSetOf<Long>()
        val budgets = (0 until budgetsJson.length()).map { budgetIndex ->
            val budget = budgetsJson.getJSONObject(budgetIndex)
            val id = budget.getLong("id")
            val name = budget.optString("name").trim()
            require(id > 0 && name.isNotEmpty() && seenBudgetIds.add(id)) { "O pedido contém uma opção de orçamento inválida." }
            ClassificationBudgetOption(id, name)
        }
        val settlementIncomeValue=value.opt("settlementIncomeId")
        val settlementIncomeId=if(settlementIncomeValue==null||settlementIncomeValue===JSONObject.NULL)null else (settlementIncomeValue as Number).toLong()
        require(settlementIncomeId==null||settlementIncomeId>0) { "O pedido contém uma liquidação inválida." }
        val active=value.getBoolean("active")
        require(if(settlementIncomeId==null)active else !active&&budgets.isEmpty()) { "O pedido contém uma despesa num estado inválido." }
        ClassificationRequestItem(transactionId, value.getLong("date"), title, displayTitle, amount, budgets, active, settlementIncomeId)
    }
    val incomes = (0 until incomesJson.length()).map { index ->
        val value = incomesJson.getJSONObject(index)
        val transactionId = value.getLong("transactionId")
        require(transactionId > 0 && seenIds.add(transactionId)) { "O pedido contém um movimento inválido ou repetido." }
        val title = value.optString("title")
        val displayTitle = value.optString("displayTitle", title).ifBlank { "Receita" }
        val amount = value.getDouble("amount")
        require(amount.isFinite() && amount >= 0.01) { "O pedido contém uma receita com valor inválido." }
        val previousJson=value.optJSONArray("previousExpenseIds")?:JSONArray()
        val previousIds=(0 until previousJson.length()).map{previousJson.getLong(it)}
        require(previousIds.all{it>0}&&previousIds.distinct().size==previousIds.size) { "O pedido contém despesas liquidadas inválidas." }
        ClassificationRequestIncome(transactionId, value.getLong("date"), title, displayTitle, amount, value.getBoolean("active"), previousIds)
    }
    val incomeById=incomes.associateBy{it.transactionId}
    expenses.forEach{expense->
        val incomeId=expense.settlementIncomeId
        if(incomeId!=null){
            val income=incomeById[incomeId]?:error("Falta no pedido a receita associada a uma despesa.")
            require(expense.transactionId in income.previousExpenseIds) { "A ligação de uma despesa liquidada é inconsistente." }
        }
    }
    incomes.forEach{income->
        val linked=expenses.filter{it.settlementIncomeId==income.transactionId}.map{it.transactionId}.toSet()
        require(linked==income.previousExpenseIds.toSet()) { "As despesas já liquidadas não correspondem à receita do pedido." }
        require(if(income.previousExpenseIds.isEmpty())income.active else !income.active) { "A receita está num estado inválido para liquidação." }
    }
    return ClassificationRequest(requestId, root.getLong("createdAt"), expenses, incomes)
}

internal fun parseClassificationResponse(raw: String): ClassificationResponse {
    val root = JSONObject(raw)
    require(root.optString("schema") == CLASSIFICATION_RESPONSE_SCHEMA) { "Este ficheiro não é uma resposta de classificação válida." }
    require(root.optInt("version") == CLASSIFICATION_SCHEMA_VERSION) { "A versão desta resposta não é compatível com a Carteira." }
    val requestId = canonicalRequestId(root.optString("requestId"))
    val assignmentsJson = root.optJSONArray("assignments") ?: error("A resposta não contém classificações.")
    val settlementsJson = root.optJSONArray("settlements") ?: JSONArray()
    val expenseSplitsJson = root.optJSONArray("expenseSplits") ?: JSONArray()
    require(assignmentsJson.length() + settlementsJson.length() in 1..500 && assignmentsJson.length() + settlementsJson.length() + expenseSplitsJson.length() <= 500) { "A resposta não contém alterações válidas." }
    val seenExpenseIds = mutableSetOf<Long>()
    val assignments = (0 until assignmentsJson.length()).map { index ->
        val value = assignmentsJson.getJSONObject(index)
        val transactionId = value.getLong("transactionId")
        val budgetId = value.getLong("budgetId")
        require(transactionId > 0 && budgetId > 0 && seenExpenseIds.add(transactionId)) {
            "A resposta contém uma classificação inválida ou repetida."
        }
        ClassificationResponseAssignment(transactionId, budgetId)
    }
    val seenIncomeIds = mutableSetOf<Long>()
    val settlements = (0 until settlementsJson.length()).map { index ->
        val value = settlementsJson.getJSONObject(index)
        val incomeId = value.getLong("incomeId")
        require(incomeId > 0 && seenIncomeIds.add(incomeId)) { "A resposta contém uma receita repetida." }
        val expenseIdsJson = value.optJSONArray("expenseIds") ?: error("Faltam despesas numa liquidação.")
        require(expenseIdsJson.length() in 1..500) { "Uma liquidação precisa de pelo menos uma despesa." }
        val expenseIds = (0 until expenseIdsJson.length()).map { expenseIndex ->
            val expenseId = expenseIdsJson.getLong(expenseIndex)
            require(expenseId > 0 && seenExpenseIds.add(expenseId)) { "Uma despesa foi repetida ou usada em mais de uma decisão." }
            expenseId
        }
        ClassificationResponseSettlement(incomeId, expenseIds)
    }
    val assignmentsByExpense = assignments.associateBy { it.transactionId }
    val settlementExpenseIds = settlements.flatMap { it.expenseIds }.toSet()
    val splitIds = mutableSetOf<Long>()
    val expenseSplits = (0 until expenseSplitsJson.length()).map { index ->
        val value = expenseSplitsJson.getJSONObject(index)
        val transactionId = value.getLong("transactionId")
        require(transactionId > 0 && splitIds.add(transactionId)) { "A resposta divide uma despesa mais do que uma vez." }
        val partsJson = value.optJSONArray("parts") ?: error("Faltam partes na despesa dividida.")
        require(partsJson.length() == 2) { "Uma despesa dividida tem de conter duas partes." }
        val parts = (0 until partsJson.length()).map { partIndex ->
            val part = partsJson.getJSONObject(partIndex)
            val title = part.optString("title").trim()
            val amount = part.optDouble("amount", Double.NaN)
            val budgetValue = part.opt("budgetId")
            val budgetId = if (budgetValue == null || budgetValue === JSONObject.NULL) null else (budgetValue as Number).toLong()
            require(title.isNotEmpty() && title.length <= 120 && amount.isFinite() && amount >= 0.01 && (budgetId == null || budgetId > 0)) {
                "A resposta contém uma parte de despesa inválida."
            }
            ClassificationResponseExpensePart(title, amount, budgetId)
        }
        val firstBudgetId = parts.firstNotNullOfOrNull { it.budgetId }
        val assignment = assignmentsByExpense[transactionId]
        require(if (firstBudgetId == null) assignment == null else assignment?.budgetId == firstBudgetId) {
            "A classificação da despesa dividida é inconsistente."
        }
        require(firstBudgetId == null || transactionId !in settlementExpenseIds) {
            "Uma despesa com orçamento atribuído não pode estar numa liquidação."
        }
        ClassificationResponseExpenseSplit(transactionId, parts)
    }
    return ClassificationResponse(requestId, assignments, settlements, expenseSplits)
}

internal fun ClassificationResponse.toJson(): JSONObject = JSONObject()
    .put("schema", CLASSIFICATION_RESPONSE_SCHEMA)
    .put("version", CLASSIFICATION_SCHEMA_VERSION)
    .put("requestId", requestId)
    .put("assignments", JSONArray().apply {
        assignments.forEach { assignment ->
            put(JSONObject().put("transactionId", assignment.transactionId).put("budgetId", assignment.budgetId))
        }
    })
    .put("settlements", JSONArray().apply {
        settlements.forEach { settlement ->
            put(JSONObject()
                .put("incomeId", settlement.incomeId)
                .put("expenseIds", JSONArray().apply { settlement.expenseIds.forEach { put(it) } })
            )
        }
    })
    .put("expenseSplits", JSONArray().apply {
        expenseSplits.forEach { split ->
            put(JSONObject()
                .put("transactionId", split.transactionId)
                .put("parts", JSONArray().apply {
                    split.parts.forEach { part ->
                        put(JSONObject()
                            .put("title", part.title)
                            .put("amount", part.amount)
                            .put("budgetId", part.budgetId ?: JSONObject.NULL)
                        )
                    }
                })
            )
        }
    })

internal fun classificationRequestFile(context: Context, requestId: String): File {
    val id = canonicalRequestId(requestId)
    return File(classificationRequestsDirectory(context), "$id.json")
}

internal fun saveClassificationRequest(context: Context, request: ClassificationRequest): File {
    val directory = classificationRequestsDirectory(context)
    cleanupExpiredClassificationRequests(context)
    val destination = classificationRequestFile(context, request.requestId)
    val temporary = File(directory, ".${request.requestId}.tmp")
    temporary.writeText(request.toJson().toString(), Charsets.UTF_8)
    if (!temporary.renameTo(destination)) {
        temporary.delete()
        error("Não foi possível guardar o pedido de classificação.")
    }
    return destination
}

internal fun cleanupExpiredClassificationRequests(context: Context) {
    val directory = File(context.filesDir, "classification/requests")
    if (!directory.isDirectory) return
    val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
    directory.listFiles()?.filter {
        it.isFile && (it.extension == "tmp" || (it.extension == "json" && it.lastModified() < cutoff))
    }?.forEach { it.delete() }
}

private fun classificationRequestsDirectory(context: Context): File =
    File(context.filesDir, "classification/requests").apply {
        if (!exists() && !mkdirs()) error("Não foi possível preparar o pedido de classificação.")
    }

private fun canonicalRequestId(value: String): String {
    val canonical = try { UUID.fromString(value).toString() } catch (_: IllegalArgumentException) { "" }
    require(canonical == value.lowercase()) { "O identificador do pedido é inválido." }
    return canonical
}
