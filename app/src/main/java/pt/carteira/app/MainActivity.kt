package pt.carteira.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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
    fun saveTransaction(value: FinanceTransaction, onComplete:(Boolean)->Unit = {}) = viewModelScope.launch {
        val saved = try {
            val source = dao.accountById(value.accountId) ?: ensureDefaultAccount()
            val budget = value.budgetId?.let { dao.budgetById(it) }
            val validBudget = when (value.kind) {
                "Despesa" -> budget != null
                else -> value.budgetId == null
            }
            val validDestination = value.kind != "Transferência" ||
                (value.toAccountId != null && value.toAccountId != source.id && dao.accountById(value.toAccountId) != null)
            val validAmount=value.amount.isFinite() && (if(value.kind=="Ajuste") kotlin.math.abs(value.amount)>=0.01 else value.amount>=0.01)
            if (value.kind !in listOf("Despesa", "Receita", "Transferência", "Ajuste") ||
                !validAmount ||
                value.category.isBlank() || !dao.categoryExists(value.category, value.kind) ||
                !validBudget || !validDestination) false
            else {
                val normalized = value.copy(
                    title = value.title.trim(),
                    amount = BigDecimal.valueOf(value.amount).setScale(2, RoundingMode.HALF_UP).toDouble(),
                    accountId = source.id,
                    note = value.note.trim()
                )
                if (normalized.id == 0L) dao.addTransaction(normalized) else dao.updateTransaction(normalized)
                true
            }
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
        onComplete(saved)
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
    fun archiveBudget(budgetId:Long) = viewModelScope.launch {
        dao.budgetById(budgetId)?.let { budget ->
            if(budget.archivedFromMonth==null) dao.updateBudget(budget.copy(archivedFromMonth=currentMonthStart()))
        }
    }
    fun setBudgetLimit(budgetId:Long,monthStart:Long,amount:Double) = viewModelScope.launch {
        if(dao.budgetById(budgetId)!=null && amount.isFinite() && amount>=0.01) {
            dao.saveBudgetPeriod(BudgetPeriod(budgetId,monthStart,BigDecimal.valueOf(amount).setScale(2,RoundingMode.HALF_UP).toDouble()))
        }
    }
    fun deleteTransaction(value:FinanceTransaction) = viewModelScope.launch { dao.deleteTransaction(value) }
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
    val accounts by model.accounts.collectAsState(); val transactions by model.transactions.collectAsState(); val budgets by model.budgets.collectAsState(); val categories by model.categories.collectAsState(); val budgetPeriods by model.budgetPeriods.collectAsState()
    var section by remember { mutableStateOf("Início") }; var dialog by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<FinanceTransaction?>(null) }
    var pendingEdit by remember { mutableStateOf<FinanceTransaction?>(null) }
    var pendingAccountEdit by remember { mutableStateOf<Account?>(null) }
    var adjustmentAccount by remember { mutableStateOf<Account?>(null) }
    var pendingBudgetLimit by remember { mutableStateOf<BudgetLimitTarget?>(null) }
    var pendingBudgetEdit by remember { mutableStateOf<Budget?>(null) }
    var pendingBudgetArchive by remember { mutableStateOf<Budget?>(null) }
    var activeBudgetId by remember { mutableStateOf<Long?>(null) }
    var movementBudgetId by remember { mutableStateOf<Long?>(null) }
    var selectedBudgetMonth by remember { mutableLongStateOf(currentMonthStart()) }
    var budgetSort by remember { mutableStateOf("Maior gasto") }
    var showBudgetRemaining by remember { mutableStateOf(false) }
    fun addMovement(budgetId: Long? = null) { pendingEdit = null; movementBudgetId = budgetId; dialog = "movimento" }
    fun editMovement(value: FinanceTransaction) { pendingEdit = value; movementBudgetId = null; dialog = "movimento" }
    val activeBudget = budgets.firstOrNull { it.id == activeBudgetId }
    val canAddBudgetMovement = activeBudget != null && selectedBudgetMonth == currentMonthStart() && activeBudget.archivedFromMonth == null
    val pageTitle = if (section == "DetalheOrçamento") activeBudget?.name ?: "Orçamento" else section
    val total = accounts.sumOf { accountBalance(it, transactions) }
    Scaffold(containerColor=Paper, topBar={
        Row(Modifier.fillMaxWidth().background(Paper).statusBarsPadding().padding(horizontal=22.dp, vertical=18.dp), verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("CARTEIRA", style=MaterialTheme.typography.labelMedium, color=Green, fontWeight=FontWeight.Bold); Text(pageTitle, style=MaterialTheme.typography.headlineSmall, color=Ink, fontWeight=FontWeight.Bold) }
            if(section!="DetalheOrçamento"||canAddBudgetMovement) IconButton(onClick={addMovement(if(section=="DetalheOrçamento")activeBudgetId else null)}) { Icon(Icons.Default.Add, "Novo movimento", tint=Green) }
        }
    }, bottomBar={ NavigationBar(containerColor=Color.White) {
        listOf("Início" to Icons.Default.Home, "Movimentos" to Icons.Default.ReceiptLong, "Orçamentos" to Icons.Default.PieChart, "Contas" to Icons.Default.AccountBalanceWallet, "Relatórios" to Icons.Default.BarChart).forEach { (label, icon) ->
            NavigationBarItem(selected=section==label || (section=="DetalheOrçamento" && label=="Orçamentos"), onClick={section=label}, icon={Icon(icon,label)}, label={Text(label, style=MaterialTheme.typography.labelSmall.copy(fontSize=9.sp,lineHeight=10.sp),maxLines=1,softWrap=false,textAlign=TextAlign.Center)}, colors=NavigationBarItemDefaults.colors(selectedIconColor=Green,selectedTextColor=Green,indicatorColor=Mint))
        }
    } }, floatingActionButton={ if(section in listOf("Movimentos","Orçamentos","Contas")||(section=="DetalheOrçamento"&&canAddBudgetMovement)) FloatingActionButton(onClick={when(section){"Orçamentos"->{pendingEdit=null;dialog="orçamento"};"Contas"->dialog="conta";"DetalheOrçamento"->addMovement(activeBudgetId);else->addMovement()}},containerColor=Green,contentColor=Color.White){Icon(Icons.Default.Add,"Adicionar")} }) { pad ->
        when(section) {
            "Início" -> HomePage(Modifier.padding(pad), total, transactions, accounts, budgets, budgetPeriods, {section=it}, {pendingDelete=it}, {editMovement(it)}, {addMovement()}, {budget->activeBudgetId=budget.id;selectedBudgetMonth=currentMonthStart();section="DetalheOrçamento"})
            "Movimentos" -> TransactionsPage(Modifier.padding(pad), transactions, accounts, {pendingDelete=it}, {editMovement(it)}, {addMovement()})
            "Contas" -> AccountsPage(Modifier.padding(pad), accounts, transactions, {pendingAccountEdit=null;dialog="conta"}, {pendingAccountEdit=it;dialog="editar-conta"})
            "Orçamentos" -> BudgetsPage(Modifier.padding(pad), budgets, transactions, budgetPeriods, selectedBudgetMonth, {selectedBudgetMonth=it}, budgetSort, {budgetSort=it}, showBudgetRemaining, {showBudgetRemaining=it}, {pendingBudgetLimit=null;pendingBudgetEdit=null;dialog="orçamento"}, {budget->addMovement(budget.id)}, {budget->activeBudgetId=budget.id;section="DetalheOrçamento"})
            "DetalheOrçamento" -> BudgetDetailPage(Modifier.padding(pad), activeBudget, transactions, accounts, budgetPeriods, selectedBudgetMonth, {selectedBudgetMonth=it}, {addMovement(activeBudget?.id)}, {pendingDelete=it}, {editMovement(it)}, {budget,monthStart,amount->pendingBudgetLimit=BudgetLimitTarget(budget.id,monthStart,amount);dialog="budget-limit"}, {budget->pendingBudgetEdit=budget;dialog="editar-orçamento"}, {budget->pendingBudgetArchive=budget})
            else -> ReportsPage(Modifier.padding(pad), transactions)
        }
    }
    if(dialog.isNotEmpty()) when(dialog) {
        "movimento" -> TransactionDialog(accounts, budgets, categories, budgets.firstOrNull{it.id==movementBudgetId}, pendingEdit, { value, done -> model.saveTransaction(value, done) }, { pendingEdit=null;dialog="";movementBudgetId=null }, { pendingEdit=null;movementBudgetId=null;dialog="orçamento" })
        "conta" -> AccountDialog(null,0.0, {value->model.saveAccount(value);dialog=""}, {dialog=""}, {})
        "editar-conta" -> AccountDialog(pendingAccountEdit, pendingAccountEdit?.let{accountBalance(it,transactions)}?:0.0, {value->model.saveAccount(value);pendingAccountEdit=null;dialog=""}, {pendingAccountEdit=null;dialog=""}, {account->adjustmentAccount=account;pendingAccountEdit=null;dialog="acerto-conta"})
        "orçamento" -> BudgetDialog(null,500.0,{name,limit->model.addBudget(name,limit);dialog=""},{dialog=""})
        "editar-orçamento" -> pendingBudgetEdit?.let{budget->BudgetDialog(budget,budgetLimitFor(budget,budgetPeriods,selectedBudgetMonth),{name,_->model.updateBudgetName(budget.copy(name=name));pendingBudgetEdit=null;dialog=""},{pendingBudgetEdit=null;dialog=""})}
        "acerto-conta" -> adjustmentAccount?.let{account->AdjustmentDialog(account,accountBalance(account,transactions),{amount,decrease,note,done->model.saveTransaction(FinanceTransaction(title="Acerto de saldo",amount=if(decrease)-amount else amount,kind="Ajuste",category="Acerto de saldo",accountId=account.id,note=note),done)},{adjustmentAccount=null;dialog=""})}
        "budget-limit" -> pendingBudgetLimit?.let{target->BudgetLimitDialog(target,{amount->model.setBudgetLimit(target.budgetId,target.monthStart,amount);pendingBudgetLimit=null;dialog=""},{pendingBudgetLimit=null;dialog=""})}
    }
    pendingDelete?.let { transaction ->
        AlertDialog(
            onDismissRequest={pendingDelete=null},
            title={Text("Eliminar movimento?",color=Ink,fontWeight=FontWeight.Bold)},
            text={Text("O movimento ‘${transaction.title.ifBlank{transaction.category}}’ será removido da carteira.",color=Muted)},
            confirmButton={TextButton(onClick={model.deleteTransaction(transaction);pendingDelete=null}){Text("Eliminar",color=Coral)}},
            dismissButton={TextButton(onClick={pendingDelete=null}){Text("Cancelar",color=Green)}}
        )
    }
    pendingBudgetArchive?.let { budget ->
        AlertDialog(
            onDismissRequest={pendingBudgetArchive=null},
            title={Text("Arquivar orçamento?",color=Ink,fontWeight=FontWeight.Bold)},
            text={Text("${budget.name} deixa de aparecer a partir deste mês, mas continua disponível nos meses anteriores.",color=Muted)},
            confirmButton={TextButton(onClick={model.archiveBudget(budget.id);pendingBudgetArchive=null;selectedBudgetMonth=shiftMonth(currentMonthStart(),-1)}){Text("Arquivar",color=Coral)}},
            dismissButton={TextButton(onClick={pendingBudgetArchive=null}){Text("Cancelar",color=Green)}}
        )
    }
}

@Composable private fun HomePage(modifier:Modifier,total:Double,tx:List<FinanceTransaction>,accounts:List<Account>,budgets:List<Budget>,budgetPeriods:List<BudgetPeriod>,navigate:(String)->Unit,requestDelete:(FinanceTransaction)->Unit,requestEdit:(FinanceTransaction)->Unit,addMovement:()->Unit,openBudget:(Budget)->Unit) {
    val cal=Calendar.getInstance(); val month=cal.get(Calendar.MONTH); val year=cal.get(Calendar.YEAR)
    val monthly=tx.filter { val c=Calendar.getInstance().apply{timeInMillis=it.date}; c.get(Calendar.MONTH)==month&&c.get(Calendar.YEAR)==year }
    val spent=moneySum(monthly.filter{it.kind=="Despesa"}.map{it.amount}); val income=moneySum(monthly.filter{it.kind=="Receita"}.map{it.amount})
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
        else items(tx.take(5)) { TransactionRow(it,accounts,requestDelete,requestEdit) }
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

@Composable private fun TransactionsPage(modifier:Modifier,tx:List<FinanceTransaction>,accounts:List<Account>,requestDelete:(FinanceTransaction)->Unit,requestEdit:(FinanceTransaction)->Unit,addMovement:()->Unit){
    var filter by remember{mutableStateOf("Todos")}; val filtered=tx.filter{filter=="Todos"||it.kind==filter}
    Column(modifier.fillMaxSize().padding(horizontal=20.dp)){
        Text("Toca num movimento para editar; mantém premido para apagar.",color=Muted,style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(bottom=8.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.horizontalScroll(rememberScrollState()).padding(bottom=12.dp)){
            listOf("Todos","Despesa","Receita","Transferência","Ajuste").forEach{AssistChip(onClick={filter=it},label={Text(it)},colors=AssistChipDefaults.assistChipColors(containerColor=if(filter==it)Mint else Color.White))}
        }
        if(filtered.isEmpty()) EmptyCard(if(tx.isEmpty())"Sem movimentos" else "Sem resultados",if(tx.isEmpty())"Adiciona receitas, despesas ou transferências." else "Não há movimentos deste tipo.","Novo movimento",addMovement)
        else LazyColumn(verticalArrangement=Arrangement.spacedBy(9.dp)){items(filtered){TransactionRow(it,accounts,requestDelete,requestEdit)}}
    }
}
@Composable private fun AccountsPage(modifier:Modifier,accounts:List<Account>,tx:List<FinanceTransaction>,add:()->Unit,edit:(Account)->Unit){Column(modifier.fillMaxSize().padding(20.dp)){Row(verticalAlignment=Alignment.CenterVertically){Text("Contas e carteiras",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,color=Ink);TextButton(onClick=add){Text("+ Adicionar")}};Spacer(Modifier.height(8.dp));accounts.forEach{account->AccountDetail(account,tx){edit(account)};Spacer(Modifier.height(10.dp))}}}
@Composable private fun AccountDetail(a:Account,tx:List<FinanceTransaction>,edit:()->Unit){val bal=accountBalance(a,tx);Row(Modifier.fillMaxWidth().background(Color.White,RoundedCornerShape(18.dp)).padding(start=18.dp,top=12.dp,bottom=12.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.AccountBalanceWallet,null,tint=Green,modifier=Modifier.size(40.dp));Column(Modifier.weight(1f).padding(start=14.dp)){Text(a.name,color=Ink,fontWeight=FontWeight.Bold);Text(a.type,color=Muted,style=MaterialTheme.typography.bodySmall)};Text(money(bal),color=Ink,fontWeight=FontWeight.Bold);IconButton(onClick=edit){Icon(Icons.Default.Edit,"Editar conta",tint=Green)}} }

@Composable private fun BudgetsPage(
    modifier:Modifier,budgets:List<Budget>,tx:List<FinanceTransaction>,budgetPeriods:List<BudgetPeriod>,
    selectedMonth:Long,onMonthChange:(Long)->Unit,sort:String,onSortChange:(String)->Unit,
    showRemaining:Boolean,onShowRemainingChange:(Boolean)->Unit,add:()->Unit,quickAdd:(Budget)->Unit,openBudget:(Budget)->Unit
){
    val visible=budgets.filter{budgetVisibleInMonth(it,selectedMonth)}
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
    val totalLimit=moneySum(visible.map{budgetLimitFor(it,budgetPeriods,selectedMonth)})
    val totalSpent=moneySum(visible.map{budgetSpent(it,tx,selectedMonth)})
    val totalRemaining=moneySum(listOf(totalLimit,-totalSpent))
    var sortExpanded by remember{mutableStateOf(false)}
    LazyColumn(modifier.fillMaxSize(),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=8.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item {
            Row(verticalAlignment=Alignment.CenterVertically){
                Text("Orçamentos mensais",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,color=Ink)
                Box {
                    TextButton(onClick={sortExpanded=true}){Icon(Icons.Default.Sort,null,tint=Green);Spacer(Modifier.width(4.dp));Text("Ordenar",color=Green)}
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
        if(sorted.isEmpty()) item { EmptyCard("Sem orçamentos neste mês","Cria um limite mensal para acompanhar as tuas despesas.","Criar orçamento",add) }
        else items(sorted,key={it.id}){budget->BudgetRow(budget,tx,budgetPeriods,selectedMonth,showRemaining,if(selectedMonth==currentMonthStart()){{quickAdd(budget)}}else null){openBudget(budget)}}
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

@Composable private fun BudgetRow(b:Budget,tx:List<FinanceTransaction>,periods:List<BudgetPeriod>,monthStart:Long,showRemaining:Boolean=false,onQuickAdd:(()->Unit)?=null,onClick:()->Unit){
    val limit=budgetLimitFor(b,periods,monthStart).coerceAtLeast(0.01); val spent=budgetSpent(b,tx,monthStart); val ratio=spent/limit; val progress=ratio.toFloat().coerceIn(0f,1f)
    Column(Modifier.fillMaxWidth().clickable(onClick=onClick).background(Color.White,RoundedCornerShape(17.dp)).padding(horizontal=13.dp,vertical=10.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){
            Text(b.name,color=Ink,fontWeight=FontWeight.SemiBold,maxLines=1,modifier=Modifier.weight(1f))
            Text(if(showRemaining)money(limit-spent) else "${(ratio*100).toInt()}% de ${money(limit)}",color=if(ratio>1)Coral else Ink,fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.bodySmall,maxLines=1,softWrap=false,textAlign=TextAlign.End)
            if(onQuickAdd!=null) IconButton(onClick=onQuickAdd,modifier=Modifier.size(36.dp)){Icon(Icons.Default.Add,"Adicionar movimento a ${b.name}",tint=Green,modifier=Modifier.size(22.dp))}
        }
        Spacer(Modifier.height(6.dp));LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(5.dp)),color=if(ratio>1)Coral else Green,trackColor=Mint,strokeCap= androidx.compose.ui.graphics.StrokeCap.Round)
        Text(if(showRemaining)"Gasto ${money(spent)}" else if(spent>limit)"Ultrapassado ${money(spent-limit)}" else "Disponível ${money(limit-spent)}",color=if(ratio>1)Coral else Muted,style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(top=4.dp))
    }
}

@Composable private fun BudgetDetailPage(
    modifier:Modifier,budget:Budget?,tx:List<FinanceTransaction>,accounts:List<Account>,periods:List<BudgetPeriod>,
    selectedMonth:Long,onMonthChange:(Long)->Unit,add:()->Unit,requestDelete:(FinanceTransaction)->Unit,
    requestEdit:(FinanceTransaction)->Unit,editLimit:(Budget,Long,Double)->Unit,
    editName:(Budget)->Unit,archive:(Budget)->Unit
){
    if(budget==null){EmptyCard("Orçamento indisponível","Este orçamento já não existe.","Voltar aos orçamentos",{}) ; return}
    val records=budgetTransactions(budget,tx,selectedMonth)
    val archivedInSelectedMonth=budget.archivedFromMonth?.let{selectedMonth>=it}?:false
    val canAdd=selectedMonth==currentMonthStart()&&!archivedInSelectedMonth
    Column(modifier.fillMaxSize().padding(horizontal=16.dp)){
        MonthNavigator(selectedMonth,onMonthChange)
        Row(Modifier.fillMaxWidth().padding(top=4.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically){
            Text("${records.size} registos",Modifier.weight(1f),color=Muted,style=MaterialTheme.typography.labelMedium)
            TextButton(onClick={editName(budget)}){Icon(Icons.Default.Edit,null);Spacer(Modifier.width(4.dp));Text("Editar nome")}
            if(budget.archivedFromMonth==null) TextButton(onClick={archive(budget)}){Icon(Icons.Default.Archive,null);Spacer(Modifier.width(4.dp));Text("Arquivar",color=Coral)}
        }
        BudgetRow(budget,tx,periods,selectedMonth){ }
        TextButton(onClick={editLimit(budget,selectedMonth,budgetLimitFor(budget,periods,selectedMonth))},modifier=Modifier.align(Alignment.End)){Text("Alterar limite deste mês",color=Green)}
        if(archivedInSelectedMonth) Text("Orçamento arquivado a partir deste mês. Os registos anteriores continuam disponíveis.",color=Muted,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(vertical=4.dp))
        Text("Toca num movimento para editar; mantém premido para apagar.",color=Muted,style=MaterialTheme.typography.labelSmall)
        Row(Modifier.fillMaxWidth().padding(top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically){Text("Registos do mês",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,color=Ink,fontWeight=FontWeight.Bold);Text(records.size.toString(),color=Muted)}
        if(records.isEmpty()) {
            if(canAdd) EmptyCard("Ainda sem registos","As despesas associadas a este orçamento aparecem aqui.","Adicionar movimento",add)
            else Text("Sem registos neste mês.",color=Muted,modifier=Modifier.padding(vertical=16.dp))
        } else LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){items(records,key={it.id}){TransactionRow(it,accounts,requestDelete,requestEdit)}}
        if(canAdd) Button(onClick=add,modifier=Modifier.fillMaxWidth().padding(vertical=10.dp)){Icon(Icons.Default.Add,null);Spacer(Modifier.width(8.dp));Text("Adicionar movimento")}
    }
}

private fun budgetVisibleInMonth(budget:Budget,monthStart:Long):Boolean = budget.archivedFromMonth?.let{monthStart<it}?:true

private fun budgetTransactions(budget:Budget,tx:List<FinanceTransaction>,monthStart:Long):List<FinanceTransaction> = tx.filter { t ->
    t.kind=="Despesa" && currentMonthStart(t.date)==monthStart && t.budgetId==budget.id
}
private fun budgetSpent(budget:Budget,tx:List<FinanceTransaction>,monthStart:Long):Double = moneySum(budgetTransactions(budget,tx,monthStart).map{it.amount})
private fun budgetLimitFor(budget:Budget,periods:List<BudgetPeriod>,monthStart:Long):Double = periods
    .filter{it.budgetId==budget.id&&it.monthStart<=monthStart}
    .maxByOrNull{it.monthStart}?.limitAmount?:budget.limitAmount

@Composable private fun ReportsPage(modifier:Modifier,tx:List<FinanceTransaction>){val exp=moneySum(tx.filter{it.kind=="Despesa"}.map{it.amount});val inc=moneySum(tx.filter{it.kind=="Receita"}.map{it.amount});val cats=tx.filter{it.kind=="Despesa"}.groupBy{it.category}.mapValues{e->moneySum(e.value.map{it.amount})}.toList().sortedByDescending{it.second};Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)){Text("Resumo geral",style=MaterialTheme.typography.titleLarge,color=Ink,fontWeight=FontWeight.Bold);Text("Com base nos movimentos registados",color=Muted);Spacer(Modifier.height(16.dp));Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){ReportTile("Receitas",inc,Green,Modifier.weight(1f));ReportTile("Despesas",exp,Coral,Modifier.weight(1f))};Spacer(Modifier.height(22.dp));Text("Despesas por categoria",style=MaterialTheme.typography.titleMedium,color=Ink,fontWeight=FontWeight.Bold);Spacer(Modifier.height(10.dp));if(cats.isEmpty())Text("Ainda não existem despesas para analisar.",color=Muted)else cats.forEach{(cat,value)->Row(Modifier.fillMaxWidth().padding(vertical=9.dp),verticalAlignment=Alignment.CenterVertically){Text(cat,Modifier.weight(1f),color=Ink);Text(money(value),color=Ink,fontWeight=FontWeight.SemiBold)}};Spacer(Modifier.height(14.dp));Text("Movimentos totais: ${tx.size}",color=Muted)} }
@Composable private fun ReportTile(label:String,value:Double,color:Color,modifier:Modifier){Column(modifier.background(Color.White,RoundedCornerShape(18.dp)).padding(16.dp)){Text(label,color=Muted);Text(money(value),color=color,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)} }

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun TransactionRow(t:FinanceTransaction,accounts:List<Account>,delete:(FinanceTransaction)->Unit,edit:(FinanceTransaction)->Unit){
    val expense=t.kind=="Despesa"||t.kind=="Transferência"||(t.kind=="Ajuste"&&t.amount<0); val color=if(expense)Coral else Green
    val account=accounts.firstOrNull{it.id==t.accountId}?.name?:"Conta"; val dest=accounts.firstOrNull{it.id==t.toAccountId}?.name
    Row(Modifier.fillMaxWidth().background(Color.White,RoundedCornerShape(17.dp)).combinedClickable(onClick={edit(t)},onLongClick={delete(t)}).padding(14.dp),verticalAlignment=Alignment.CenterVertically){
        Box(Modifier.size(42.dp).background(if(expense)Color(0xFFFFEFEC) else Mint,CircleShape),contentAlignment=Alignment.Center){Icon(if(t.kind=="Transferência")Icons.Default.SwapHoriz else if(expense)Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,null,tint=color,modifier=Modifier.size(20.dp))}
        Column(Modifier.weight(1f).padding(horizontal=12.dp)){
            Text(t.title.ifBlank{t.category},color=Ink,fontWeight=FontWeight.SemiBold)
            Text("${t.category} · $account${dest?.let{" → $it"}?:""}${if(t.recurring)" · Recorrente" else ""}",color=Muted,style=MaterialTheme.typography.labelSmall)
        }
        Column(horizontalAlignment=Alignment.End){Text("${if(expense)"−" else "+"}${money(kotlin.math.abs(t.amount))}",color=color,fontWeight=FontWeight.Bold);Text(SimpleDateFormat("dd MMM",Locale("pt","PT")).format(Date(t.date)),color=Muted,style=MaterialTheme.typography.labelSmall)}
    }
}
@Composable private fun EmptyCard(title:String,body:String,action:String,onClick:()->Unit){Column(Modifier.fillMaxWidth().background(Color.White,RoundedCornerShape(20.dp)).padding(20.dp)){Text(title,color=Ink,fontWeight=FontWeight.Bold);Text(body,color=Muted,modifier=Modifier.padding(top=5.dp,bottom=12.dp));OutlinedButton(onClick=onClick){Text(action,color=Green)}}}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TransactionDialog(
    accounts:List<Account>, budgets:List<Budget>, categories:List<Category>, pinnedBudget:Budget?, initial:FinanceTransaction?,
    save:(FinanceTransaction,(Boolean)->Unit)->Unit, close:()->Unit, createBudget:()->Unit
){
    val movementId=initial?.id?:0L
    var title by remember(movementId){mutableStateOf(initial?.title.orEmpty())}
    var amount by remember(movementId){mutableStateOf(initial?.amount?.let{kotlin.math.abs(it)}?.toString().orEmpty())}
    var kind by remember(movementId){mutableStateOf(initial?.kind?:"Despesa")}
    var adjustmentDecreases by remember(movementId){mutableStateOf(initial?.let{it.kind=="Ajuste"&&it.amount<0}?:false)}
    var categoryName by remember(movementId,pinnedBudget?.id){mutableStateOf(initial?.category?:categories.firstOrNull{it.kind=="Despesa"}?.name.orEmpty())}
    var note by remember(movementId){mutableStateOf(initial?.note.orEmpty())}
    var recurring by remember(movementId){mutableStateOf(initial?.recurring?:false)}
    var accountId by remember(movementId){mutableLongStateOf(initial?.accountId?:accounts.firstOrNull()?.id?:0L)}
    var destinationId by remember(movementId){mutableLongStateOf(initial?.toAccountId?:accounts.firstOrNull{it.id!=accountId}?.id?:accountId)}
    var budgetId by remember(movementId,pinnedBudget?.id){mutableLongStateOf(initial?.budgetId?:pinnedBudget?.id?:0L)}
    var accountMenu by remember{mutableStateOf(false)}; var destinationMenu by remember{mutableStateOf(false)}
    var budgetMenu by remember{mutableStateOf(false)}; var categoryMenu by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}; var saving by remember{mutableStateOf(false)}
    val availableCategories=categories.filter{it.kind==kind}.map{it.name}
    val availableBudgets=budgets.filter{budgetVisibleInMonth(it,currentMonthStart())||it.id==initial?.budgetId}
    val selectedBudget=pinnedBudget?:availableBudgets.firstOrNull{it.id==budgetId}
    val movementKinds=if(initial?.kind=="Ajuste") listOf("Despesa","Receita","Transferência","Ajuste") else listOf("Despesa","Receita","Transferência")

    LaunchedEffect(accounts){
        if(accounts.none{it.id==accountId}) accountId=accounts.firstOrNull()?.id?:0L
        if(accounts.none{it.id==destinationId&&it.id!=accountId}) destinationId=accounts.firstOrNull{it.id!=accountId}?.id?:accountId
    }
    LaunchedEffect(kind,budgets,categories,pinnedBudget?.id){
        if(categoryName !in availableCategories) categoryName=availableCategories.firstOrNull().orEmpty()
        if(kind=="Despesa"){
            val budget=if(pinnedBudget!=null)pinnedBudget else availableBudgets.firstOrNull{it.id==budgetId}
            if(budget==null) budgetId=0L else budgetId=budget.id
        } else budgetId=0L
    }
    AlertDialog(
        onDismissRequest={if(!saving)close()},
        title={Text(if(initial==null)"Novo movimento" else "Editar movimento",color=Ink,fontWeight=FontWeight.Bold)},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(9.dp)){
            if(pinnedBudget==null) Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                movementKinds.forEach{type->FilterChip(selected=kind==type,onClick={kind=type},label={Text(type,style=MaterialTheme.typography.labelSmall)})}
            } else Text("Associado ao orçamento: ${pinnedBudget.name}",color=Green,style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.SemiBold)
            if(kind=="Ajuste") Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                FilterChip(selected=!adjustmentDecreases,onClick={adjustmentDecreases=false},label={Text("Aumentar saldo")})
                FilterChip(selected=adjustmentDecreases,onClick={adjustmentDecreases=true},label={Text("Diminuir saldo")})
            }

            OutlinedTextField(title,{title=it},label={Text("Descrição (opcional)")},singleLine=true)
            OutlinedTextField(amount,{amount=it},label={Text("Montante (€)")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))

            if(kind=="Despesa"){
                ExposedDropdownMenuBox(expanded=categoryMenu,onExpandedChange={categoryMenu=availableCategories.isNotEmpty()&&!categoryMenu}){
                    OutlinedTextField(categoryName,{},readOnly=true,label={Text("Categoria")},modifier=Modifier.menuAnchor().fillMaxWidth(),trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded=categoryMenu)})
                    ExposedDropdownMenu(expanded=categoryMenu,onDismissRequest={categoryMenu=false}){availableCategories.forEach{item->DropdownMenuItem(text={Text(item)},onClick={categoryName=item;categoryMenu=false})}}
                }
                if(pinnedBudget!=null) OutlinedTextField(pinnedBudget.name,{},readOnly=true,label={Text("Orçamento")},supportingText={Text("Movimento associado a este orçamento.")},modifier=Modifier.fillMaxWidth())
                else if(availableBudgets.isEmpty()) Column {
                    Text("É obrigatório associar cada despesa a um orçamento.",color=Muted,style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick=createBudget){Text("Criar orçamento mensal",color=Green)}
                } else ExposedDropdownMenuBox(expanded=budgetMenu,onExpandedChange={budgetMenu=!budgetMenu}){
                    OutlinedTextField(selectedBudget?.name.orEmpty(),{},readOnly=true,label={Text("Orçamento obrigatório")},placeholder={Text("Selecionar orçamento")},modifier=Modifier.menuAnchor().fillMaxWidth(),trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded=budgetMenu)})
                    ExposedDropdownMenu(expanded=budgetMenu,onDismissRequest={budgetMenu=false}){availableBudgets.forEach{item->DropdownMenuItem(text={Text(item.name)},onClick={budgetId=item.id;budgetMenu=false})}}
                }
            } else {
                ExposedDropdownMenuBox(expanded=categoryMenu,onExpandedChange={categoryMenu=availableCategories.isNotEmpty()&&!categoryMenu}){
                    OutlinedTextField(categoryName,{},readOnly=true,label={Text("Categoria")},modifier=Modifier.menuAnchor().fillMaxWidth(),trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded=categoryMenu)})
                    ExposedDropdownMenu(expanded=categoryMenu,onDismissRequest={categoryMenu=false}){availableCategories.forEach{item->DropdownMenuItem(text={Text(item)},onClick={categoryName=item;categoryMenu=false})}}
                }
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
                kind=="Despesa"&&selectedBudget==null -> error="Seleciona ou cria um orçamento para esta despesa."
                categoryName.isBlank() -> error="Escolhe uma categoria."
                kind=="Transferência"&&accounts.none{it.id==destinationId&&it.id!=accountId} -> error="Cria ou escolhe uma conta de destino diferente."
                else -> {
                    error=""; saving=true
                    val signedAmount=if(kind=="Ajuste"&&adjustmentDecreases)-parsedAmount else parsedAmount
                    val value=(initial?:FinanceTransaction(title="",amount=signedAmount,kind=kind,category=categoryName,accountId=accountId)).copy(
                        title=title,amount=signedAmount,kind=kind,category=categoryName,accountId=accountId,
                        recurring=recurring,note=note,toAccountId=if(kind=="Transferência")destinationId else null,
                        budgetId=if(kind=="Despesa")selectedBudget?.id else null
                    )
                    save(value){success->saving=false;if(success)close() else error="Não foi possível guardar. Confirma a categoria, o orçamento e as contas."
                    }
                }
            }
        }){Text(if(saving)"A guardar…" else "Guardar",color=Green,fontWeight=FontWeight.Bold)}},
        dismissButton={TextButton(onClick=close,enabled=!saving){Text("Cancelar",color=Muted)}}
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
private fun shiftMonth(monthStart:Long,offset:Int):Long = Calendar.getInstance().apply {
    timeInMillis=monthStart
    add(Calendar.MONTH,offset)
    set(Calendar.DAY_OF_MONTH,1)
    set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)
}.timeInMillis
private fun monthLabel(monthStart:Long):String = SimpleDateFormat("MMMM yyyy",Locale("pt","PT")).format(Date(monthStart)).replaceFirstChar { it.titlecase(Locale("pt","PT")) }

private fun accountBalance(a:Account,tx:List<FinanceTransaction>):Double {
    val amounts = mutableListOf(a.openingBalance)
    tx.forEach { t -> when { t.kind=="Ajuste" && t.accountId==a.id -> amounts.add(t.amount); t.kind=="Receita" && t.accountId==a.id -> amounts.add(t.amount); t.kind=="Despesa" && t.accountId==a.id -> amounts.add(-t.amount); t.kind=="Transferência" && t.accountId==a.id -> amounts.add(-t.amount); t.kind=="Transferência" && t.toAccountId==a.id -> amounts.add(t.amount); else -> Unit } }
    return moneySum(amounts)
}
private fun moneySum(values:Iterable<Double>):Double = values.filter{it.isFinite()}.fold(BigDecimal.ZERO){sum,value->sum.add(BigDecimal.valueOf(value))}.setScale(2,RoundingMode.HALF_UP).toDouble()
private fun money(value:Double):String = NumberFormat.getCurrencyInstance(Locale("pt","PT")).format(value)
