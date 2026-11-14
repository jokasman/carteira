package pt.carteira.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

private val Ink = Color(0xFF18352D)
private val Green = Color(0xFF3D8A68)
private val Mint = Color(0xFFE9F3EC)
private val Paper = Color(0xFFF7F8F5)
private val Muted = Color(0xFF78827D)
private val Coral = Color(0xFFC45E50)

class FinanceModel(private val dao: FinanceDao) : ViewModel() {
    private val accountMutex = Mutex()
    val accounts = dao.accounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val transactions = dao.transactions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val budgets = dao.budgets().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val categories = dao.categories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val budgetPeriods = dao.budgetPeriods().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val budgetRules = dao.budgetRules().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val budgetClosures = dao.budgetClosures().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    init {
        viewModelScope.launch {
            ensureDefaultAccount()
            dao.addCategories(DefaultCategoryOptions.map { (name, kind) -> Category(name = name, kind = kind) })
            ensureBudgetHistory()
        }
    }
    private suspend fun ensureDefaultAccount(): Account = accountMutex.withLock {
        dao.firstAccount() ?: run {
            dao.addAccount(Account(name = "Dinheiro", type = "Carteira"))
            dao.firstAccount() ?: error("Não foi possível criar a conta inicial")
        }
    }
    private suspend fun ensureBudgetHistory() {
        val currentMonth=currentMonthStart()
        dao.allBudgets().forEach { budget ->
            val history=dao.periodsForBudget(budget.id).sortedBy{it.monthStart}
            var last=history.lastOrNull()
            if(last==null){
                dao.saveBudgetPeriod(BudgetPeriod(budget.id,currentMonth,budget.limitAmount))
                last=BudgetPeriod(budget.id,currentMonth,budget.limitAmount)
            }
            var month=last.monthStart
            while(month<currentMonth){
                month=shiftMonth(month,1)
                if(month<=currentMonth) dao.saveBudgetPeriod(BudgetPeriod(budget.id,month,last.limitAmount))
            }
        }
    }
    fun saveAccount(value: Account) = viewModelScope.launch {
        if(value.name.isNotBlank() && value.openingBalance.isFinite()) {
            val account=value.copy(name=value.name.trim())
            if(account.id==0L) dao.addAccount(account) else dao.updateAccount(account)
        }
    }
    fun saveTransaction(value: FinanceTransaction, rememberBudgetRule:Boolean=false, onComplete:(Boolean)->Unit = {}) = viewModelScope.launch {
        val saved = try {
            val current=if(value.id>0)dao.transactionById(value.id)else null
            val settlementLocked=current?.settlementRevenueId!=null||current?.let{it.kind=="Receita"&&dao.settlementExpenses(it.id).isNotEmpty()}==true
            val source = dao.accountById(value.accountId) ?: ensureDefaultAccount()
            val ruleKey=normalizeMovementKey(value.title)
            // O orçamento escolhido (ou a ausência dele) é respeitado; a regra memorizada só serve de sugestão no ecrã.
            val candidate=value
            val staleRuleKey=if(current?.kind=="Despesa"&&current.budgetId!=null&&value.kind=="Despesa"&&value.budgetId==null&&ruleKey.isNotBlank())
                dao.budgetRule(ruleKey)?.takeIf{it.budgetId==current.budgetId}?.merchantKey else null
            val budget = candidate.budgetId?.let { dao.budgetById(it) }
            val budgetChanged=candidate.budgetId!=null&&(candidate.budgetId!=current?.budgetId||current?.date?.let{currentMonthStart(it)}!=currentMonthStart(candidate.date))
            val closedBudget=budgetChanged&&budgetClosedInMonth(candidate.budgetId!!,currentMonthStart(candidate.date))
            val validBudget = when (candidate.kind) {
                "Despesa" -> (candidate.budgetId == null || budget != null)&&!closedBudget
                else -> candidate.budgetId == null
            }
            val validDestination = candidate.kind != "Transferência" ||
                (candidate.toAccountId != null && candidate.toAccountId != source.id && dao.accountById(candidate.toAccountId) != null)
            val validAmount=candidate.amount.isFinite() && (if(candidate.kind=="Ajuste") kotlin.math.abs(candidate.amount)>=0.01 else candidate.amount>=0.01)
            if (settlementLocked || candidate.kind !in listOf("Despesa", "Receita", "Transferência", "Ajuste") ||
                !validAmount ||
                candidate.category.isBlank() || !dao.categoryExists(candidate.category, candidate.kind) ||
                !validBudget || !validDestination) false
            else {
                val normalized = candidate.copy(
                    title = candidate.title.trim(),
                    amount = BigDecimal.valueOf(candidate.amount).setScale(2, RoundingMode.HALF_UP).toDouble(),
                    accountId = source.id,
                    note = candidate.note.trim()
                )
                val rule=if(rememberBudgetRule&&normalized.kind=="Despesa"&&normalized.budgetId!=null&&ruleKey.isNotBlank())
                    BudgetRule(ruleKey,normalized.title,normalized.budgetId) else null
                dao.saveTransactionAndRule(normalized,rule,staleRuleKey)
                true
            }
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
        onComplete(saved)
    }
    internal fun importCsv(accountId:Long,csv:ParsedBankCsv,onComplete:(CsvImportResult?,String?)->Unit)=viewModelScope.launch {
        try {
            if(dao.accountById(accountId)==null) error("Seleciona uma conta válida para importar os movimentos.")
            val rules=dao.allBudgetRules().associateBy{it.merchantKey}
            val allBudgets=dao.allBudgets().associateBy{it.id}
            val closedMonths=dao.allBudgetClosures().map{it.budgetId to it.monthStart}.toSet()
            val importedRows=csv.movements.map{movement->
                val expense=movement.signedAmount<0
                val key=normalizeMovementKey(movement.description)
                val rule=if(expense)rules[key] else null
                val budget=rule?.let{allBudgets[it.budgetId]}
                val budgetId=budget?.takeIf{budgetVisibleInMonth(it,currentMonthStart(movement.date))&&(it.id to currentMonthStart(movement.date)) !in closedMonths}?.id
                val amount=BigDecimal.valueOf(kotlin.math.abs(movement.signedAmount)).setScale(2,RoundingMode.HALF_UP).toDouble()
                val importKey=csvImportKey(accountId,movement,key)
                FinanceTransaction(
                    title=movement.description,
                    amount=amount,
                    kind=if(expense)"Despesa" else "Receita",
                    category=if(expense)"Outros" else "Outros rendimentos",
                    accountId=accountId,
                    date=movement.date,
                    budgetId=budgetId,
                    importKey=importKey,
                    importedFromCsv=true
                )
            }
            val results=if(importedRows.isEmpty()) emptyList() else dao.importTransactions(importedRows)
            val importedIndices=results.indices.filter{results[it]>=0L}
            val result=CsvImportResult(
                imported=importedIndices.size,
                duplicates=results.count{it<0L},
                unassignedExpenses=importedIndices.count{importedRows[it].kind=="Despesa"&&importedRows[it].budgetId==null},
                skippedRows=csv.skippedRows
            )
            onComplete(result,null)
        } catch(cancelled:CancellationException){throw cancelled}
        catch(error:Exception){onComplete(null,error.message?:"Não foi possível importar o CSV.")}
    }
    private fun csvImportKey(accountId:Long,movement:BankCsvMovement,merchantKey:String):String {
        val identity="$accountId|${movement.date}|$merchantKey|${movement.signedAmount}|${movement.balance ?: ""}|${movement.occurrence}"
        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return digest.joinToString(""){byte->"%02x".format(byte.toInt() and 0xff)}
    }
    fun addBudget(name:String, amount:Double) = viewModelScope.launch {
        if(name.isNotBlank() && amount.isFinite() && amount>=0.01) {
            val rounded=BigDecimal.valueOf(amount).setScale(2,RoundingMode.HALF_UP).toDouble()
            val id=dao.addBudget(Budget(name=name.trim(),limitAmount=rounded))
            dao.saveBudgetPeriod(BudgetPeriod(id,currentMonthStart(),rounded))
        }
    }
    fun updateBudgetName(value:Budget) = viewModelScope.launch {
        if(value.id>0 && value.name.isNotBlank() && dao.budgetById(value.id)!=null) {
            dao.updateBudget(value.copy(name=value.name.trim()))
        }
    }
    fun archiveBudget(budgetId:Long,fromMonth:Long=currentMonthStart()) = viewModelScope.launch {
        dao.budgetById(budgetId)?.let { budget ->
            if(budget.archivedFromMonth==null) dao.updateBudget(budget.copy(archivedFromMonth=currentMonthStart(fromMonth)))
        }
    }
    fun unarchiveBudget(budgetId:Long) = viewModelScope.launch {
        dao.budgetById(budgetId)?.let { budget ->
            if(budget.archivedFromMonth!=null) dao.updateBudget(budget.copy(archivedFromMonth=null))
        }
    }
    fun setBudgetClosed(budgetId:Long,monthStart:Long,closed:Boolean) = viewModelScope.launch {
        if(dao.budgetById(budgetId)==null) return@launch
        if(closed) dao.closeBudgetMonth(BudgetClosure(budgetId,monthStart)) else dao.reopenBudgetMonth(budgetId,monthStart)
    }
    private suspend fun budgetClosedInMonth(budgetId:Long,monthStart:Long)=dao.budgetClosureCount(budgetId,monthStart)>0
    fun setBudgetLimit(budgetId:Long,monthStart:Long,amount:Double) = viewModelScope.launch {
        if(dao.budgetById(budgetId)!=null && amount.isFinite() && amount>=0.01) {
            dao.saveBudgetPeriod(BudgetPeriod(budgetId,monthStart,BigDecimal.valueOf(amount).setScale(2,RoundingMode.HALF_UP).toDouble()))
        }
    }
    fun deleteTransaction(value:FinanceTransaction) = viewModelScope.launch { dao.deleteTransactionAndClearSettlement(value) }
    fun deleteTransactions(values:List<FinanceTransaction>) = viewModelScope.launch {
        if(values.isNotEmpty()) dao.deleteTransactionsAndClearSettlement(values)
    }
    fun assignBudget(value:FinanceTransaction,budgetId:Long,rememberForFuture:Boolean=false) = viewModelScope.launch {
        val budget=dao.budgetById(budgetId)
        if(value.kind=="Despesa"&&value.budgetId==null&&budget!=null&&budgetVisibleInMonth(budget,currentMonthStart(value.date))&&!budgetClosedInMonth(budget.id,currentMonthStart(value.date))) {
            val key=normalizeMovementKey(value.title)
            val rule=if(rememberForFuture&&key.isNotBlank())BudgetRule(key,value.title.trim(),budget.id) else null
            dao.saveTransactionAndRule(value.copy(budgetId=budget.id),rule)
        }
    }
    internal fun prepareClassificationImport(context:android.content.Context,rawResponse:String,onComplete:(ClassificationImportPreview?,String?)->Unit) = viewModelScope.launch {
        val result=try {
            val response=parseClassificationResponse(rawResponse)
            val requestFile=classificationRequestFile(context,response.requestId)
            if(!requestFile.isFile) error("Este pedido não está pendente nesta Carteira ou já foi importado. Pede um novo ficheiro de resposta.")
            val request=withContext(Dispatchers.IO){parseClassificationRequest(requestFile.readText(Charsets.UTF_8))}
            val validated=validateClassificationAssignments(request,response)
            validated.preview
        } catch(cancelled:CancellationException){throw cancelled}
        catch(error:Exception){onComplete(null,error.message?:"Não foi possível validar a resposta.");return@launch}
        onComplete(result,null)
    }
    internal fun importClassificationResponse(context:android.content.Context,preview:ClassificationImportPreview,onComplete:(Boolean,String?)->Unit) = viewModelScope.launch {
        try {
            val requestFile=classificationRequestFile(context,preview.requestId)
            if(!requestFile.isFile) error("O pedido original já não está disponível. Cria um pedido novo.")
            val request=withContext(Dispatchers.IO){parseClassificationRequest(requestFile.readText(Charsets.UTF_8))}
            val response=ClassificationResponse(
                preview.requestId,
                preview.assignments.map{ClassificationResponseAssignment(it.transactionId,it.budgetId)}+
                    preview.expenseSplits.mapNotNull{split->split.parts.firstNotNullOfOrNull{part->part.budgetId?.let{ClassificationResponseAssignment(split.transactionId,it)}}},
                preview.settlements.map{ClassificationResponseSettlement(it.incomeId,it.expenseIds,it.expensePartRefs)},
                preview.expenseSplits.map{split->ClassificationResponseExpenseSplit(split.transactionId,split.parts.map{ClassificationResponseExpensePart(it.title,it.amount,it.budgetId)})}
            )
            val validated=validateClassificationAssignments(request,response)
            if(validated.preview!=preview)
                error("A resposta mudou desde a pré-visualização. Importa-a novamente.")
            dao.applyClassificationChanges(validated.budgetUpdates,validated.incomeUpdates,validated.settlementExpenseUpdates,validated.expensePartUpdates)
            withContext(Dispatchers.IO){requestFile.delete()}
            onComplete(true,null)
        } catch(cancelled:CancellationException){throw cancelled}
        catch(error:Exception){onComplete(false,error.message?:"Não foi possível importar a classificação.")}
    }
    private suspend fun validateClassificationAssignments(
        request:ClassificationRequest,response:ClassificationResponse
    ):ValidatedClassificationChanges{
        if(response.requestId!=request.requestId) error("A resposta não corresponde ao pedido original.")
        if(System.currentTimeMillis()-request.createdAt>30L*24*60*60*1000) error("Este pedido expirou. Cria um pedido novo.")
        if(response.assignments.isEmpty()&&response.settlements.isEmpty()&&response.expenseSplits.isEmpty())error("A resposta não contém classificações, divisões nem liquidações.")
        val requestedExpenses=request.expenses.associateBy{it.transactionId}
        val requestedIncomes=request.incomes.associateBy{it.transactionId}
        if(response.assignments.any{it.transactionId !in requestedExpenses}||response.settlements.any{it.incomeId !in requestedIncomes}||response.expenseSplits.any{it.transactionId !in requestedExpenses})
            error("A resposta contém movimentos que não pertencem ao pedido.")
        if(response.settlements.any{settlement->settlement.expenseIds.any{it !in requestedExpenses}})
            error("A liquidação contém despesas que não pertencem ao pedido.")
        if(response.settlements.any{settlement->settlement.expensePartRefs.any{it.transactionId !in requestedExpenses}})
            error("A liquidação contém parcelas que não pertencem ao pedido.")
        val stored=dao.transactionsByIds(request.expenses.map{it.transactionId}+request.incomes.map{it.transactionId}).associateBy{it.id}
        val budgets=dao.allBudgets().associateBy{it.id}
        val closedMonths=dao.allBudgetClosures().map{it.budgetId to it.monthStart}.toSet()
        val splitByExpense=response.expenseSplits.associateBy{it.transactionId}
        val budgetUpdates=mutableListOf<FinanceTransaction>()
        val incomeUpdates=mutableListOf<FinanceTransaction>()
        val settlementExpenseUpdates=mutableListOf<FinanceTransaction>()
        val expensePartUpdates=mutableListOf<FinanceTransaction>()
        val assignmentPreview=mutableListOf<ClassificationImportAssignment>()
        val settlementPreview=mutableListOf<ClassificationImportSettlement>()
        val splitPreview=mutableListOf<ClassificationImportExpenseSplit>()
        response.expenseSplits.forEach{split->
            val item=requestedExpenses[split.transactionId]?:error("Falta uma despesa dividida no pedido.")
            val transaction=stored[item.transactionId]?:error("Uma despesa do pedido já não existe. Cria um pedido novo.")
            if(split.parts.size!=2)error("Uma despesa dividida tem de conter duas partes.")
            val originalAmount=BigDecimal.valueOf(kotlin.math.abs(transaction.amount)).setScale(2,RoundingMode.HALF_UP)
            val requestedAmount=BigDecimal.valueOf(item.amount).setScale(2,RoundingMode.HALF_UP)
            val unchanged=transaction.kind=="Despesa"&&transaction.budgetId==null&&transaction.active&&transaction.settlementRevenueId==null&&
                item.active&&item.settlementIncomeId==null&&transaction.title==item.title&&transaction.date==item.date&&originalAmount.compareTo(requestedAmount)==0
            if(!unchanged)error("A despesa ‘${item.displayTitle}’ foi alterada ou já não pode ser dividida. Cria um pedido novo.")
            var splitTotal=BigDecimal.ZERO
            val previewParts=split.parts.map{part->
                if(part.title.isBlank()||part.title.length>120||!part.amount.isFinite()||part.amount<0.01||kotlin.math.abs(part.amount*100-kotlin.math.round(part.amount*100))>0.000001)
                    error("A resposta contém uma parte de despesa inválida.")
                val partAmount=BigDecimal.valueOf(part.amount).setScale(2,RoundingMode.UNNECESSARY)
                splitTotal=splitTotal.add(partAmount)
                val budgetName=if(part.budgetId==null)null else {
                    if(item.budgets.none{it.id==part.budgetId})error("A resposta escolheu um orçamento que não estava disponível para esta despesa.")
                    val budget=budgets[part.budgetId]?:error("Um orçamento escolhido já não existe. Pede uma nova classificação.")
                    if(!budgetVisibleInMonth(budget,currentMonthStart(transaction.date)))error("O orçamento ‘${budget.name}’ já não está disponível para esta despesa.")
                    if((budget.id to currentMonthStart(transaction.date)) in closedMonths)error("O orçamento ‘${budget.name}’ está fechado neste mês. Reabre-o ou cria um pedido novo.")
                    budget.name
                }
                ClassificationImportExpensePart(part.title.trim(),partAmount.toDouble(),part.budgetId,budgetName)
            }
            if(splitTotal.compareTo(originalAmount)!=0||splitTotal.compareTo(requestedAmount)!=0)
                error("A soma das partes tem de igualar exatamente o valor original da despesa.")
            val firstBudget=split.parts.firstNotNullOfOrNull{it.budgetId}
            val assignment=response.assignments.firstOrNull{it.transactionId==split.transactionId}
            if(if(firstBudget==null)assignment!=null else assignment?.budgetId!=firstBudget)
                error("A classificação da despesa dividida é inconsistente.")
            if(firstBudget!=null&&response.settlements.any{split.transactionId in it.expenseIds})
                error("Uma despesa com orçamento atribuído não pode estar numa liquidação.")
            splitPreview+=ClassificationImportExpenseSplit(split.transactionId,item.displayTitle,previewParts)
        }
        response.assignments.forEach{assignment->
            val item=requestedExpenses[assignment.transactionId]?:error("Falta uma despesa no pedido.")
            if(item.budgets.none{it.id==assignment.budgetId}) error("A resposta escolheu um orçamento que não estava disponível para este movimento.")
            val transaction=stored[item.transactionId]?:error("Um movimento do pedido já não existe. Cria um pedido novo.")
            val amount=BigDecimal.valueOf(kotlin.math.abs(transaction.amount)).setScale(2,RoundingMode.HALF_UP)
            val requestedAmount=BigDecimal.valueOf(item.amount).setScale(2,RoundingMode.HALF_UP)
            val unchanged=transaction.kind=="Despesa"&&transaction.budgetId==null&&transaction.active&&transaction.settlementRevenueId==null&&
                transaction.title==item.title&&transaction.date==item.date&&amount.compareTo(requestedAmount)==0
            if(!unchanged) error("O movimento ‘${item.displayTitle}’ foi alterado ou já não está por classificar. Cria um pedido novo.")
            val budget=budgets[assignment.budgetId]?:error("Um orçamento escolhido já não existe. Pede uma nova classificação.")
            if(!budgetVisibleInMonth(budget,currentMonthStart(transaction.date))) error("O orçamento ‘${budget.name}’ já não está disponível para este movimento.")
            if((budget.id to currentMonthStart(transaction.date)) in closedMonths) error("O orçamento ‘${budget.name}’ está fechado neste mês. Reabre-o ou cria um pedido novo.")
            if(splitByExpense.containsKey(transaction.id)) {
                val expected=splitByExpense.getValue(transaction.id).parts.firstNotNullOfOrNull{it.budgetId}
                if(expected!=budget.id)error("A classificação da despesa dividida é inconsistente.")
            } else {
                budgetUpdates+=transaction.copy(budgetId=budget.id)
                assignmentPreview+=ClassificationImportAssignment(transaction.id,budget.id,item.displayTitle,budget.name)
            }
        }
        val splitExpensesProcessed=mutableSetOf<Long>()
        val partSettlementIncomeIds=response.settlements.flatMap{settlement->
            settlement.expensePartRefs.map{ref->(ref.transactionId to ref.partIndex) to settlement.incomeId}
        }.toMap()
        fun splitTransactions(source:FinanceTransaction,split:ClassificationResponseExpenseSplit,wholeExpenseSettlementIncomeId:Long?):List<FinanceTransaction>{
            val sign=if(source.amount<0)-1 else 1
            return split.parts.mapIndexed{index,part->
                val settlementIncomeId=partSettlementIncomeIds[source.id to index]?:wholeExpenseSettlementIncomeId
                source.copy(
                    id=if(index==0)source.id else 0,
                    title=part.title.trim(),
                    amount=sign*part.amount,
                    budgetId=part.budgetId,
                    importKey=if(index==0)source.importKey else null,
                    active=settlementIncomeId==null,
                    settlementRevenueId=settlementIncomeId
                )
            }
        }
        response.settlements.forEach{settlement->
            val incomeItem=requestedIncomes[settlement.incomeId]?:error("A receita da liquidação não pertence ao pedido.")
            val income=stored[incomeItem.transactionId]?:error("Uma receita do pedido já não existe. Cria um pedido novo.")
            if(!income.amount.isFinite())error("O valor da receita não é válido para liquidar.")
            val previousExpenses=dao.settlementExpenses(income.id)
            if(previousExpenses.map{it.id}.toSet()!=incomeItem.previousExpenseIds.toSet())
                error("A liquidação da receita mudou desde o envio. Cria um pedido novo.")
            val incomeAmount=BigDecimal.valueOf(kotlin.math.abs(income.amount)).setScale(2,RoundingMode.HALF_UP)
            val requestedIncomeAmount=BigDecimal.valueOf(incomeItem.amount).setScale(2,RoundingMode.HALF_UP)
            val expectedIncomeActive=previousExpenses.isEmpty()
            val validIncome=income.kind=="Receita"&&income.active==expectedIncomeActive&&income.active==incomeItem.active&&income.budgetId==null&&
                income.title==incomeItem.title&&income.date==incomeItem.date&&incomeAmount.compareTo(requestedIncomeAmount)==0
            if(!validIncome)
                error("A receita ‘${incomeItem.displayTitle}’ foi alterada ou já não está disponível para liquidar.")
            val expenseItems=settlement.expenseIds.map{id->requestedExpenses[id]?:error("Uma despesa da liquidação não pertence ao pedido.")}
            val expenses=settlement.expenseIds.map{id->stored[id]?:error("Uma despesa da liquidação já não existe. Cria um pedido novo.")}
            val selectedSplitParts=settlement.expensePartRefs.map{ref->
                val item=requestedExpenses[ref.transactionId]?:error("Uma parcela da liquidação não pertence ao pedido.")
                val source=stored[ref.transactionId]?:error("A despesa de uma parcela já não existe. Cria um pedido novo.")
                val split=splitByExpense[ref.transactionId]?:error("Falta uma divisão de despesa referida na liquidação.")
                val part=split.parts.getOrNull(ref.partIndex)?:error("A liquidação refere uma parcela inválida.")
                if(part.budgetId!=null||source.kind!="Despesa"||!source.active||source.settlementRevenueId!=null||!item.active||item.settlementIncomeId!=null)
                    error("Só é possível liquidar parcelas ativas e sem orçamento.")
                Triple(ref,item,part)
            }
            val validExpenses=expenses.zip(expenseItems).all{(expense,item)->
                if(!expense.amount.isFinite())return@all false
                val amount=BigDecimal.valueOf(kotlin.math.abs(expense.amount)).setScale(2,RoundingMode.HALF_UP)
                val requestedAmount=BigDecimal.valueOf(item.amount).setScale(2,RoundingMode.HALF_UP)
                val stateMatches=if(item.settlementIncomeId==null)expense.active&&expense.settlementRevenueId==null
                    else item.settlementIncomeId==income.id&&!expense.active&&expense.settlementRevenueId==income.id
                expense.kind=="Despesa"&&stateMatches&&expense.active==item.active&&expense.budgetId==null&&
                    expense.title==item.title&&expense.date==item.date&&amount.compareTo(requestedAmount)==0
            }
            if(!validExpenses)error("Uma despesa escolhida para liquidar foi alterada, atribuída ou já não está disponível.")
            val regularTotal=expenses.fold(BigDecimal.ZERO){sum,expense->sum.add(BigDecimal.valueOf(kotlin.math.abs(expense.amount)).setScale(2,RoundingMode.HALF_UP))}
            val splitPartTotal=selectedSplitParts.fold(BigDecimal.ZERO){sum,(_,_,part)->sum.add(BigDecimal.valueOf(part.amount).setScale(2,RoundingMode.UNNECESSARY))}
            val total=regularTotal.add(splitPartTotal).setScale(2,RoundingMode.HALF_UP)
            if(total.compareTo(incomeAmount)!=0)error("As despesas escolhidas para ‘${incomeItem.displayTitle}’ não somam exatamente o valor da receita.")
            incomeUpdates+=income.copy(active=false)
            val selectedIds=settlement.expenseIds.toSet()
            settlementExpenseUpdates+=previousExpenses.filter{it.id !in selectedIds}.map{it.copy(active=true,settlementRevenueId=null)}
            expenses.forEach{expense->
                val split=splitByExpense[expense.id]
                if(split==null)settlementExpenseUpdates+=expense.copy(active=false,settlementRevenueId=income.id)
                else{
                    if(split.parts.any{it.budgetId!=null})error("Uma despesa com orçamento atribuído não pode estar numa liquidação.")
                    expensePartUpdates+=splitTransactions(expense,split,income.id)
                    splitExpensesProcessed+=expense.id
                }
            }
            val expenseTitles=expenseItems.mapIndexed{index,item->
                splitByExpense[item.transactionId]?.parts?.map{it.title.trim()}?:listOf(item.displayTitle)
            }.flatten()+selectedSplitParts.map{(_,_,part)->part.title.trim()}
            settlementPreview+=ClassificationImportSettlement(
                incomeId=income.id,
                incomeTitle=incomeItem.displayTitle,
                incomeAmount=incomeAmount.toDouble(),
                expenseIds=expenses.map{it.id},
                expenseTitles=expenseTitles,
                expensePartRefs=settlement.expensePartRefs
            )
        }
        response.expenseSplits.forEach{split->
            if(split.transactionId !in splitExpensesProcessed){
                val transaction=stored[split.transactionId]?:error("Uma despesa do pedido já não existe. Cria um pedido novo.")
                expensePartUpdates+=splitTransactions(transaction,split,null)
            }
        }
        return ValidatedClassificationChanges(
            budgetUpdates,incomeUpdates,settlementExpenseUpdates,expensePartUpdates,
            ClassificationImportPreview(request.requestId,assignmentPreview,settlementPreview,splitPreview)
        )
    }
    fun settleIncome(incomeId:Long,expenseIds:Set<Long>,onComplete:(Boolean)->Unit) = viewModelScope.launch {
        val settled=try {
            val income=dao.transactionById(incomeId)
            val ids=expenseIds.toList()
            val previousExpenses=dao.settlementExpenses(incomeId)
            val expenses=if(ids.isEmpty())emptyList() else dao.transactionsByIds(ids)
            val validIncome=income!=null&&income.kind=="Receita"&&income.amount.isFinite()&&
                (if(previousExpenses.isEmpty()) income.active else !income.active)
            val validExpenses=expenses.size==ids.size&&expenses.isNotEmpty()&&expenses.all{expense->
                expense.kind=="Despesa"&&expense.budgetId==null&&when(expense.settlementRevenueId){
                    incomeId->!expense.active
                    null->expense.active
                    else->false
                }
            }
            val selectedTotal=expenses.fold(BigDecimal.ZERO){total,expense->
                total.add(BigDecimal.valueOf(kotlin.math.abs(expense.amount)).setScale(2,RoundingMode.HALF_UP))
            }.setScale(2,RoundingMode.HALF_UP)
            val target=income?.let{BigDecimal.valueOf(kotlin.math.abs(it.amount)).setScale(2,RoundingMode.HALF_UP)}
            if(validIncome&&validExpenses&&target!=null&&selectedTotal.compareTo(target)==0){
                dao.updateIncomeSettlement(income!!,previousExpenses,expenses);true
            }else false
        } catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){false}
        onComplete(settled)
    }
    fun undoIncomeSettlement(incomeId:Long,onComplete:(Boolean)->Unit) = viewModelScope.launch {
        val undone=try {
            val income=dao.transactionById(incomeId)
            val expenses=dao.settlementExpenses(incomeId)
            val valid=income!=null&&income.kind=="Receita"&&!income.active&&expenses.isNotEmpty()&&expenses.all{
                it.kind=="Despesa"&&!it.active&&it.settlementRevenueId==incomeId
            }
            if(valid){dao.undoIncomeSettlement(income!!,expenses);true}else false
        } catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){false}
        onComplete(undone)
    }
    fun splitTransaction(
        original:FinanceTransaction,firstAmount:Double,secondAmount:Double,firstTitle:String,secondTitle:String,
        firstBudgetId:Long?,secondBudgetId:Long?,onComplete:(Boolean)->Unit
    ) = viewModelScope.launch {
        val split = try {
            val stored=dao.transactionById(original.id)
            val settlementLocked=stored?.settlementRevenueId!=null||stored?.let{it.kind=="Receita"&&dao.settlementExpenses(it.id).isNotEmpty()}==true
            fun rounded(value:Double)=BigDecimal.valueOf(value).setScale(2,RoundingMode.HALF_UP).toDouble()
            val first=rounded(firstAmount);val second=rounded(secondAmount);val total=rounded(kotlin.math.abs(original.amount))
            suspend fun validBudget(id:Long?):Boolean {
                if(id==null)return true
                val budget=dao.budgetById(id)?:return false
                return budget.id==original.budgetId||(budgetVisibleInMonth(budget,currentMonthStart(original.date))&&!budgetClosedInMonth(budget.id,currentMonthStart(original.date)))
            }
            val validBudgets=if(original.kind=="Despesa") validBudget(firstBudgetId)&&validBudget(secondBudgetId)
                else firstBudgetId==null&&secondBudgetId==null
            val sumMatches=BigDecimal.valueOf(first).add(BigDecimal.valueOf(second)).compareTo(BigDecimal.valueOf(total))==0
            if(settlementLocked||original.id<=0||!first.isFinite()||!second.isFinite()||first<0.01||second<0.01||!sumMatches||!validBudgets) false
            else {
                val sign=if(original.amount<0)-1 else 1
                val firstMovement=original.copy(
                    title=firstTitle.trim(),amount=if(sign<0)-first else first,
                    budgetId=if(original.kind=="Despesa")firstBudgetId else null
                )
                val secondMovement=original.copy(
                    id=0,title=secondTitle.trim(),amount=if(sign<0)-second else second,
                    budgetId=if(original.kind=="Despesa")secondBudgetId else null,
                    importKey=null
                )
                // Keep the source import key only on the first part; re-imports are then ignored by the unique key.
                dao.splitTransaction(firstMovement,secondMovement)
                true
            }
        } catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){false}
        onComplete(split)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState)
        val dao = FinanceDb.get(this).dao()
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary=Green, background=Paper, surface=Color.White)) {
            val model: FinanceModel = viewModel(factory=object: ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(c:Class<T>):T = FinanceModel(dao) as T
            })
            CarteiraApp(model)
        } }
    }
}

@Composable private fun CarteiraApp(model: FinanceModel) {
    val accounts by model.accounts.collectAsState(); val transactions by model.transactions.collectAsState(); val budgets by model.budgets.collectAsState(); val budgetPeriods by model.budgetPeriods.collectAsState()
    val budgetRules by model.budgetRules.collectAsState()
    val budgetClosures by model.budgetClosures.collectAsState()
    val closedKeys=remember(budgetClosures){budgetClosures.map{it.budgetId to it.monthStart}.toSet()}
    val context=LocalContext.current
    val coroutineScope=rememberCoroutineScope()
    LaunchedEffect(Unit){withContext(Dispatchers.IO){cleanupExpiredClassificationRequests(context)}}
    var section by remember { mutableStateOf("Início") }; var dialog by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<FinanceTransaction?>(null) }
    var pendingBulkDelete by remember { mutableStateOf<List<FinanceTransaction>?>(null) }
    var pendingBudgetAssignment by remember { mutableStateOf<FinanceTransaction?>(null) }
    var pendingSplit by remember { mutableStateOf<FinanceTransaction?>(null) }
    var pendingSettlementIncome by remember { mutableStateOf<FinanceTransaction?>(null) }
    var pendingSettlementDetails by remember { mutableStateOf<FinanceTransaction?>(null) }
    var pendingUndoSettlement by remember { mutableStateOf<FinanceTransaction?>(null) }
    var selectedMovementIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var pendingEdit by remember { mutableStateOf<FinanceTransaction?>(null) }
    var pendingAccountEdit by remember { mutableStateOf<Account?>(null) }
    var adjustmentAccount by remember { mutableStateOf<Account?>(null) }
    var pendingBudgetLimit by remember { mutableStateOf<BudgetLimitTarget?>(null) }
    var pendingBudgetEdit by remember { mutableStateOf<Budget?>(null) }
    var pendingBudgetArchive by remember { mutableStateOf<Budget?>(null) }
    var archiveFromMonth by remember { mutableLongStateOf(currentMonthStart()) }
    var activeBudgetId by remember { mutableStateOf<Long?>(null) }
    var movementBudgetId by remember { mutableStateOf<Long?>(null) }
    var selectedBudgetMonth by remember { mutableLongStateOf(currentMonthStart()) }
    var budgetSort by remember { mutableStateOf("Maior gasto") }
    var showBudgetRemaining by remember { mutableStateOf(false) }
    var pendingCsvImport by remember { mutableStateOf<ParsedBankCsv?>(null) }
    var csvImportAccountId by remember { mutableLongStateOf(0L) }
    var csvImportError by remember { mutableStateOf<String?>(null) }
    var csvImportResult by remember { mutableStateOf<CsvImportResult?>(null) }
    var csvImporting by remember { mutableStateOf(false) }
    var classificationNotice by remember { mutableStateOf<String?>(null) }
    var pendingClassificationRequest by remember { mutableStateOf<ClassificationRequest?>(null) }
    var pendingClassificationImport by remember { mutableStateOf<ClassificationImportPreview?>(null) }
    var classificationImporting by remember { mutableStateOf(false) }
    var classificationImportedMessage by remember { mutableStateOf<String?>(null) }
    val csvPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null) coroutineScope.launch {
            try {
                val parsed=withContext(Dispatchers.IO){
                    val bytes=context.contentResolver.openInputStream(uri)?.use{it.readBytes()}?:error("Não foi possível abrir o ficheiro selecionado.")
                    if(bytes.size>15*1024*1024) error("O CSV é demasiado grande para importar (limite: 15 MB).")
                    parseMillenniumCsv(bytes)
                }
                pendingCsvImport=parsed
                csvImportAccountId=accounts.firstOrNull()?.id?:0L
                csvImportError=null
            } catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){csvImportError=error.message?:"Não foi possível ler o CSV."}
        }
    }
    fun openCsvPicker(){csvImportError=null;csvPicker.launch(arrayOf("text/csv","text/comma-separated-values","application/vnd.ms-excel","text/plain","*/*"))}
    val classificationPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null) coroutineScope.launch {
            try {
                val raw=withContext(Dispatchers.IO){
                    val bytes=context.contentResolver.openInputStream(uri)?.use{it.readBytes()}?:error("Não foi possível abrir o ficheiro selecionado.")
                    if(bytes.size>2*1024*1024) error("A resposta é demasiado grande para importar (limite: 2 MB).")
                    bytes.toString(Charsets.UTF_8)
                }
                model.prepareClassificationImport(context,raw){preview,error->
                    if(error!=null) classificationNotice=error else pendingClassificationImport=preview
                }
            } catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){classificationNotice=error.message?:"Não foi possível ler a resposta de classificação."}
        }
    }
    fun openClassificationPicker(){classificationNotice=null;classificationPicker.launch(arrayOf("application/json","text/json","*/*"))}
    val classificationRequestExporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
        val request=pendingClassificationRequest
        pendingClassificationRequest=null
        if(uri==null) classificationNotice="A exportação do pedido foi cancelada."
        else if(request==null) classificationNotice="Não foi possível recuperar o pedido para exportar."
        else coroutineScope.launch {
            try {
                withContext(Dispatchers.IO){
                    saveClassificationRequest(context,request)
                    val bytes=request.toJson().toString().toByteArray(Charsets.UTF_8)
                    context.contentResolver.openOutputStream(uri,"wt")?.use{it.write(bytes)}
                        ?:error("Não foi possível guardar o pedido no local escolhido.")
                }
                classificationNotice="Pedido guardado. Carrega este ficheiro na página Classificar e liquidar para criar a ligação a enviar."
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://jokasman.github.io/carteira/classificacao/")))
                }.onFailure {
                    classificationNotice="Pedido guardado. Abre https://jokasman.github.io/carteira/classificacao/ e carrega o ficheiro para criar a ligação."
                }
            } catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){classificationNotice=error.message?:"Não foi possível exportar o pedido de classificação."}
        }
    }
    fun requestMovementClassification(selected:List<FinanceTransaction>){
        val selectedIds=selected.map{it.id}.toSet()
        val selectedIncomes=selected.filter{income->
            income.kind=="Receita"&&settlementIncomeCanBeRequested(income,transactions)&&
                transactions.filter{it.settlementRevenueId==income.id}.all{it.id in selectedIds}
        }
        val selectedExpenses=selected.filter{it.kind=="Despesa"&&it.budgetId==null&&it.active&&it.settlementRevenueId==null}
        val incomeRequests=selectedIncomes.filter{income->
            transactions.any{it.settlementRevenueId==income.id}||selectedExpenses.isNotEmpty()
        }.map{income->
            val previous=selected.filter{it.settlementRevenueId==income.id}
            ClassificationRequestIncome(income.id,income.date,income.title,income.title.ifBlank{"Receita"},
                BigDecimal.valueOf(kotlin.math.abs(income.amount)).setScale(2,RoundingMode.HALF_UP).toDouble(),income.active,previous.map{it.id})
        }
        val incomeIds=incomeRequests.map{it.transactionId}.toSet()
        val candidateExpenses=(selectedExpenses+selected.filter{it.settlementRevenueId?.let(incomeIds::contains)==true}).distinctBy{it.id}
        val expenseRequests=candidateExpenses.mapNotNull{transaction->
            val linkedIncomeId=transaction.settlementRevenueId?.takeIf{it in incomeIds}
            val available=if(linkedIncomeId==null)budgets.filter{budgetVisibleInMonth(it,currentMonthStart(transaction.date))&&(it.id to currentMonthStart(transaction.date)) !in closedKeys}
                .map{ClassificationBudgetOption(it.id,it.name)} else emptyList()
            if(available.isEmpty()&&incomeRequests.isEmpty())null else ClassificationRequestItem(
                transactionId=transaction.id,
                date=transaction.date,
                title=transaction.title,
                displayTitle=transaction.title.ifBlank{"Despesa"},
                amount=BigDecimal.valueOf(kotlin.math.abs(transaction.amount)).setScale(2,RoundingMode.HALF_UP).toDouble(),
                budgets=available,
                active=transaction.active,
                settlementIncomeId=linkedIncomeId
            )
        }
        if(expenseRequests.isEmpty()){
            classificationNotice="Seleciona despesas sem orçamento para classificar ou uma receita e despesas elegíveis para liquidar."
            return
        }
        val request=ClassificationRequest(UUID.randomUUID().toString(),System.currentTimeMillis(),expenseRequests,incomeRequests)
        pendingClassificationRequest=request
        classificationRequestExporter.launch("carteira-pedido-${request.requestId.take(8)}.json")
    }
    fun addMovement(budgetId: Long? = null) { pendingEdit = null; movementBudgetId = budgetId; dialog = "movimento" }
    fun editMovement(value: FinanceTransaction) { pendingEdit = value; movementBudgetId = null; dialog = "movimento" }
    val activeBudget = budgets.firstOrNull { it.id == activeBudgetId }
    val canAddBudgetMovement = activeBudget != null && selectedBudgetMonth == currentMonthStart() && budgetVisibleInMonth(activeBudget, selectedBudgetMonth) && (activeBudget.id to selectedBudgetMonth) !in closedKeys
    val pageTitle = if (section == "DetalheOrçamento") activeBudget?.name ?: "Orçamento" else section
    val total = accounts.sumOf { accountBalance(it, transactions) }
    Scaffold(containerColor=Paper, topBar={
        Row(Modifier.fillMaxWidth().background(Paper).statusBarsPadding().padding(horizontal=22.dp, vertical=18.dp), verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("CARTEIRA", style=MaterialTheme.typography.labelMedium, color=Green, fontWeight=FontWeight.Bold); Text(pageTitle, style=MaterialTheme.typography.headlineSmall, color=Ink, fontWeight=FontWeight.Bold) }
            if(section!="DetalheOrçamento"||canAddBudgetMovement) IconButton(onClick={addMovement(if(section=="DetalheOrçamento")activeBudgetId else null)}) { Icon(Icons.Default.Add, "Novo movimento", tint=Green) }
        }
    }, bottomBar={ NavigationBar(containerColor=Color.White) {
        listOf("Início" to Icons.Default.Home, "Movimentos" to Icons.Default.ReceiptLong, "Orçamentos" to Icons.Default.PieChart, "Contas" to Icons.Default.AccountBalanceWallet, "Relatórios" to Icons.Default.BarChart).forEach { (label, icon) ->
            NavigationBarItem(selected=section==label || (section=="DetalheOrçamento" && label=="Orçamentos"), onClick={section=label;selectedMovementIds=emptySet()}, icon={Icon(icon,label)}, label={Text(label, style=MaterialTheme.typography.labelSmall.copy(fontSize=9.sp,lineHeight=10.sp),maxLines=1,softWrap=false,textAlign=TextAlign.Center)}, colors=NavigationBarItemDefaults.colors(selectedIconColor=Green,selectedTextColor=Green,indicatorColor=Mint))
        }
    } }, floatingActionButton={ if(section in listOf("Movimentos","Orçamentos","Contas")||(section=="DetalheOrçamento"&&canAddBudgetMovement)) FloatingActionButton(onClick={when(section){"Orçamentos"->{pendingEdit=null;dialog="orçamento"};"Contas"->dialog="conta";"DetalheOrçamento"->addMovement(activeBudgetId);else->addMovement()}},containerColor=Green,contentColor=Color.White){Icon(Icons.Default.Add,"Adicionar")} }) { pad ->
        when(section) {
            "Início" -> HomePage(Modifier.padding(pad), total, transactions, accounts, budgets, budgetPeriods, {section=it}, {pendingDelete=it}, {editMovement(it)}, {addMovement()}, {budget->activeBudgetId=budget.id;selectedBudgetMonth=currentMonthStart();section="DetalheOrçamento"})
            "Movimentos" -> TransactionsPage(Modifier.padding(pad), transactions, accounts, budgets, closedKeys, selectedMovementIds, {selectedMovementIds=it}, {pendingBulkDelete=it}, {pendingBudgetAssignment=it;dialog="atribuir-orçamento"}, {editMovement(it)}, {addMovement()}, {openCsvPicker()}, {openClassificationPicker()}, {requestMovementClassification(it)}, {pendingSettlementIncome=it;dialog="liquidar-receita"}, {pendingSettlementDetails=it;dialog="ver-liquidacao"})
            "Contas" -> AccountsPage(Modifier.padding(pad), accounts, transactions, {pendingAccountEdit=null;dialog="conta"}, {pendingAccountEdit=it;dialog="editar-conta"})
            "Orçamentos" -> BudgetsPage(Modifier.padding(pad), budgets, closedKeys, transactions, budgetPeriods, selectedBudgetMonth, {selectedBudgetMonth=it}, budgetSort, {budgetSort=it}, showBudgetRemaining, {showBudgetRemaining=it}, {pendingBudgetLimit=null;pendingBudgetEdit=null;dialog="orçamento"}, {budget->addMovement(budget.id)}, {budget->activeBudgetId=budget.id;section="DetalheOrçamento"}, {budget,closed->model.setBudgetClosed(budget.id,selectedBudgetMonth,closed)})
            "DetalheOrçamento" -> BudgetDetailPage(Modifier.padding(pad), activeBudget, activeBudget?.let{(it.id to selectedBudgetMonth) in closedKeys}?:false, {budget,closed->model.setBudgetClosed(budget.id,selectedBudgetMonth,closed)}, transactions, accounts, budgetPeriods, selectedBudgetMonth, {selectedBudgetMonth=it}, {addMovement(activeBudget?.id)}, {pendingDelete=it}, {editMovement(it)}, {budget,monthStart,amount->pendingBudgetLimit=BudgetLimitTarget(budget.id,monthStart,amount);dialog="budget-limit"}, {budget->pendingBudgetEdit=budget;dialog="editar-orçamento"}, {budget->archiveFromMonth=selectedBudgetMonth;pendingBudgetArchive=budget}, {budget->model.unarchiveBudget(budget.id)})
            else -> ReportsPage(Modifier.padding(pad), transactions)
        }
    }
    if(dialog.isNotEmpty()) when(dialog) {
        "movimento" -> TransactionDialog(accounts,budgets,closedKeys,budgetRules,budgets.firstOrNull{it.id==movementBudgetId},pendingEdit,{value,rememberRule,done->model.saveTransaction(value,rememberRule,done)},{pendingEdit=null;dialog="";movementBudgetId=null},{pendingEdit=null;movementBudgetId=null;dialog="orçamento"},{transaction->pendingSplit=transaction;dialog="dividir-movimento"})
        "dividir-movimento" -> pendingSplit?.let{transaction->SplitTransactionDialog(transaction,budgets,closedKeys,{firstAmount,secondAmount,firstTitle,secondTitle,firstBudgetId,secondBudgetId,done->model.splitTransaction(transaction,firstAmount,secondAmount,firstTitle,secondTitle,firstBudgetId,secondBudgetId){success->done(success);if(success){pendingSplit=null;pendingEdit=null;movementBudgetId=null;dialog=""}}},{pendingSplit=null;dialog="movimento"})}
        "liquidar-receita" -> pendingSettlementIncome?.let{income->
            val linked=transactions.filter{it.settlementRevenueId==income.id}
            val candidates=transactions.filter{(it.kind=="Despesa"&&it.active&&it.budgetId==null&&it.settlementRevenueId==null)||it.settlementRevenueId==income.id}
            SettleIncomeDialog(income,candidates,linked.map{it.id}.toSet(),{selectedIds,done->model.settleIncome(income.id,selectedIds,done)},{pendingSettlementIncome=null;dialog=""})
        }
        "ver-liquidacao" -> pendingSettlementDetails?.let{income->SettledExpensesDialog(income,transactions.filter{it.settlementRevenueId==income.id},{pendingSettlementDetails=null;pendingSettlementIncome=income;dialog="liquidar-receita"},{pendingSettlementDetails=null;pendingUndoSettlement=income;dialog=""},{pendingSettlementDetails=null;dialog=""})}
        "atribuir-orçamento" -> pendingBudgetAssignment?.let{transaction->BudgetAssignmentDialog(transaction,budgets,closedKeys,{value,budgetId,remember->model.assignBudget(value,budgetId,remember);pendingBudgetAssignment=null;dialog=""},{pendingBudgetAssignment=null;dialog=""})}
        "conta" -> AccountDialog(null,0.0, {value->model.saveAccount(value);dialog=""}, {dialog=""}, {})
        "editar-conta" -> AccountDialog(pendingAccountEdit, pendingAccountEdit?.let{accountBalance(it,transactions)}?:0.0, {value->model.saveAccount(value);pendingAccountEdit=null;dialog=""}, {pendingAccountEdit=null;dialog=""}, {account->adjustmentAccount=account;pendingAccountEdit=null;dialog="acerto-conta"})
        "orçamento" -> BudgetDialog(null,500.0,{name,limit->model.addBudget(name,limit);dialog=""},{dialog=""})
        "editar-orçamento" -> pendingBudgetEdit?.let{budget->BudgetDialog(budget,budgetLimitFor(budget,budgetPeriods,selectedBudgetMonth),{name,_->model.updateBudgetName(budget.copy(name=name));pendingBudgetEdit=null;dialog=""},{pendingBudgetEdit=null;dialog=""})}
        "acerto-conta" -> adjustmentAccount?.let{account->AdjustmentDialog(account,accountBalance(account,transactions),{amount,decrease,note,done->model.saveTransaction(FinanceTransaction(title="Acerto de saldo",amount=if(decrease)-amount else amount,kind="Ajuste",category="Acerto de saldo",accountId=account.id,note=note),false,done)},{adjustmentAccount=null;dialog=""})}
        "budget-limit" -> pendingBudgetLimit?.let{target->BudgetLimitDialog(target,{amount->model.setBudgetLimit(target.budgetId,target.monthStart,amount);pendingBudgetLimit=null;dialog=""},{pendingBudgetLimit=null;dialog=""})}
    }
    pendingDelete?.let { transaction ->
        AlertDialog(
            onDismissRequest={pendingDelete=null},
            title={Text("Eliminar movimento?",color=Ink,fontWeight=FontWeight.Bold)},
            text={Text("O movimento ‘${transaction.title.ifBlank{transaction.kind}}’ será removido da carteira.",color=Muted)},
            confirmButton={TextButton(onClick={model.deleteTransaction(transaction);pendingDelete=null}){Text("Eliminar",color=Coral)}},
            dismissButton={TextButton(onClick={pendingDelete=null}){Text("Cancelar",color=Green)}}
        )
    }
    pendingBulkDelete?.let { selected ->
        AlertDialog(
            onDismissRequest={pendingBulkDelete=null},
            title={Text(if(selected.size==1)"Eliminar movimento?" else "Eliminar ${selected.size} movimentos?",color=Ink,fontWeight=FontWeight.Bold)},
            text={Text(if(selected.size==1)"O movimento selecionado será removido da carteira." else "Os ${selected.size} movimentos selecionados serão removidos da carteira.",color=Muted)},
            confirmButton={TextButton(onClick={model.deleteTransactions(selected);selectedMovementIds=emptySet();pendingBulkDelete=null}){Text("Eliminar",color=Coral)}},
            dismissButton={TextButton(onClick={pendingBulkDelete=null}){Text("Cancelar",color=Green)}}
        )
    }
    pendingUndoSettlement?.let { income ->
        var undoing by remember(income.id){mutableStateOf(false)}
        var error by remember(income.id){mutableStateOf("")}
        AlertDialog(
            onDismissRequest={if(!undoing){pendingUndoSettlement=null;dialog=""}},
            title={Text("Desfazer liquidação?",color=Ink,fontWeight=FontWeight.Bold)},
            text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
                Text("A receita e as despesas associadas voltarão a ficar ativas e a ligação entre elas será removida.",color=Muted)
                if(error.isNotBlank())Text(error,color=Coral,style=MaterialTheme.typography.bodySmall)
            }},
            confirmButton={TextButton(enabled=!undoing,onClick={
                undoing=true
                model.undoIncomeSettlement(income.id){success->
                    undoing=false
                    if(success){pendingUndoSettlement=null;dialog=""}else error="Não foi possível desfazer esta liquidação."
                }
            }){Text(if(undoing)"A desfazer…" else "Desfazer liquidação",color=Coral,fontWeight=FontWeight.Bold)}},
            dismissButton={TextButton(enabled=!undoing,onClick={pendingUndoSettlement=null;dialog=""}){Text("Cancelar",color=Muted)}}
        )
    }
    pendingBudgetArchive?.let { budget ->
        val affected=transactions.count{it.kind=="Despesa"&&it.budgetId==budget.id&&currentMonthStart(it.date)>=archiveFromMonth}
        AlertDialog(
            onDismissRequest={pendingBudgetArchive=null},
            title={Text("Arquivar orçamento?",color=Ink,fontWeight=FontWeight.Bold)},
            text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text("Escolhe o mês a partir do qual ${budget.name} fica arquivado. Pode ser um mês passado ou futuro.",color=Muted)
                MonthNavigator(archiveFromMonth){archiveFromMonth=it}
                Text("Deixa de aparecer a partir de ${monthLabel(archiveFromMonth)} e continua disponível nos meses anteriores.",color=Muted,style=MaterialTheme.typography.bodySmall)
                if(affected>0) Text("Há $affected despesa(s) associada(s) a este orçamento a partir desse mês. Mantêm a associação, mas o orçamento fica oculto nesses meses.",color=Coral,style=MaterialTheme.typography.bodySmall)
            }},
            confirmButton={TextButton(onClick={model.archiveBudget(budget.id,archiveFromMonth);pendingBudgetArchive=null}){Text("Arquivar",color=Coral)}},
            dismissButton={TextButton(onClick={pendingBudgetArchive=null}){Text("Cancelar",color=Green)}}
        )
    }
    pendingCsvImport?.let{parsed->
        CsvImportDialog(parsed,accounts,csvImportAccountId,csvImporting,{csvImportAccountId=it},{
            csvImporting=true
            model.importCsv(csvImportAccountId,parsed){result,error->
                csvImporting=false
                if(error!=null) csvImportError=error else {pendingCsvImport=null;csvImportResult=result}
            }
        },{if(!csvImporting)pendingCsvImport=null})
    }
    csvImportError?.let{message->
        AlertDialog(onDismissRequest={csvImportError=null},title={Text("Importar CSV",color=Ink,fontWeight=FontWeight.Bold)},text={Text(message,color=Muted)},confirmButton={TextButton(onClick={csvImportError=null}){Text("OK",color=Green)}})
    }
    csvImportResult?.let{result->
        AlertDialog(
            onDismissRequest={csvImportResult=null},
            title={Text("Importação concluída",color=Ink,fontWeight=FontWeight.Bold)},
            text={Text("${result.imported} movimentos importados. ${result.duplicates} duplicados ignorados. ${result.unassignedExpenses} despesas ficaram sem orçamento para poderes revê-las no filtro correspondente.${if(result.skippedRows>0)" ${result.skippedRows} linhas inválidas foram ignoradas." else ""}",color=Muted)},
            confirmButton={TextButton(onClick={csvImportResult=null;section="Movimentos"}){Text("Ver movimentos",color=Green)}}
        )
    }
    classificationNotice?.let{message->
        AlertDialog(
            onDismissRequest={classificationNotice=null},
            title={Text("Classificação de movimentos",color=Ink,fontWeight=FontWeight.Bold)},
            text={Text(message,color=Muted)},
            confirmButton={TextButton(onClick={classificationNotice=null}){Text("OK",color=Green)}}
        )
    }
    pendingClassificationImport?.let{preview->
        AlertDialog(
            onDismissRequest={if(!classificationImporting)pendingClassificationImport=null},
            title={Text("Importar classificação?",color=Ink,fontWeight=FontWeight.Bold)},
            text={
                Column(Modifier.heightIn(max=380.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text("Confirma as decisões. Cada despesa dividida conserva o valor original; orçamentos e liquidações aplicam-se às partes apresentadas.",color=Muted)
                    preview.assignments.forEach{assignment->
                        Column(Modifier.fillMaxWidth().background(Paper,RoundedCornerShape(12.dp)).padding(10.dp)){
                            Text("Classificar: ${assignment.movementTitle}",color=Ink,fontWeight=FontWeight.SemiBold)
                            Text("→ ${assignment.budgetName}",color=Green,style=MaterialTheme.typography.bodySmall)
                        }
                    }
                    preview.expenseSplits.forEach{split->
                        Column(Modifier.fillMaxWidth().background(Paper,RoundedCornerShape(12.dp)).padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                            Text("Dividir despesa ${split.sourceTitle}",color=Ink,fontWeight=FontWeight.SemiBold)
                            split.parts.forEachIndexed{index,part->
                                Text("Parte ${index+1}: ${part.title} · ${money(part.amount)}",color=Green,style=MaterialTheme.typography.bodySmall)
                                Text("→ ${part.budgetName?:"Sem orçamento"}",color=Muted,style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    preview.settlements.forEach{settlement->
                        Column(Modifier.fillMaxWidth().background(Paper,RoundedCornerShape(12.dp)).padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                            Text("Liquidar ${settlement.incomeTitle}",color=Ink,fontWeight=FontWeight.SemiBold)
                            Text("Receita ${money(settlement.incomeAmount)} · ${settlement.expenseTitles.size} despesas",color=Green,style=MaterialTheme.typography.bodySmall)
                            Text(settlement.expenseTitles.joinToString("\n"){"• $it"},color=Muted,style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton={TextButton(enabled=!classificationImporting,onClick={
                classificationImporting=true
                model.importClassificationResponse(context,preview){success,error->
                    classificationImporting=false
                    if(success){
                        val settledExpenses=preview.settlements.sumOf{it.expenseTitles.size}
                        classificationImportedMessage=listOfNotNull(
                            preview.assignments.size.takeIf{it>0}?.let{"$it despesas classificadas"},
                            preview.expenseSplits.size.takeIf{it>0}?.let{"$it despesas divididas"},
                            preview.settlements.size.takeIf{it>0}?.let{"${preview.settlements.size} receitas liquidadas ($settledExpenses despesas)"}
                        ).joinToString(". ")
                        pendingClassificationImport=null
                    }else{
                        pendingClassificationImport=null
                        classificationNotice=error?:"Não foi possível importar a classificação."
                    }
                }
            }){Text(if(classificationImporting)"A importar…" else "Aplicar decisões",color=Green,fontWeight=FontWeight.Bold)}},
            dismissButton={TextButton(enabled=!classificationImporting,onClick={pendingClassificationImport=null}){Text("Cancelar",color=Muted)}}
        )
    }
    classificationImportedMessage?.let{message->AlertDialog(
        onDismissRequest={classificationImportedMessage=null},
        title={Text("Resposta importada",color=Ink,fontWeight=FontWeight.Bold)},
        text={Text(message,color=Muted)},
        confirmButton={TextButton(onClick={classificationImportedMessage=null;section="Movimentos";selectedMovementIds=emptySet()}){Text("Ver movimentos",color=Green)}}
    )}
}

@Composable private fun HomePage(modifier:Modifier,total:Double,tx:List<FinanceTransaction>,accounts:List<Account>,budgets:List<Budget>,budgetPeriods:List<BudgetPeriod>,navigate:(String)->Unit,requestDelete:(FinanceTransaction)->Unit,requestEdit:(FinanceTransaction)->Unit,addMovement:()->Unit,openBudget:(Budget)->Unit) {
    val cal=Calendar.getInstance(); val month=cal.get(Calendar.MONTH); val year=cal.get(Calendar.YEAR)
    val monthly=tx.filter { val c=Calendar.getInstance().apply{timeInMillis=it.date}; c.get(Calendar.MONTH)==month&&c.get(Calendar.YEAR)==year }
    val spent=moneySum(monthly.filter{it.active&&it.kind=="Despesa"}.map{it.amount}); val income=moneySum(monthly.filter{it.active&&it.kind=="Receita"}.map{it.amount})
    val activeBudgets=budgets.filter{budgetVisibleInMonth(it,currentMonthStart())}
    LazyColumn(modifier.fillMaxSize(), contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=24.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { BalanceCard(total, income, spent) }
        item { SectionTitle("As minhas contas", "Ver todas", {navigate("Contas")}) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) { accounts.take(3).forEach { AccountMini(it,tx) }; AddMini { navigate("Contas") } } }
        item { SectionTitle("Orçamento deste mês", "Ver orçamento", {navigate("Orçamentos")}) }
        if(activeBudgets.isEmpty()) item { EmptyCard("Ainda não tens orçamentos", "Define limites mensais para acompanhar as despesas.", "Criar orçamento", {navigate("Orçamentos")}) }
        else items(activeBudgets.take(3)) { b -> BudgetRow(b, tx, budgetPeriods, currentMonthStart()) { openBudget(b) } }
        item { SectionTitle("Movimentos recentes", "Ver todos", {navigate("Movimentos")}) }
        if(tx.isEmpty()) item { EmptyCard("Tudo começa aqui", "Regista a tua primeira receita ou despesa.", "Adicionar movimento", addMovement) }
        else items(tx.take(5),key={it.id}) { movement ->
            val expenseCount=if(movement.kind=="Receita")tx.count{it.settlementRevenueId==movement.id}else 0
            val settlementIncome=movement.settlementRevenueId?.let{id->tx.firstOrNull{it.id==id}}
            TransactionRow(movement,accounts,requestDelete,requestEdit,budgets,settledExpenseCount=expenseCount,settlementIncome=settlementIncome,settlementIncomeTitle=settlementIncome?.title?.takeIf{it.isNotBlank()})
        }
    }
}

@Composable private fun BalanceCard(total:Double,income:Double,spent:Double) {
    Column(Modifier.fillMaxWidth().background(Ink,RoundedCornerShape(24.dp)).padding(22.dp)) {
        Text("SALDO TOTAL",color=Color.White.copy(.72f),style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.SemiBold)
        Text(money(total),color=Color.White,style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=5.dp,bottom=20.dp))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            MetricPill("Receitas",income,Color(0xFFB5E7C7),Modifier.weight(1f)); MetricPill("Despesas",spent,Color(0xFFFFC9BE),Modifier.weight(1f))
        }
    }
}
@Composable private fun MetricPill(label:String,value:Double,color:Color,modifier:Modifier){Column(modifier.background(Color.White.copy(.09f),RoundedCornerShape(15.dp)).padding(12.dp)){Text(label,color=Color.White.copy(.7f),style=MaterialTheme.typography.labelMedium);Text(money(value),color=color,fontWeight=FontWeight.Bold)} }
@Composable private fun SectionTitle(title:String,action:String,onClick:()->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,color=Ink,fontWeight=FontWeight.Bold);TextButton(onClick=onClick){Text(action,color=Green)}}}
@Composable private fun AccountMini(account:Account,tx:List<FinanceTransaction>){val balance=accountBalance(account,tx);Column(Modifier.widthIn(min=112.dp).background(Color.White,RoundedCornerShape(18.dp)).padding(14.dp)){Icon(if(account.type=="Carteira")Icons.Default.Payments else Icons.Default.AccountBalance, null,tint=Green);Text(account.name,style=MaterialTheme.typography.labelMedium,color=Muted,modifier=Modifier.padding(top=10.dp));Text(money(balance),fontWeight=FontWeight.Bold,color=Ink)} }
@Composable private fun AddMini(onClick:()->Unit){Column(Modifier.width(78.dp).height(108.dp).border(1.dp,Color(0xFFDCE5DF),RoundedCornerShape(18.dp)).clickable(onClick=onClick).padding(12.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Default.Add,null,tint=Green);Text("Nova",color=Muted,style=MaterialTheme.typography.labelSmall)} }

@Composable private fun TransactionsPage(
    modifier:Modifier,tx:List<FinanceTransaction>,accounts:List<Account>,budgets:List<Budget>,closedKeys:Set<Pair<Long,Long>>,selectedIds:Set<Long>,onSelectionChange:(Set<Long>)->Unit,
    requestDeleteSelected:(List<FinanceTransaction>)->Unit,assignBudget:(FinanceTransaction)->Unit,
    requestEdit:(FinanceTransaction)->Unit,addMovement:()->Unit,importCsv:()->Unit,importClassificationResponse:()->Unit,
    requestClassification:(List<FinanceTransaction>)->Unit,
    requestSettlement:(FinanceTransaction)->Unit,viewSettlement:(FinanceTransaction)->Unit
){
    var kindFilter by remember{mutableStateOf("Todos")}
    var budgetFilter by remember{mutableStateOf("Todos")}
    var originFilter by remember{mutableStateOf("Todas")}
    var showInactive by remember{mutableStateOf(false)}
    var pendingView by remember{mutableStateOf(false)}
    var datesBeforePendingView by remember{mutableStateOf<Pair<Long?,Long?>?>(null)}
    val initialMonth=remember{currentMonthStart()}
    var dateFrom by remember{mutableStateOf<Long?>(initialMonth)}
    var dateTo by remember{mutableStateOf<Long?>(monthEndDay(initialMonth))}
    var showDateFilter by remember{mutableStateOf(false)}
    var sort by remember{mutableStateOf("Mais recentes")}
    var sortExpanded by remember{mutableStateOf(false)}
    val filtered=tx.filter{transaction->
        val pendingMatches=transaction.active&&(transaction.kind=="Receita"||(transaction.kind=="Despesa"&&transaction.budgetId==null&&transaction.settlementRevenueId==null))
        val typeMatches=pendingView||kindFilter=="Todos"||transaction.kind==kindFilter
        val budgetMatches=pendingView||when(budgetFilter){
            "Sem orçamento"->transaction.kind=="Despesa"&&transaction.budgetId==null
            "Com orçamento"->transaction.kind=="Despesa"&&transaction.budgetId!=null
            else->true
        }
        val isCsv=transaction.importedFromCsv||transaction.importKey!=null
        val originMatches=when(originFilter){"CSV"->isCsv;"Manual"->!isCsv;else->true}
        val activeMatches=if(pendingView)pendingMatches else showInactive||transaction.active
        val dateMatches=(dateFrom==null||transaction.date>=dateFrom!!) && (dateTo==null||transaction.date<=endOfDay(dateTo!!))
        typeMatches&&budgetMatches&&originMatches&&activeMatches&&dateMatches
    }.let{list->when(sort){
        "Mais antigos"->list.sortedWith(compareBy<FinanceTransaction>{it.date}.thenBy{it.id})
        "Maior valor"->list.sortedWith(compareByDescending<FinanceTransaction>{kotlin.math.abs(it.amount)}.thenByDescending{it.date})
        "Menor valor"->list.sortedWith(compareBy<FinanceTransaction>{kotlin.math.abs(it.amount)}.thenByDescending{it.date})
        else->list.sortedWith(compareByDescending<FinanceTransaction>{it.date}.thenByDescending{it.id})
    }}
    val selectedMovements=tx.filter{it.id in selectedIds}
    val selectionMode=selectedIds.isNotEmpty()
    val selectedUnassignedExpenses=selectedMovements.filter{it.kind=="Despesa"&&it.budgetId==null&&it.active&&it.settlementRevenueId==null}
    val classifiableSelectedCount=selectedMovements.count{transaction->
        transaction in selectedUnassignedExpenses&&
            budgets.any{budgetVisibleInMonth(it,currentMonthStart(transaction.date))&&(it.id to currentMonthStart(transaction.date)) !in closedKeys}
    }
    val requestableIncomeCount=selectedMovements.count{income->
        income.kind=="Receita"&&settlementIncomeCanBeRequested(income,tx)&&
            tx.filter{it.settlementRevenueId==income.id}.all{it.id in selectedIds}&&
            (tx.any{it.settlementRevenueId==income.id}||selectedUnassignedExpenses.isNotEmpty())
    }
    val canSendClassificationRequest=classifiableSelectedCount>0||requestableIncomeCount>0
    val visibleIds=filtered.map{it.id}.toSet()
    val allVisibleSelected=visibleIds.isNotEmpty()&&visibleIds.all{it in selectedIds}
    val dateFormatter=remember{SimpleDateFormat("dd/MM/yyyy",Locale("pt","PT"))}
    val dateFilterLabel=when{
        dateFrom==null&&dateTo==null->"Todas as datas"
        dateFrom==null->"Até ${dateFormatter.format(Date(dateTo!!))}"
        dateTo==null->"Desde ${dateFormatter.format(Date(dateFrom!!))}"
        else->"${dateFormatter.format(Date(dateFrom!!))} – ${dateFormatter.format(Date(dateTo!!))}"
    }
    Column(modifier.fillMaxSize().padding(horizontal=16.dp)){
        if(selectionMode) Column {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                TextButton(onClick={onSelectionChange(emptySet())}){Text("Cancelar",color=Green)}
                Text("${selectedMovements.size} selecionado(s)",Modifier.weight(1f),color=Muted,style=MaterialTheme.typography.labelMedium,textAlign=TextAlign.Center)
                IconButton(onClick={requestClassification(selectedMovements)},enabled=canSendClassificationRequest,modifier=Modifier.size(42.dp)){
                    Icon(Icons.Default.Share,"Pedir classificação ou liquidação",tint=if(canSendClassificationRequest)Green else Muted,modifier=Modifier.size(20.dp))
                }
                Button(onClick={requestDeleteSelected(selectedMovements)},enabled=selectedMovements.isNotEmpty(),colors=ButtonDefaults.buttonColors(containerColor=Coral),contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp)){
                    Icon(Icons.Default.Delete,null,modifier=Modifier.size(18.dp));Spacer(Modifier.width(5.dp));Text("Eliminar")
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
                TextButton(onClick={onSelectionChange(if(allVisibleSelected)emptySet() else visibleIds)},enabled=visibleIds.isNotEmpty()){
                    Icon(if(allVisibleSelected)Icons.Default.Deselect else Icons.Default.SelectAll,null,tint=Green)
                    Spacer(Modifier.width(5.dp));Text(if(allVisibleSelected)"Desmarcar todos" else "Selecionar todos",color=Green)
                }
            }
        } else Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Text("${filtered.size} movimentos",Modifier.weight(1f),color=Muted,style=MaterialTheme.typography.labelSmall)
            TextButton(onClick=importCsv,contentPadding=PaddingValues(horizontal=5.dp,vertical=2.dp)){Icon(Icons.Default.FileOpen,null,tint=Green,modifier=Modifier.size(17.dp));Spacer(Modifier.width(3.dp));Text("CSV",color=Green)}
            TextButton(onClick=importClassificationResponse,contentPadding=PaddingValues(horizontal=5.dp,vertical=2.dp)){Icon(Icons.Default.AssignmentTurnedIn,null,tint=Green,modifier=Modifier.size(17.dp));Spacer(Modifier.width(3.dp));Text("Resposta",color=Green)}
        }
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp),modifier=Modifier.horizontalScroll(rememberScrollState()).padding(bottom=2.dp)){
            FilterChip(
                selected=pendingView,
                onClick={
                    if(pendingView){
                        pendingView=false
                        datesBeforePendingView?.let{(from,to)->dateFrom=from;dateTo=to}
                        datesBeforePendingView=null
                    } else {
                        datesBeforePendingView=dateFrom to dateTo
                        dateFrom=null;dateTo=null
                        pendingView=true
                    }
                },
                label={Text("Receitas ativas e despesas sem orçamento")},
                colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Mint)
            )
            if(!pendingView) listOf("Todos","Despesa","Receita","Transferência","Ajuste").forEach{option->AssistChip(onClick={kindFilter=option},label={Text(option)},colors=AssistChipDefaults.assistChipColors(containerColor=if(kindFilter==option)Mint else Color.White))}
            if(!pendingView){
                listOf("Todos os orçamentos","Com orçamento","Sem orçamento").forEach{label->
                    val value=if(label=="Todos os orçamentos")"Todos" else label
                    AssistChip(onClick={budgetFilter=value},label={Text(label)},colors=AssistChipDefaults.assistChipColors(containerColor=if(budgetFilter==value)Mint else Color.White))
                }
                AssistChip(onClick={showInactive=!showInactive},label={Text(if(showInactive)"Inativos visíveis" else "Inativos ocultos")},colors=AssistChipDefaults.assistChipColors(containerColor=if(showInactive)Mint else Color.White))
            }
            listOf("Todas" to "Todas as origens","CSV" to "CSV","Manual" to "Manual").forEach{(value,label)->
                AssistChip(onClick={originFilter=value},label={Text(label)},colors=AssistChipDefaults.assistChipColors(containerColor=if(originFilter==value)Mint else Color.White))
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom=2.dp),verticalAlignment=Alignment.CenterVertically){
            FilterChip(
                selected=dateFrom!=null||dateTo!=null,onClick={showDateFilter=true},
                label={Text(dateFilterLabel)},leadingIcon={Icon(Icons.Default.CalendarMonth,null,modifier=Modifier.size(18.dp))},
                colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Mint)
            )
            Box(Modifier.weight(1f),contentAlignment=Alignment.CenterEnd){
                TextButton(onClick={sortExpanded=true},contentPadding=PaddingValues(horizontal=6.dp,vertical=2.dp)){Icon(Icons.Default.Sort,null,tint=Green);Spacer(Modifier.width(4.dp));Text(sort,color=Green)}
                DropdownMenu(expanded=sortExpanded,onDismissRequest={sortExpanded=false}){
                    listOf("Mais recentes","Mais antigos","Maior valor","Menor valor").forEach{option->DropdownMenuItem(text={Text(option)},onClick={sort=option;sortExpanded=false})}
                }
            }
        }
        if(selectionMode) Text("$classifiableSelectedCount despesas classificáveis · $requestableIncomeCount receitas liquidáveis · mantém premido para selecionar mais",color=Muted,style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(bottom=5.dp))
        if(filtered.isEmpty()) EmptyCard(if(tx.isEmpty())"Sem movimentos" else "Sem resultados",if(tx.isEmpty())"Importa o extrato CSV ou adiciona movimentos manualmente." else "Não há movimentos com estes filtros.","Novo movimento",addMovement)
        else LazyColumn(verticalArrangement=Arrangement.spacedBy(9.dp)){items(filtered,key={it.id}){transaction->
            val isSelected=transaction.id in selectedIds
            val linkedExpenses=if(transaction.kind=="Receita")tx.filter{it.settlementRevenueId==transaction.id} else emptyList()
            val settlementIncome=transaction.settlementRevenueId?.let{id->tx.firstOrNull{it.id==id}}
            val settlementIncomeTitle=transaction.settlementRevenueId?.let{id->tx.firstOrNull{it.id==id}?.title?.takeIf{it.isNotBlank()}?:"Receita"}
            TransactionRow(
                transaction,accounts,{},requestEdit,budgets,
                selectionMode=selectionMode,selected=isSelected,
                onSelect={onSelectionChange(if(isSelected)selectedIds-transaction.id else selectedIds+transaction.id)},
                assignBudget=assignBudget,
                settleIncome=if(transaction.kind=="Receita"&&transaction.active)requestSettlement else null,
                settledExpenseCount=linkedExpenses.size,
                viewSettlement=if(linkedExpenses.isNotEmpty()||settlementIncome!=null)viewSettlement else null,
                settlementIncomeTitle=settlementIncomeTitle,
                settlementIncome=settlementIncome
            )
        }}
    }
    if(showDateFilter) DateFilterDialog(dateFrom,dateTo,{from,to->dateFrom=from;dateTo=to;showDateFilter=false},{dateFrom=null;dateTo=null;showDateFilter=false},{showDateFilter=false})
}
@Composable private fun AccountsPage(modifier:Modifier,accounts:List<Account>,tx:List<FinanceTransaction>,add:()->Unit,edit:(Account)->Unit){Column(modifier.fillMaxSize().padding(20.dp)){Row(verticalAlignment=Alignment.CenterVertically){Text("Contas e carteiras",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,color=Ink);TextButton(onClick=add){Text("+ Adicionar")}};Spacer(Modifier.height(8.dp));accounts.forEach{account->AccountDetail(account,tx){edit(account)};Spacer(Modifier.height(10.dp))}}}
@Composable private fun AccountDetail(a:Account,tx:List<FinanceTransaction>,edit:()->Unit){val bal=accountBalance(a,tx);Row(Modifier.fillMaxWidth().background(Color.White,RoundedCornerShape(18.dp)).padding(start=18.dp,top=12.dp,bottom=12.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.AccountBalanceWallet,null,tint=Green,modifier=Modifier.size(40.dp));Column(Modifier.weight(1f).padding(start=14.dp)){Text(a.name,color=Ink,fontWeight=FontWeight.Bold);Text(a.type,color=Muted,style=MaterialTheme.typography.bodySmall)};Text(money(bal),color=Ink,fontWeight=FontWeight.Bold);IconButton(onClick=edit){Icon(Icons.Default.Edit,"Editar conta",tint=Green)}} }

@Composable private fun BudgetsPage(
    modifier:Modifier,budgets:List<Budget>,closedKeys:Set<Pair<Long,Long>>,tx:List<FinanceTransaction>,budgetPeriods:List<BudgetPeriod>,
    selectedMonth:Long,onMonthChange:(Long)->Unit,sort:String,onSortChange:(String)->Unit,
    showRemaining:Boolean,onShowRemainingChange:(Boolean)->Unit,add:()->Unit,quickAdd:(Budget)->Unit,openBudget:(Budget)->Unit,
    setClosed:(Budget,Boolean)->Unit
){
    val allVisible=budgets.filter{budgetVisibleInMonth(it,selectedMonth)}
    var showClosed by remember{mutableStateOf(false)}
    val closedCount=allVisible.count{(it.id to selectedMonth) in closedKeys}
    var showArchived by remember{mutableStateOf(false)}
    val archivedInMonth=budgets.filter{!budgetVisibleInMonth(it,selectedMonth)}
    val visible=(if(showClosed)allVisible else allVisible.filter{(it.id to selectedMonth) !in closedKeys})+
        (if(showArchived)archivedInMonth else emptyList())
    val sorted=when(sort){
        "Nome A–Z"->visible.sortedBy{it.name.lowercase(Locale("pt","PT"))}
        "Nome Z–A"->visible.sortedByDescending{it.name.lowercase(Locale("pt","PT"))}
        "Menor gasto"->visible.sortedBy{budgetSpent(it,tx,selectedMonth)}
        "Maior limite"->visible.sortedByDescending{budgetLimitFor(it,budgetPeriods,selectedMonth)}
        "Menor limite"->visible.sortedBy{budgetLimitFor(it,budgetPeriods,selectedMonth)}
        "Maior percentagem"->visible.sortedByDescending{budgetSpent(it,tx,selectedMonth)/budgetLimitFor(it,budgetPeriods,selectedMonth).coerceAtLeast(.01)}
        "Menor percentagem"->visible.sortedBy{budgetSpent(it,tx,selectedMonth)/budgetLimitFor(it,budgetPeriods,selectedMonth).coerceAtLeast(.01)}
        else->visible.sortedByDescending{budgetSpent(it,tx,selectedMonth)}
    }
    val totalLimit=moneySum(allVisible.map{budgetLimitFor(it,budgetPeriods,selectedMonth)})
    val totalSpent=moneySum(allVisible.map{budgetSpent(it,tx,selectedMonth)})
    val totalRemaining=moneySum(listOf(totalLimit,-totalSpent))
    var sortExpanded by remember{mutableStateOf(false)}
    LazyColumn(modifier.fillMaxSize(),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=8.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item {
            Row(verticalAlignment=Alignment.CenterVertically){
                Text("Orçamentos mensais",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,color=Ink)
                Box {
                    TextButton(onClick={sortExpanded=true}){Icon(Icons.Default.Sort,null,tint=Green);Spacer(Modifier.width(4.dp));Text(sort,color=Green)}
                    DropdownMenu(expanded=sortExpanded,onDismissRequest={sortExpanded=false}){
                        listOf("Maior gasto","Menor gasto","Maior limite","Menor limite","Maior percentagem","Menor percentagem","Nome A–Z","Nome Z–A").forEach{option->DropdownMenuItem(text={Text(option)},onClick={onSortChange(option);sortExpanded=false})}
                    }
                }
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.White).padding(3.dp)){
                listOf(false to "Gasto (%)",true to "Restante (€)").forEach{(remaining,label)->
                    TextButton(onClick={onShowRemainingChange(remaining)},modifier=Modifier.weight(1f).background(if(showRemaining==remaining)Green else Color.Transparent,RoundedCornerShape(22.dp))){Text(label,color=if(showRemaining==remaining)Color.White else Ink)}
                }
            }
        }
        item { MonthNavigator(selectedMonth,onMonthChange) }
        item { BudgetTotalsCard(totalLimit,totalSpent,totalRemaining) }
        if(closedCount>0||archivedInMonth.isNotEmpty()) item {
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp),modifier=Modifier.horizontalScroll(rememberScrollState())){
                if(closedCount>0) AssistChip(onClick={showClosed=!showClosed},label={Text(if(showClosed)"A mostrar fechados ($closedCount)" else "Fechados ocultos ($closedCount)")},colors=AssistChipDefaults.assistChipColors(containerColor=if(showClosed)Mint else Color.White))
                if(archivedInMonth.isNotEmpty()) AssistChip(onClick={showArchived=!showArchived},label={Text(if(showArchived)"A mostrar arquivados (${archivedInMonth.size})" else "Arquivados ocultos (${archivedInMonth.size})")},colors=AssistChipDefaults.assistChipColors(containerColor=if(showArchived)Mint else Color.White))
            }
        }
        if(sorted.isEmpty()) item {
            if(allVisible.isNotEmpty()) EmptyCard("Todos os orçamentos deste mês estão fechados","Mostra os fechados para os ver ou reabre um orçamento no respetivo detalhe.","Mostrar fechados",{showClosed=true})
            else EmptyCard("Sem orçamentos neste mês","Cria um limite mensal para acompanhar as tuas despesas.","Criar orçamento",add)
        }
        else items(sorted,key={it.id}){budget->
            val closed=(budget.id to selectedMonth) in closedKeys
            val archived=!budgetVisibleInMonth(budget,selectedMonth)
            BudgetRow(
                budget,tx,budgetPeriods,selectedMonth,showRemaining,
                if(selectedMonth==currentMonthStart()&&!closed&&!archived){{quickAdd(budget)}}else null,
                closed,archived,
                if(archived)null else {{setClosed(budget,!closed)}}
            ){openBudget(budget)}
        }
        item { OutlinedButton(onClick=add,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Add,null);Spacer(Modifier.width(6.dp));Text("Criar orçamento mensal")} }
    }
}

@Composable private fun BudgetTotalsCard(totalLimit:Double,totalSpent:Double,totalRemaining:Double){
    Column(Modifier.fillMaxWidth().background(Ink,RoundedCornerShape(19.dp)).padding(14.dp)){
        Text("TOTAL DOS ORÇAMENTOS",color=Color.White.copy(.72f),style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.SemiBold)
        Text(money(totalLimit),color=Color.White,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=2.dp,bottom=10.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Column(Modifier.weight(1f).background(Color.White.copy(.09f),RoundedCornerShape(13.dp)).padding(horizontal=10.dp,vertical=8.dp)){
                Text("Já gasto",color=Color.White.copy(.72f),style=MaterialTheme.typography.labelSmall)
                Text(money(totalSpent),color=Color(0xFFFFC9BE),fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.bodyMedium,maxLines=1)
            }
            Column(Modifier.weight(1f).background(Color.White.copy(.09f),RoundedCornerShape(13.dp)).padding(horizontal=10.dp,vertical=8.dp)){
                Text(if(totalRemaining<0)"Acima do limite" else "Ainda por gastar",color=Color.White.copy(.72f),style=MaterialTheme.typography.labelSmall,maxLines=1)
                Text(money(kotlin.math.abs(totalRemaining)),color=if(totalRemaining<0)Color(0xFFFFC9BE) else Color(0xFFB5E7C7),fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.bodyMedium,maxLines=1)
            }
        }
    }
}

@Composable private fun MonthNavigator(month:Long,onMonthChange:(Long)->Unit){
    Row(Modifier.fillMaxWidth().background(Color.White,RoundedCornerShape(24.dp)).padding(horizontal=8.dp,vertical=2.dp),verticalAlignment=Alignment.CenterVertically){
        IconButton(onClick={onMonthChange(shiftMonth(month,-1))}){Icon(Icons.Default.ChevronLeft,"Mês anterior",tint=Green)}
        TextButton(onClick={onMonthChange(currentMonthStart())},modifier=Modifier.weight(1f)){Text(if(month==currentMonthStart())"Este mês" else monthLabel(month),color=Ink,fontWeight=FontWeight.SemiBold)}
        IconButton(onClick={onMonthChange(shiftMonth(month,1))}){Icon(Icons.Default.ChevronRight,"Mês seguinte",tint=Green)}
    }
}

@Composable private fun BudgetRow(b:Budget,tx:List<FinanceTransaction>,periods:List<BudgetPeriod>,monthStart:Long,showRemaining:Boolean=false,onQuickAdd:(()->Unit)?=null,closed:Boolean=false,archived:Boolean=false,onCloseToggle:(()->Unit)?=null,onClick:()->Unit){
    val tag=when{archived->"Arquivado";closed->"Fechado";else->null}
    val limit=budgetLimitFor(b,periods,monthStart).coerceAtLeast(0.01); val spent=budgetSpent(b,tx,monthStart); val ratio=spent/limit; val progress=ratio.toFloat().coerceIn(0f,1f)
    val dimmed=closed||archived
    Column(Modifier.fillMaxWidth().clickable(onClick=onClick).background(if(dimmed)Color(0xFFE9ECEA) else Color.White,RoundedCornerShape(17.dp)).alpha(if(dimmed)0.6f else 1f).padding(horizontal=13.dp,vertical=10.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){
            Text(b.name,color=Ink,fontWeight=FontWeight.SemiBold,maxLines=1,modifier=Modifier.weight(1f,fill=tag==null))
            if(tag!=null) Text(tag,color=Muted,style=MaterialTheme.typography.labelSmall,modifier=Modifier.weight(1f).padding(start=6.dp))
            Text(if(showRemaining)money(limit-spent) else "${(ratio*100).toInt()}% de ${money(limit)}",color=if(ratio>1)Coral else Ink,fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.bodySmall,maxLines=1,softWrap=false,textAlign=TextAlign.End)
            if(onQuickAdd!=null) IconButton(onClick=onQuickAdd,modifier=Modifier.size(36.dp)){Icon(Icons.Default.Add,"Adicionar movimento a ${b.name}",tint=Green,modifier=Modifier.size(22.dp))}
            if(onCloseToggle!=null) IconButton(onClick=onCloseToggle,modifier=Modifier.size(36.dp)){
                Icon(if(closed)Icons.Default.LockOpen else Icons.Default.Lock,if(closed)"Reabrir ${b.name} neste mês" else "Fechar ${b.name} neste mês",tint=if(closed)Green else Muted,modifier=Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(6.dp));LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(5.dp)),color=if(ratio>1)Coral else Green,trackColor=Mint,strokeCap= androidx.compose.ui.graphics.StrokeCap.Round)
        Text(if(showRemaining)"Gasto ${money(spent)}" else if(spent>limit)"Ultrapassado ${money(spent-limit)}" else "Disponível ${money(limit-spent)}",color=if(ratio>1)Coral else Muted,style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(top=4.dp))
    }
}

@Composable private fun BudgetDetailPage(
    modifier:Modifier,budget:Budget?,closed:Boolean,setClosed:(Budget,Boolean)->Unit,tx:List<FinanceTransaction>,accounts:List<Account>,periods:List<BudgetPeriod>,
    selectedMonth:Long,onMonthChange:(Long)->Unit,add:()->Unit,requestDelete:(FinanceTransaction)->Unit,
    requestEdit:(FinanceTransaction)->Unit,editLimit:(Budget,Long,Double)->Unit,
    editName:(Budget)->Unit,archive:(Budget)->Unit,unarchive:(Budget)->Unit
){
    if(budget==null){EmptyCard("Orçamento indisponível","Este orçamento já não existe.","Voltar aos orçamentos",{}) ; return}
    val records=budgetTransactions(budget,tx,selectedMonth)
    val archivedInSelectedMonth=budget.archivedFromMonth?.let{selectedMonth>=it}?:false
    val canAdd=selectedMonth==currentMonthStart()&&!archivedInSelectedMonth&&!closed
    Column(modifier.fillMaxSize().padding(horizontal=16.dp)){
        MonthNavigator(selectedMonth,onMonthChange)
        Row(Modifier.fillMaxWidth().padding(top=4.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically){
            Text("${records.size} registos",Modifier.weight(1f),color=Muted,style=MaterialTheme.typography.labelMedium)
            TextButton(onClick={editName(budget)}){Icon(Icons.Default.Edit,null);Spacer(Modifier.width(4.dp));Text("Editar nome")}
            if(budget.archivedFromMonth==null) TextButton(onClick={archive(budget)}){Icon(Icons.Default.Archive,null);Spacer(Modifier.width(4.dp));Text("Arquivar",color=Coral)}
            else TextButton(onClick={unarchive(budget)}){Icon(Icons.Default.Unarchive,null,tint=Green);Spacer(Modifier.width(4.dp));Text("Desarquivar",color=Green)}
        }
        BudgetRow(budget,tx,periods,selectedMonth,closed=closed){ }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
            if(!archivedInSelectedMonth) TextButton(onClick={setClosed(budget,!closed)}){Text(if(closed)"Reabrir este mês" else "Fechar este mês",color=if(closed)Green else Coral)}
            TextButton(onClick={editLimit(budget,selectedMonth,budgetLimitFor(budget,periods,selectedMonth))}){Text("Alterar limite deste mês",color=Green)}
        }
        if(closed) Text("Orçamento fechado neste mês: não aparece na lista por defeito nem pode ser associado a novas despesas. Os registos existentes mantêm-se.",color=Muted,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(vertical=4.dp))
        if(archivedInSelectedMonth) Text("Orçamento arquivado a partir de ${budget.archivedFromMonth?.let{monthLabel(it)}?:"este mês"}. Os registos anteriores continuam disponíveis.",color=Muted,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(vertical=4.dp))
        Text("Toca num movimento para editar; mantém premido para apagar.",color=Muted,style=MaterialTheme.typography.labelSmall)
        Row(Modifier.fillMaxWidth().padding(top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically){Text("Registos do mês",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,color=Ink,fontWeight=FontWeight.Bold);Text(records.size.toString(),color=Muted)}
        if(records.isEmpty()) {
            if(canAdd) EmptyCard("Ainda sem registos","As despesas associadas a este orçamento aparecem aqui.","Adicionar movimento",add)
            else Text("Sem registos neste mês.",color=Muted,modifier=Modifier.padding(vertical=16.dp))
        } else LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){items(records,key={it.id}){TransactionRow(it,accounts,requestDelete,requestEdit,listOf(budget))}}
        if(canAdd) Button(onClick=add,modifier=Modifier.fillMaxWidth().padding(vertical=10.dp)){Icon(Icons.Default.Add,null);Spacer(Modifier.width(8.dp));Text("Adicionar movimento")}
    }
}

internal fun budgetVisibleInMonth(budget:Budget,monthStart:Long):Boolean = budget.archivedFromMonth?.let{monthStart<it}?:true

private fun settlementIncomeCanBeRequested(income:FinanceTransaction,transactions:List<FinanceTransaction>):Boolean{
    if(income.kind!="Receita"||!income.amount.isFinite()||kotlin.math.abs(income.amount)<0.01)return false
    val previous=transactions.filter{it.settlementRevenueId==income.id}
    return if(previous.isEmpty())income.active else !income.active&&previous.all{
        it.kind=="Despesa"&&it.budgetId==null&&it.settlementRevenueId==income.id&&!it.active
    }
}

private fun budgetTransactions(budget:Budget,tx:List<FinanceTransaction>,monthStart:Long):List<FinanceTransaction> = tx.filter { t ->
    t.kind=="Despesa" && currentMonthStart(t.date)==monthStart && t.budgetId==budget.id
}
private fun budgetSpent(budget:Budget,tx:List<FinanceTransaction>,monthStart:Long):Double = moneySum(budgetTransactions(budget,tx,monthStart).filter{it.active}.map{it.amount})
private fun budgetLimitFor(budget:Budget,periods:List<BudgetPeriod>,monthStart:Long):Double = periods
    .filter{it.budgetId==budget.id&&it.monthStart<=monthStart}
    .maxByOrNull{it.monthStart}?.limitAmount?:budget.limitAmount

@Composable private fun ReportsPage(modifier:Modifier,tx:List<FinanceTransaction>){
    val active=tx.filter{it.active}
    val exp=moneySum(active.filter{it.kind=="Despesa"}.map{it.amount})
    val inc=moneySum(active.filter{it.kind=="Receita"}.map{it.amount})
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)){
        Text("Resumo geral",style=MaterialTheme.typography.titleLarge,color=Ink,fontWeight=FontWeight.Bold)
        Text("Com base nos movimentos ativos",color=Muted)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
            ReportTile("Receitas",inc,Green,Modifier.weight(1f));ReportTile("Despesas",exp,Coral,Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        Text("${active.size} movimentos ativos · ${tx.size-active.size} inativos",color=Muted)
    }
}
@Composable private fun ReportTile(label:String,value:Double,color:Color,modifier:Modifier){Column(modifier.background(Color.White,RoundedCornerShape(18.dp)).padding(16.dp)){Text(label,color=Muted);Text(money(value),color=color,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)} }

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun TransactionRow(
    t:FinanceTransaction,accounts:List<Account>,delete:(FinanceTransaction)->Unit,edit:(FinanceTransaction)->Unit,budgets:List<Budget> = emptyList(),
    selectionMode:Boolean=false,selected:Boolean=false,onSelect:(()->Unit)?=null,assignBudget:((FinanceTransaction)->Unit)?=null,
    settleIncome:((FinanceTransaction)->Unit)?=null,settledExpenseCount:Int=0,viewSettlement:((FinanceTransaction)->Unit)?=null,settlementIncomeTitle:String?=null,
    settlementIncome:FinanceTransaction?=null
){
    val expense=t.kind=="Despesa"||t.kind=="Transferência"||(t.kind=="Ajuste"&&t.amount<0); val color=if(!t.active)Muted else if(expense)Coral else Green
    val settlementLocked=t.settlementRevenueId!=null||settledExpenseCount>0
    val budgetLabel=if(t.kind=="Despesa")if(t.budgetId==null)"Sem orçamento" else budgets.firstOrNull{it.id==t.budgetId}?.name?:"Orçamento" else null
    Row(Modifier.fillMaxWidth().background(if(selected)Mint else if(t.active)Color.White else Color(0xFFF0F2EF),RoundedCornerShape(17.dp)).combinedClickable(
        onClick={if(selectionMode)onSelect?.invoke() else if(settlementLocked){
            if(t.settlementRevenueId!=null)settlementIncome?.let{viewSettlement?.invoke(it)}
            else viewSettlement?.invoke(t)
        }else edit(t)},
        onLongClick={if(onSelect!=null)onSelect() else delete(t)}
    ).padding(14.dp),verticalAlignment=Alignment.CenterVertically){
        Box(Modifier.size(42.dp).background(if(selectionMode||!t.active)Color.White else if(expense)Color(0xFFFFEFEC) else Mint,CircleShape),contentAlignment=Alignment.Center){
            if(selectionMode) Icon(if(selected)Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,"Selecionar movimento",tint=if(selected)Green else Muted,modifier=Modifier.size(22.dp))
            else Icon(if(t.kind=="Transferência")Icons.Default.SwapHoriz else if(expense)Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,null,tint=color,modifier=Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal=12.dp)){
            Text(t.title.ifBlank{t.kind},color=if(t.active)Ink else Muted,fontWeight=FontWeight.SemiBold)
            val details=listOfNotNull(budgetLabel,if(t.recurring)"Recorrente" else null,if(t.importedFromCsv||t.importKey!=null)"CSV" else null,if(!t.active)"Inativo" else null,settlementIncomeTitle?.let{"Liquidada com $it"})
            Text(details.joinToString(" · "),color=if(t.active&&t.kind=="Despesa"&&t.budgetId==null)Coral else Muted,style=MaterialTheme.typography.labelSmall,maxLines=1)
            if(t.kind=="Despesa"&&t.budgetId==null&&assignBudget!=null) TextButton(
                onClick={assignBudget(t)},contentPadding=PaddingValues(horizontal=0.dp,vertical=0.dp),modifier=Modifier.height(30.dp)
            ){Text("Atribuir orçamento",color=Green,style=MaterialTheme.typography.labelSmall)}
            if(t.kind=="Receita"&&t.active&&!selectionMode&&settleIncome!=null) TextButton(
                onClick={settleIncome(t)},contentPadding=PaddingValues(horizontal=0.dp,vertical=0.dp),modifier=Modifier.height(30.dp)
            ){Text("Liquidar",color=Green,style=MaterialTheme.typography.labelSmall)}
            if(t.kind=="Receita"&&!t.active&&settledExpenseCount>0&&viewSettlement!=null) TextButton(
                onClick={viewSettlement(t)},contentPadding=PaddingValues(horizontal=0.dp,vertical=0.dp),modifier=Modifier.height(30.dp)
            ){Text("Ver liquidação · $settledExpenseCount despesas",color=Green,style=MaterialTheme.typography.labelSmall)}
            if(t.kind=="Despesa"&&settlementIncome!=null&&viewSettlement!=null) TextButton(
                onClick={viewSettlement(settlementIncome)},contentPadding=PaddingValues(horizontal=0.dp,vertical=0.dp),modifier=Modifier.height(30.dp)
            ){Text("Ver receita associada",color=Green,style=MaterialTheme.typography.labelSmall)}
        }
        Column(horizontalAlignment=Alignment.End){Text("${if(expense)"−" else "+"}${money(kotlin.math.abs(t.amount))}",color=color,fontWeight=FontWeight.Bold);Text(SimpleDateFormat("dd MMM",Locale("pt","PT")).format(Date(t.date)),color=Muted,style=MaterialTheme.typography.labelSmall)}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DateFilterDialog(
    initialStart:Long?,initialEnd:Long?,apply:(Long?,Long?)->Unit,clear:()->Unit,close:()->Unit
){
    val state=rememberDateRangePickerState(
        initialSelectedStartDateMillis=initialStart?.let(::localDateToPickerMillis),
        initialSelectedEndDateMillis=initialEnd?.let(::localDateToPickerMillis)
    )
    DatePickerDialog(
        onDismissRequest=close,
        confirmButton={TextButton(
            onClick={apply(state.selectedStartDateMillis?.let(::pickerDateToLocalStart),state.selectedEndDateMillis?.let(::pickerDateToLocalStart))},
            enabled=state.selectedStartDateMillis!=null||state.selectedEndDateMillis!=null
        ){Text("Aplicar",color=Green,fontWeight=FontWeight.Bold)}},
        dismissButton={Row{
            TextButton(onClick=clear){Text("Limpar",color=Coral)}
            TextButton(onClick=close){Text("Cancelar",color=Muted)}
        }}
    ){
        DateRangePicker(
            state=state,
            title={Text("Filtrar por data",color=Ink,fontWeight=FontWeight.Bold)},
            showModeToggle=false,
            modifier=Modifier.fillMaxWidth().height(500.dp).padding(8.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun BudgetAssignmentDialog(
    transaction:FinanceTransaction,budgets:List<Budget>,closedKeys:Set<Pair<Long,Long>>,assign:(FinanceTransaction,Long,Boolean)->Unit,close:()->Unit
){
    val movementMonth=currentMonthStart(transaction.date)
    val availableBudgets=budgets.filter{budgetVisibleInMonth(it,movementMonth)&&(it.id to movementMonth) !in closedKeys}
    var budgetId by remember(transaction.id){mutableLongStateOf(0L)}
    var expanded by remember{mutableStateOf(false)}
    var rememberForFuture by remember(transaction.id){mutableStateOf(false)}
    val selectedBudget=availableBudgets.firstOrNull{it.id==budgetId}
    AlertDialog(
        onDismissRequest=close,
        title={Text("Associar orçamento",color=Ink,fontWeight=FontWeight.Bold)},
        text={
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                Text("Escolhe o orçamento para ‘${transaction.title.ifBlank{transaction.kind}}’.",color=Muted)
                if(availableBudgets.isEmpty()) Text("Não há orçamentos disponíveis no mês deste movimento.",color=Muted)
                else ExposedDropdownMenuBox(expanded=expanded,onExpandedChange={expanded=it}){
                    OutlinedTextField(
                        value=selectedBudget?.name.orEmpty(),onValueChange={},readOnly=true,
                        label={Text("Orçamento")},placeholder={Text("Selecionar orçamento")},
                        modifier=Modifier.menuAnchor().fillMaxWidth(),
                        trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded=expanded)}
                    )
                    ExposedDropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){
                        availableBudgets.forEach{budget->DropdownMenuItem(text={Text(budget.name)},onClick={budgetId=budget.id;expanded=false})}
                    }
                }
                if(normalizeMovementKey(transaction.title).isNotBlank()) Row(verticalAlignment=Alignment.CenterVertically){
                    Checkbox(checked=rememberForFuture,onCheckedChange={rememberForFuture=it})
                    Text("Definir como orçamento por defeito para este descritivo",color=Ink,style=MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton={TextButton(onClick={selectedBudget?.let{assign(transaction,it.id,rememberForFuture);close()}},enabled=selectedBudget!=null){Text("Associar",color=Green)}},
        dismissButton={TextButton(onClick=close){Text("Cancelar",color=Muted)}}
    )
}

@Composable private fun SettleIncomeDialog(
    income:FinanceTransaction,expenses:List<FinanceTransaction>,initialSelectedIds:Set<Long>,settle:(Set<Long>,(Boolean)->Unit)->Unit,close:()->Unit
){
    val targetCents=BigDecimal.valueOf(kotlin.math.abs(income.amount)).setScale(2,RoundingMode.HALF_UP).movePointRight(2).toBigInteger()
    fun expenseCents(expense:FinanceTransaction)=BigDecimal.valueOf(kotlin.math.abs(expense.amount)).setScale(2,RoundingMode.HALF_UP).movePointRight(2).toBigInteger()
    // Primeiro as despesas com o valor exato da receita; depois as restantes por valor crescente.
    val sortedExpenses=expenses.sortedWith(
        compareBy<FinanceTransaction>{if(expenseCents(it)==targetCents)0 else 1}
            .thenBy{expenseCents(it)}
            .thenByDescending{it.date}
            .thenByDescending{it.id}
    )
    var selectedIds by remember(income.id,initialSelectedIds){mutableStateOf(initialSelectedIds)}
    var saving by remember(income.id){mutableStateOf(false)}
    var error by remember(income.id){mutableStateOf("")}
    val dateFormat=remember{SimpleDateFormat("dd MMM yyyy",Locale("pt","PT"))}
    val selectedTotal=sortedExpenses.filter{it.id in selectedIds}.fold(BigDecimal.ZERO){total,expense->
        total.add(BigDecimal.valueOf(kotlin.math.abs(expense.amount)).setScale(2,RoundingMode.HALF_UP))
    }.setScale(2,RoundingMode.HALF_UP)
    val targetTotal=BigDecimal.valueOf(kotlin.math.abs(income.amount)).setScale(2,RoundingMode.HALF_UP)
    val totalMatches=selectedIds.isNotEmpty()&&selectedTotal.compareTo(targetTotal)==0
    fun toggle(id:Long){selectedIds=if(id in selectedIds)selectedIds-id else selectedIds+id}
    AlertDialog(
        onDismissRequest={if(!saving)close()},
        title={Text(if(initialSelectedIds.isEmpty())"Liquidar receita" else "Editar liquidação",color=Ink,fontWeight=FontWeight.Bold)},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("Receita: ${income.title.ifBlank{"Receita"}} · ${money(income.amount)}",color=Ink,fontWeight=FontWeight.SemiBold)
            Text(if(initialSelectedIds.isEmpty())"Seleciona despesas ativas sem orçamento. A receita e as despesas selecionadas ficam inativas." else "Altera as despesas associadas. As despesas retiradas da liquidação voltam a ficar ativas.",color=Muted,style=MaterialTheme.typography.bodySmall)
            if(sortedExpenses.isEmpty()) Text("Não existem despesas disponíveis para liquidar.",color=Muted)
            else Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState())){
                sortedExpenses.forEach{expense->
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                        Checkbox(checked=expense.id in selectedIds,onCheckedChange={toggle(expense.id)},enabled=!saving)
                        Column(Modifier.weight(1f)){
                            Text(expense.title.ifBlank{"Despesa"},color=Ink,fontWeight=FontWeight.SemiBold)
                            Text("${money(kotlin.math.abs(expense.amount))} · ${dateFormat.format(Date(expense.date))}${if(expense.settlementRevenueId==income.id)" · Já associada" else ""}",color=Muted,style=MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Text("Total selecionado: ${money(selectedTotal.toDouble())} / ${money(targetTotal.toDouble())}",color=if(totalMatches)Green else Coral,fontWeight=FontWeight.SemiBold)
            if(!totalMatches)Text("A soma das despesas tem de ser igual ao valor da receita.",color=Coral,style=MaterialTheme.typography.bodySmall)
            if(error.isNotBlank()) Text(error,color=Coral,style=MaterialTheme.typography.bodySmall)
        }},
        confirmButton={TextButton(enabled=!saving&&totalMatches,onClick={
            saving=true;error=""
            settle(selectedIds){success->saving=false;if(success)close() else error="Não foi possível guardar. Confirma se os movimentos e os respetivos valores continuam disponíveis."}
        }){Text(if(saving)"A guardar…" else if(initialSelectedIds.isEmpty())"Confirmar liquidação" else "Guardar liquidação",color=Green,fontWeight=FontWeight.Bold)}},
        dismissButton={TextButton(onClick=close,enabled=!saving){Text("Cancelar",color=Muted)}}
    )
}

@Composable private fun SettledExpensesDialog(income:FinanceTransaction,expenses:List<FinanceTransaction>,edit:()->Unit,undo:()->Unit,close:()->Unit){
    val sortedExpenses=expenses.sortedWith(compareBy<FinanceTransaction>{it.date}.thenBy{it.id})
    val dateFormat=remember{SimpleDateFormat("dd MMM yyyy",Locale("pt","PT"))}
    AlertDialog(
        onDismissRequest=close,
        title={Text("Liquidação da receita",color=Ink,fontWeight=FontWeight.Bold)},
        text={Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text("Receita: ${income.title.ifBlank{"Receita"}} · ${money(income.amount)}",color=Ink,fontWeight=FontWeight.SemiBold)
            Text("${sortedExpenses.size} despesas liquidadas",color=Muted,style=MaterialTheme.typography.bodySmall)
            sortedExpenses.forEach{expense->
                Column(Modifier.fillMaxWidth().background(Paper,RoundedCornerShape(12.dp)).padding(10.dp)){
                    Text(expense.title.ifBlank{"Despesa"},color=Ink,fontWeight=FontWeight.SemiBold)
                    Text("−${money(kotlin.math.abs(expense.amount))} · ${dateFormat.format(Date(expense.date))}",color=Coral,style=MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(onClick=undo,modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.outlinedButtonColors(contentColor=Coral)){
                Icon(Icons.Default.Undo,null);Spacer(Modifier.width(6.dp));Text("Desfazer liquidação")
            }
        }},
        confirmButton={Row{
            TextButton(onClick=edit){Text("Editar liquidação",color=Green,fontWeight=FontWeight.Bold)}
            TextButton(onClick=close){Text("Fechar",color=Muted)}
        }}
    )
}

@Composable private fun EmptyCard(title:String,body:String,action:String,onClick:()->Unit){Column(Modifier.fillMaxWidth().background(Color.White,RoundedCornerShape(20.dp)).padding(20.dp)){Text(title,color=Ink,fontWeight=FontWeight.Bold);Text(body,color=Muted,modifier=Modifier.padding(top=5.dp,bottom=12.dp));OutlinedButton(onClick=onClick){Text(action,color=Green)}}}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TransactionDialog(
    accounts:List<Account>, budgets:List<Budget>, closedKeys:Set<Pair<Long,Long>>, budgetRules:List<BudgetRule>,pinnedBudget:Budget?, initial:FinanceTransaction?,
    save:(FinanceTransaction,Boolean,(Boolean)->Unit)->Unit, close:()->Unit, createBudget:()->Unit, split:(FinanceTransaction)->Unit
){
    val movementId=initial?.id?:0L
    var title by remember(movementId){mutableStateOf(initial?.title.orEmpty())}
    var amount by remember(movementId){mutableStateOf(initial?.amount?.let{kotlin.math.abs(it)}?.toString().orEmpty())}
    var movementDate by remember(movementId){mutableLongStateOf(initial?.date?:System.currentTimeMillis())}
    var showMovementDatePicker by remember(movementId){mutableStateOf(false)}
    var kind by remember(movementId){mutableStateOf(initial?.kind?:"Despesa")}
    var adjustmentDecreases by remember(movementId){mutableStateOf(initial?.let{it.kind=="Ajuste"&&it.amount<0}?:false)}
    var active by remember(movementId){mutableStateOf(initial?.active?:true)}
    var note by remember(movementId){mutableStateOf(initial?.note.orEmpty())}
    var recurring by remember(movementId){mutableStateOf(initial?.recurring?:false)}
    var accountId by remember(movementId){mutableLongStateOf(initial?.accountId?:accounts.firstOrNull()?.id?:0L)}
    var destinationId by remember(movementId){mutableLongStateOf(initial?.toAccountId?:accounts.firstOrNull{it.id!=accountId}?.id?:accountId)}
    var budgetId by remember(movementId,pinnedBudget?.id){mutableLongStateOf(initial?.budgetId?:pinnedBudget?.id?:0L)}
    var manuallySelectedBudget by remember(movementId,pinnedBudget?.id){mutableStateOf(initial?.budgetId!=null||pinnedBudget!=null)}
    var rememberBudgetRule by remember(movementId){mutableStateOf(initial?.let{(it.importedFromCsv||it.importKey!=null)&&it.budgetId==null}?:false)}
    var accountMenu by remember{mutableStateOf(false)}; var destinationMenu by remember{mutableStateOf(false)}
    var budgetMenu by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}; var saving by remember{mutableStateOf(false)}
    val dialogMonth=currentMonthStart(initial?.date?:System.currentTimeMillis())
    val availableBudgets=budgets.filter{(budgetVisibleInMonth(it,dialogMonth)&&(it.id to dialogMonth) !in closedKeys)||it.id==initial?.budgetId}
    val selectedBudget=pinnedBudget?:availableBudgets.firstOrNull{it.id==budgetId}
    val movementKinds=if(initial?.kind=="Ajuste") listOf("Despesa","Receita","Transferência","Ajuste") else listOf("Despesa","Receita","Transferência")

    LaunchedEffect(accounts){
        if(accounts.none{it.id==accountId}) accountId=accounts.firstOrNull()?.id?:0L
        if(accounts.none{it.id==destinationId&&it.id!=accountId}) destinationId=accounts.firstOrNull{it.id!=accountId}?.id?:accountId
    }
    LaunchedEffect(kind,budgets,pinnedBudget?.id,title,budgetRules){
        if(kind=="Despesa"){
            if(pinnedBudget!=null){budgetId=pinnedBudget.id;manuallySelectedBudget=true}
            else if(!manuallySelectedBudget){
                val key=normalizeMovementKey(title)
                val suggested=budgetRules.firstOrNull{it.merchantKey==key}?.budgetId
                budgetId=availableBudgets.firstOrNull{it.id==suggested}?.id?:0L
            } else if(availableBudgets.none{it.id==budgetId}) budgetId=0L
        } else budgetId=0L
    }
    AlertDialog(
        onDismissRequest={if(!saving)close()},
        title={Text(if(initial==null)"Novo movimento" else "Editar movimento",color=Ink,fontWeight=FontWeight.Bold)},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(9.dp)){
            if(initial!=null) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){
                    Text(if(active)"Movimento ativo" else "Movimento inativo",color=Ink,fontWeight=FontWeight.SemiBold)
                    Text("Os movimentos inativos não entram nos saldos nem nos orçamentos.",color=Muted,style=MaterialTheme.typography.bodySmall)
                }
                Switch(checked=active,onCheckedChange={active=it})
            }
            if(initial!=null) Column(verticalArrangement=Arrangement.spacedBy(2.dp)){
                OutlinedButton(onClick={split(initial.copy(active=active))},modifier=Modifier.fillMaxWidth()){
                    Icon(Icons.Default.CallSplit,null,tint=Green);Spacer(Modifier.width(6.dp));Text("Dividir em dois",color=Green)
                }
                Text("A divisão usa o montante guardado neste movimento.",color=Muted,style=MaterialTheme.typography.labelSmall)
            }
            if(pinnedBudget==null) Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                movementKinds.forEach{type->FilterChip(selected=kind==type,onClick={kind=type},label={Text(type,style=MaterialTheme.typography.labelSmall)})}
            } else Text("Associado ao orçamento: ${pinnedBudget.name}",color=Green,style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.SemiBold)
            if(kind=="Ajuste") Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                FilterChip(selected=!adjustmentDecreases,onClick={adjustmentDecreases=false},label={Text("Aumentar saldo")})
                FilterChip(selected=adjustmentDecreases,onClick={adjustmentDecreases=true},label={Text("Diminuir saldo")})
            }

            OutlinedTextField(title,{title=it},label={Text("Descrição (opcional)")},singleLine=true)
            OutlinedTextField(amount,{amount=it},label={Text("Montante (€)")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
            OutlinedButton(onClick={showMovementDatePicker=true},modifier=Modifier.fillMaxWidth()){
                Icon(Icons.Default.CalendarMonth,null,tint=Green);Spacer(Modifier.width(8.dp))
                Text("Data do movimento: ${SimpleDateFormat("dd/MM/yyyy",Locale("pt","PT")).format(Date(movementDate))}",color=Green)
            }

            if(kind=="Despesa"){
                if(pinnedBudget!=null) OutlinedTextField(pinnedBudget.name,{},readOnly=true,label={Text("Orçamento")},supportingText={Text("Movimento associado a este orçamento.")},modifier=Modifier.fillMaxWidth())
                else {
                    Column {
                        Text("Associar um orçamento é opcional.",color=Muted,style=MaterialTheme.typography.bodySmall)
                        if(availableBudgets.isEmpty()) TextButton(onClick=createBudget){Text("Criar orçamento mensal",color=Green)}
                    }
                    ExposedDropdownMenuBox(expanded=budgetMenu,onExpandedChange={budgetMenu=!budgetMenu}){
                        OutlinedTextField(selectedBudget?.name?:"Sem orçamento",{},readOnly=true,label={Text("Orçamento (opcional)")},placeholder={Text("Selecionar orçamento")},modifier=Modifier.menuAnchor().fillMaxWidth(),trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded=budgetMenu)})
                        ExposedDropdownMenu(expanded=budgetMenu,onDismissRequest={budgetMenu=false}){
                            DropdownMenuItem(text={Text("Sem orçamento")},onClick={budgetId=0L;manuallySelectedBudget=true;budgetMenu=false})
                            availableBudgets.forEach{item->DropdownMenuItem(text={Text(item.name)},onClick={budgetId=item.id;manuallySelectedBudget=true;budgetMenu=false})}
                        }
                    }
                }
                if(selectedBudget!=null&&normalizeMovementKey(title).isNotBlank()) Row(verticalAlignment=Alignment.CenterVertically){Checkbox(rememberBudgetRule,{rememberBudgetRule=it});Text("Usar este orçamento em movimentos futuros com o mesmo descritivo",color=Muted,style=MaterialTheme.typography.bodySmall)}
            }

            ExposedDropdownMenuBox(expanded=accountMenu,onExpandedChange={accountMenu=!accountMenu}){
                OutlinedTextField(accounts.firstOrNull{it.id==accountId}?.name?:"Escolher conta",{},readOnly=true,label={Text("Conta")},modifier=Modifier.menuAnchor().fillMaxWidth(),trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded=accountMenu)})
                ExposedDropdownMenu(expanded=accountMenu,onDismissRequest={accountMenu=false}){accounts.forEach{item->DropdownMenuItem(text={Text(item.name)},onClick={accountId=item.id;accountMenu=false})}}
            }
            if(kind=="Transferência") ExposedDropdownMenuBox(expanded=destinationMenu,onExpandedChange={destinationMenu=!destinationMenu}){
                OutlinedTextField(accounts.firstOrNull{it.id==destinationId}?.name?:"Criar outra conta primeiro",{},readOnly=true,label={Text("Conta de destino")},modifier=Modifier.menuAnchor().fillMaxWidth(),trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded=destinationMenu)})
                ExposedDropdownMenu(expanded=destinationMenu,onDismissRequest={destinationMenu=false}){accounts.filter{it.id!=accountId}.forEach{item->DropdownMenuItem(text={Text(item.name)},onClick={destinationId=item.id;destinationMenu=false})}}
            }
            OutlinedTextField(note,{note=it},label={Text("Nota (opcional)")},singleLine=true)
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(recurring,{recurring=it});Text("Movimento recorrente",color=Ink)}
            if(error.isNotBlank()) Text(error,color=Coral,style=MaterialTheme.typography.bodySmall)
        }},
        confirmButton={TextButton(enabled=!saving,onClick={
            val parsedAmount=amount.replace(',','.').toDoubleOrNull()
            when {
                parsedAmount==null||!parsedAmount.isFinite()||parsedAmount<0.01 -> error="Indica um montante válido, mínimo de 0,01 €."
                kind=="Transferência"&&accounts.none{it.id==destinationId&&it.id!=accountId} -> error="Cria ou escolhe uma conta de destino diferente."
                else -> {
                    error=""; saving=true
                    val signedAmount=if(kind=="Ajuste"&&adjustmentDecreases)-parsedAmount else parsedAmount
                    val movementCategory=initial?.takeIf{it.kind==kind}?.category?.takeIf{it.isNotBlank()}?:defaultMovementCategory(kind)
                    val value=(initial?:FinanceTransaction(title="",amount=signedAmount,kind=kind,category=movementCategory,accountId=accountId)).copy(
                        title=title,amount=signedAmount,kind=kind,category=movementCategory,accountId=accountId,date=movementDate,active=active,
                        recurring=recurring,note=note,toAccountId=if(kind=="Transferência")destinationId else null,
                        budgetId=if(kind=="Despesa")selectedBudget?.id else null
                    )
                    save(value,rememberBudgetRule){success->saving=false;if(success)close() else error="Não foi possível guardar. Confirma o montante, o orçamento selecionado e as contas."
                    }
                }
            }
        }){Text(if(saving)"A guardar…" else "Guardar",color=Green,fontWeight=FontWeight.Bold)}},
        dismissButton={TextButton(onClick=close,enabled=!saving){Text("Cancelar",color=Muted)}}
    )
    if(showMovementDatePicker) MovementDatePickerDialog(movementDate,{movementDate=it;showMovementDatePicker=false},{showMovementDatePicker=false})
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun MovementDatePickerDialog(initialDate:Long,select:(Long)->Unit,close:()->Unit){
    val state=rememberDatePickerState(initialSelectedDateMillis=localDateToPickerMillis(initialDate))
    DatePickerDialog(
        onDismissRequest=close,
        confirmButton={TextButton(onClick={state.selectedDateMillis?.let{select(pickerDateToLocalStart(it))}},enabled=state.selectedDateMillis!=null){Text("Selecionar",color=Green,fontWeight=FontWeight.Bold)}},
        dismissButton={TextButton(onClick=close){Text("Cancelar",color=Muted)}}
    ){
        DatePicker(state=state,title={Text("Data do movimento",color=Ink,fontWeight=FontWeight.Bold)},showModeToggle=false,modifier=Modifier.fillMaxWidth().height(500.dp).padding(8.dp))
    }
}

private fun defaultMovementCategory(kind:String):String=when(kind){
    "Despesa"->"Outros"
    "Receita"->"Outros rendimentos"
    "Transferência"->"Transferência"
    else->"Acerto de saldo"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SplitBudgetSelector(
    label:String,budgets:List<Budget>,selectedId:Long?,onSelect:(Long?)->Unit
){
    var expanded by remember(label){mutableStateOf(false)}
    val selected=budgets.firstOrNull{it.id==selectedId}
    ExposedDropdownMenuBox(expanded=expanded,onExpandedChange={expanded=!expanded}){
        OutlinedTextField(
            value=selected?.name?:"Sem orçamento",
            onValueChange={},readOnly=true,label={Text(label)},placeholder={Text("Selecionar orçamento")},
            modifier=Modifier.menuAnchor().fillMaxWidth(),trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded=expanded)}
        )
        ExposedDropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){
            DropdownMenuItem(text={Text("Sem orçamento")},onClick={onSelect(null);expanded=false})
            budgets.forEach{budget->DropdownMenuItem(text={Text(budget.name)},onClick={onSelect(budget.id);expanded=false})}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SplitTransactionDialog(
    original:FinanceTransaction,budgets:List<Budget>,closedKeys:Set<Pair<Long,Long>>,
    save:(Double,Double,String,String,Long?,Long?,(Boolean)->Unit)->Unit,close:()->Unit
){
    val total=BigDecimal.valueOf(kotlin.math.abs(original.amount)).setScale(2,RoundingMode.HALF_UP)
    val totalCents=total.movePointRight(2).longValueExact()
    val firstCents=totalCents/2
    val secondCents=totalCents-firstCents
    fun amountInput(cents:Long)=BigDecimal.valueOf(cents,2).toPlainString().replace('.',',')
    fun calculatedRemainder(value:String):String=runCatching{
        val firstCents=BigDecimal(value.replace(',','.')).setScale(2,RoundingMode.HALF_UP).movePointRight(2).longValueExact()
        amountInput(Math.subtractExact(totalCents,firstCents))
    }.getOrDefault("")
    var firstAmount by remember(original.id){mutableStateOf(amountInput(firstCents))}
    var secondAmount by remember(original.id){mutableStateOf(amountInput(secondCents))}
    var firstTitle by remember(original.id){mutableStateOf(original.title)}
    var secondTitle by remember(original.id){mutableStateOf("${original.title.ifBlank{"Movimento"}} (parte 2)")}
    var firstBudgetId by remember(original.id){mutableStateOf(original.budgetId)}
    var secondBudgetId by remember(original.id){mutableStateOf(original.budgetId)}
    var saving by remember(original.id){mutableStateOf(false)}
    var error by remember(original.id){mutableStateOf("")}
    val splitMonth=currentMonthStart(original.date)
    val availableBudgets=budgets.filter{(budgetVisibleInMonth(it,splitMonth)&&(it.id to splitMonth) !in closedKeys)||it.id==original.budgetId}
    AlertDialog(
        onDismissRequest={if(!saving)close()},
        title={Text("Dividir movimento",color=Ink,fontWeight=FontWeight.Bold)},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(9.dp)){
            Text("O total das duas partes tem de ser igual a ${money(total.toDouble())}.",color=Muted)
            OutlinedTextField(firstTitle,{firstTitle=it},label={Text("Descrição da parte 1")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(firstAmount,{value->firstAmount=value;secondAmount=calculatedRemainder(value)},label={Text("Montante da parte 1 (€)")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
            if(original.kind=="Despesa") SplitBudgetSelector("Orçamento da parte 1 (opcional)",availableBudgets,firstBudgetId){firstBudgetId=it}
            OutlinedTextField(secondTitle,{secondTitle=it},label={Text("Descrição da parte 2")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(secondAmount,{secondAmount=it},label={Text("Montante da parte 2 (€)")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
            if(original.kind=="Despesa") SplitBudgetSelector("Orçamento da parte 2 (opcional)",availableBudgets,secondBudgetId){secondBudgetId=it}
            if(error.isNotBlank()) Text(error,color=Coral,style=MaterialTheme.typography.bodySmall)
        }},
        confirmButton={TextButton(enabled=!saving&&totalCents>=2,onClick={
            fun parse(value:String)=value.replace(',','.').toBigDecimalOrNull()?.setScale(2,RoundingMode.HALF_UP)
            val first=parse(firstAmount);val second=parse(secondAmount)
            when{
                first==null||second==null||first< BigDecimal("0.01")||second<BigDecimal("0.01")->error="Indica dois montantes válidos, cada um de pelo menos 0,01 €."
                first.add(second).compareTo(total)!=0->error="A soma das partes tem de ser igual a ${money(total.toDouble())}."
                else->{error="";saving=true;save(first.toDouble(),second.toDouble(),firstTitle,secondTitle,firstBudgetId,secondBudgetId){success->saving=false;if(success)close() else error="Não foi possível dividir o movimento. Confirma os valores e os orçamentos selecionados."}}
            }
        }){Text(if(saving)"A dividir…" else "Dividir",color=Green,fontWeight=FontWeight.Bold)}},
        dismissButton={TextButton(onClick=close,enabled=!saving){Text("Cancelar",color=Muted)} }
    )
}

private data class BudgetLimitTarget(val budgetId:Long,val monthStart:Long,val currentAmount:Double)

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AccountDialog(initial:Account?,currentBalance:Double,save:(Account)->Unit,close:()->Unit,adjust:(Account)->Unit){
    val accountId=initial?.id?:0L
    var name by remember(accountId){mutableStateOf(initial?.name.orEmpty())}
    var type by remember(accountId){mutableStateOf(initial?.type?:"Carteira")}
    var openingBalance by remember(accountId){mutableStateOf(initial?.openingBalance?.toString()?:"0")}
    var typeMenu by remember{mutableStateOf(false)}; var error by remember{mutableStateOf("")}
    val types=listOf("Carteira","Conta bancária","Cartão de crédito","Poupança")
    AlertDialog(onDismissRequest=close,title={Text(if(initial==null)"Nova conta" else "Editar conta",color=Ink,fontWeight=FontWeight.Bold)},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            if(initial!=null) Text("Saldo atual: ${money(currentBalance)}",color=Green,fontWeight=FontWeight.SemiBold)
            OutlinedTextField(name,{name=it},label={Text("Nome")},singleLine=true)
            ExposedDropdownMenuBox(expanded=typeMenu,onExpandedChange={typeMenu=!typeMenu}){
                OutlinedTextField(type,{},readOnly=true,label={Text("Tipo")},modifier=Modifier.menuAnchor().fillMaxWidth(),trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded=typeMenu)})
                ExposedDropdownMenu(expanded=typeMenu,onDismissRequest={typeMenu=false}){types.forEach{option->DropdownMenuItem(text={Text(option)},onClick={type=option;typeMenu=false})}}
            }
            OutlinedTextField(openingBalance,{openingBalance=it},label={Text("Saldo inicial (€)")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),supportingText={Text("Acerta o saldo de partida sem criar um movimento.")})
            initial?.let{account->OutlinedButton(onClick={adjust(account)},modifier=Modifier.fillMaxWidth()) { Text("Acertar saldo através de movimento",color=Green) }}
            if(error.isNotBlank()) Text(error,color=Coral,style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={TextButton(onClick={
        val parsed=openingBalance.replace(',','.').toDoubleOrNull()
        when{ name.isBlank()->error="Indica um nome para a conta.";parsed==null||!parsed.isFinite()->error="Indica um saldo inicial válido.";else->{save(Account(id=accountId,name=name.trim(),type=type,openingBalance=parsed,currency=initial?.currency?:"EUR"));error=""} }
    }){Text("Guardar",color=Green,fontWeight=FontWeight.Bold)}},dismissButton={TextButton(onClick=close){Text("Cancelar",color=Muted)}})
}

@Composable private fun AdjustmentDialog(account:Account,currentBalance:Double,save:(Double,Boolean,String,(Boolean)->Unit)->Unit,close:()->Unit){
    var amount by remember(account.id){mutableStateOf("")}; var decreases by remember(account.id){mutableStateOf(false)}
    var note by remember(account.id){mutableStateOf("")}; var error by remember{mutableStateOf("")}; var saving by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest={if(!saving)close()},title={Text("Acerto de saldo",color=Ink,fontWeight=FontWeight.Bold)},text={
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text(account.name,color=Muted)
            Text("Saldo atual: ${money(currentBalance)}",color=Green,fontWeight=FontWeight.SemiBold)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                FilterChip(selected=!decreases,onClick={decreases=false},label={Text("Aumentar")})
                FilterChip(selected=decreases,onClick={decreases=true},label={Text("Diminuir")})
            }
            OutlinedTextField(amount,{amount=it},label={Text("Valor do acerto (€)")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
            OutlinedTextField(note,{note=it},label={Text("Nota (opcional)")},singleLine=true)
            if(error.isNotBlank()) Text(error,color=Coral,style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={TextButton(enabled=!saving,onClick={
        val parsed=amount.replace(',','.').toDoubleOrNull()
        if(parsed==null||!parsed.isFinite()||parsed<0.01) error="Indica um valor válido, mínimo de 0,01 €."
        else {error="";saving=true;save(parsed,decreases,note){success->saving=false;if(success)close() else error="Não foi possível guardar o movimento de acerto."}}
    }){Text(if(saving)"A guardar…" else "Guardar movimento",color=Green,fontWeight=FontWeight.Bold)}},dismissButton={TextButton(onClick=close,enabled=!saving){Text("Cancelar",color=Muted)}})
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CsvImportDialog(
    csv:ParsedBankCsv,accounts:List<Account>,selectedAccountId:Long,importing:Boolean,
    onAccountChange:(Long)->Unit,import:()->Unit,close:()->Unit
){
    var accountMenu by remember{mutableStateOf(false)}
    val dates=csv.movements.map{it.date}
    val dateFormatter=remember{SimpleDateFormat("dd/MM/yyyy",Locale("pt","PT"))}
    val dateRange=if(dates.isEmpty())"" else "${dateFormatter.format(Date(dates.minOrNull()!!))}–${dateFormatter.format(Date(dates.maxOrNull()!!))}"
    AlertDialog(
        onDismissRequest={if(!importing)close()},
        title={Text("Importar movimentos",color=Ink,fontWeight=FontWeight.Bold)},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(9.dp)){
            Text("${csv.movements.size} movimentos encontrados",color=Ink,fontWeight=FontWeight.SemiBold)
            Text("${csv.expenses} débitos · ${csv.income} créditos · $dateRange",color=Muted,style=MaterialTheme.typography.bodySmall)
            ExposedDropdownMenuBox(expanded=accountMenu,onExpandedChange={accountMenu=!accountMenu}){
                OutlinedTextField(accounts.firstOrNull{it.id==selectedAccountId}?.name?:"Selecionar conta",{},readOnly=true,label={Text("Conta de destino")},modifier=Modifier.menuAnchor().fillMaxWidth(),trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded=accountMenu)})
                ExposedDropdownMenu(expanded=accountMenu,onDismissRequest={accountMenu=false}){accounts.forEach{account->DropdownMenuItem(text={Text(account.name)},onClick={onAccountChange(account.id);accountMenu=false})}}
            }
            Text("Os débitos entram como despesas e os créditos como receitas. Sem regra conhecida, as despesas ficam sem orçamento para as poderes atribuir depois.",color=Muted,style=MaterialTheme.typography.bodySmall)
            if(csv.skippedRows>0) Text("${csv.skippedRows} linhas inválidas serão ignoradas.",color=Coral,style=MaterialTheme.typography.bodySmall)
            Text("Se importares este ficheiro outra vez, os movimentos duplicados são ignorados.",color=Muted,style=MaterialTheme.typography.bodySmall)
        }},
        confirmButton={TextButton(onClick=import,enabled=!importing&&accounts.any{it.id==selectedAccountId}){Text(if(importing)"A importar…" else "Importar ${csv.movements.size}",color=Green,fontWeight=FontWeight.Bold)}},
        dismissButton={TextButton(onClick=close,enabled=!importing){Text("Cancelar",color=Muted)}}
    )
}

@Composable private fun BudgetDialog(initial:Budget?,initialLimit:Double,save:(String,Double)->Unit,close:()->Unit){
    val budgetId=initial?.id?:0L
    var name by remember(budgetId){mutableStateOf(initial?.name.orEmpty())}
    var limit by remember(budgetId){mutableStateOf(initialLimit.toString())}
    var error by remember(budgetId){mutableStateOf("")}
    AlertDialog(
        onDismissRequest=close,
        title={Text(if(initial==null)"Novo orçamento mensal" else "Editar nome do orçamento",color=Ink,fontWeight=FontWeight.Bold)},
        text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
            OutlinedTextField(name,{name=it},label={Text("Nome do orçamento")},singleLine=true,modifier=Modifier.fillMaxWidth())
            if(initial==null) OutlinedTextField(limit,{limit=it},label={Text("Limite mensal (€)")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
            if(error.isNotBlank()) Text(error,color=Coral,style=MaterialTheme.typography.bodySmall)
        }},
        confirmButton={TextButton(onClick={
            val parsed=if(initial==null)limit.replace(',','.').toDoubleOrNull() else initialLimit
            when{
                name.isBlank()->error="Indica o nome do orçamento."
                parsed==null||!parsed.isFinite()||parsed<0.01->error="Indica um limite mensal válido, mínimo de 0,01 €."
                else->{error="";save(name.trim(),parsed)}
            }
        }){Text("Guardar",color=Green,fontWeight=FontWeight.Bold)}},
        dismissButton={TextButton(onClick=close){Text("Cancelar",color=Muted)}}
    )
}

@Composable private fun BudgetLimitDialog(target:BudgetLimitTarget,save:(Double)->Unit,close:()->Unit){
    var amount by remember(target.budgetId,target.monthStart){mutableStateOf(target.currentAmount.toString())}
    var error by remember{mutableStateOf("")}
    AlertDialog(onDismissRequest=close,title={Text("Limite de ${monthLabel(target.monthStart)}",color=Ink,fontWeight=FontWeight.Bold)},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("O valor fica guardado para este mês e mantém o histórico dos meses anteriores.",color=Muted)
            OutlinedTextField(amount,{amount=it},label={Text("Limite (€)")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
            if(error.isNotBlank()) Text(error,color=Coral,style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={TextButton(onClick={
        val parsed=amount.replace(',','.').toDoubleOrNull()
        if(parsed==null||!parsed.isFinite()||parsed<0.01) error="Indica um limite válido, mínimo de 0,01 €."
        else {save(parsed);error=""}
    }){Text("Guardar limite",color=Green,fontWeight=FontWeight.Bold)}},dismissButton={TextButton(onClick=close){Text("Cancelar",color=Muted)}})
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SimpleDialog(title:String,labels:List<String>,defaults:List<String>,options:List<String>,save:(List<String>)->Unit,close:()->Unit,selectionFieldIndex:Int?=if(options.isNotEmpty())1 else null){
    val values=remember(title){mutableStateListOf<String>().also{list->labels.indices.forEach{i->list.add(defaults.getOrElse(i){""})}}}
    var expanded by remember{mutableStateOf(false)}; var error by remember{mutableStateOf("")}
    LaunchedEffect(options,selectionFieldIndex){
        val index=selectionFieldIndex
        if(index!=null&&index in values.indices&&options.isNotEmpty()&&values[index] !in options) values[index]=options.first()
    }
    AlertDialog(onDismissRequest=close,title={Text(title,color=Ink,fontWeight=FontWeight.Bold)},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(9.dp)){
            labels.forEachIndexed{i,label->
                if(i==selectionFieldIndex){
                    ExposedDropdownMenuBox(expanded=expanded,onExpandedChange={expanded=options.isNotEmpty()&&!expanded}){
                        OutlinedTextField(values[i],{},readOnly=true,label={Text(label)},modifier=Modifier.menuAnchor().fillMaxWidth(),trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded=expanded)},supportingText={if(options.isEmpty())Text("A carregar opções…")})
                        ExposedDropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){options.forEach{option->DropdownMenuItem(text={Text(option)},onClick={values[i]=option;expanded=false})}}
                    }
                } else OutlinedTextField(values[i],{values[i]=it},label={Text(label)},singleLine=true,keyboardOptions=if(label.contains("€"))KeyboardOptions(keyboardType=KeyboardType.Decimal)else KeyboardOptions.Default)
            }
            if(error.isNotBlank()) Text(error,color=Coral,style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={TextButton(onClick={
        val amountIndex=labels.indexOfFirst{it.contains("€")}; val parsed=if(amountIndex>=0)values[amountIndex].replace(',','.').toDoubleOrNull() else null
        when{values.any{it.isBlank()}->error="Preenche todos os campos.";selectionFieldIndex!=null&&options.isEmpty()->error="Não existem opções disponíveis.";amountIndex>=0&&(parsed==null||!parsed.isFinite()||parsed<0.01)->error="Indica um montante válido, mínimo de 0,01 €.";else->{error="";save(values.toList())}}
    }){Text("Guardar",color=Green,fontWeight=FontWeight.Bold)}},dismissButton={TextButton(onClick=close){Text("Cancelar",color=Muted)}})
}

private fun currentMonthStart(timestamp:Long=System.currentTimeMillis()):Long = Calendar.getInstance().apply {
    timeInMillis=timestamp
    set(Calendar.DAY_OF_MONTH,1)
    set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)
}.timeInMillis
private fun monthEndDay(monthStart:Long):Long = Calendar.getInstance().apply {
    timeInMillis=monthStart
    add(Calendar.MONTH,1)
    add(Calendar.DAY_OF_MONTH,-1)
    set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)
}.timeInMillis
private fun endOfDay(timestamp:Long):Long = Calendar.getInstance().apply{
    timeInMillis=timestamp
    set(Calendar.HOUR_OF_DAY,23);set(Calendar.MINUTE,59);set(Calendar.SECOND,59);set(Calendar.MILLISECOND,999)
}.timeInMillis
private fun localDateToPickerMillis(timestamp:Long):Long {
    val local=Calendar.getInstance().apply{timeInMillis=timestamp}
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply{
        clear()
        set(local.get(Calendar.YEAR),local.get(Calendar.MONTH),local.get(Calendar.DAY_OF_MONTH),0,0,0)
        set(Calendar.MILLISECOND,0)
    }.timeInMillis
}
private fun pickerDateToLocalStart(timestamp:Long):Long {
    val utc=Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply{timeInMillis=timestamp}
    return Calendar.getInstance().apply{
        clear()
        set(utc.get(Calendar.YEAR),utc.get(Calendar.MONTH),utc.get(Calendar.DAY_OF_MONTH),0,0,0)
        set(Calendar.MILLISECOND,0)
    }.timeInMillis
}
private fun shiftMonth(monthStart:Long,offset:Int):Long = Calendar.getInstance().apply {
    timeInMillis=monthStart
    add(Calendar.MONTH,offset)
    set(Calendar.DAY_OF_MONTH,1)
    set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)
}.timeInMillis
private fun monthLabel(monthStart:Long):String = SimpleDateFormat("MMMM yyyy",Locale("pt","PT")).format(Date(monthStart)).replaceFirstChar { it.titlecase(Locale("pt","PT")) }

private fun accountBalance(a:Account,tx:List<FinanceTransaction>):Double {
    val amounts = mutableListOf(a.openingBalance)
    tx.filter{it.active}.forEach { t -> when { t.kind=="Ajuste" && t.accountId==a.id -> amounts.add(t.amount); t.kind=="Receita" && t.accountId==a.id -> amounts.add(t.amount); t.kind=="Despesa" && t.accountId==a.id -> amounts.add(-t.amount); t.kind=="Transferência" && t.accountId==a.id -> amounts.add(-t.amount); t.kind=="Transferência" && t.toAccountId==a.id -> amounts.add(t.amount); else -> Unit } }
    return moneySum(amounts)
}
private fun moneySum(values:Iterable<Double>):Double = values.filter{it.isFinite()}.fold(BigDecimal.ZERO){sum,value->sum.add(BigDecimal.valueOf(value))}.setScale(2,RoundingMode.HALF_UP).toDouble()
private fun money(value:Double):String = NumberFormat.getCurrencyInstance(Locale("pt","PT")).format(value)
