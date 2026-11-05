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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
    init { viewModelScope.launch { ensureDefaultAccount() } }
    private suspend fun ensureDefaultAccount(): Account = accountMutex.withLock {
        dao.firstAccount() ?: run {
            dao.addAccount(Account(name = "Dinheiro", type = "Carteira"))
            dao.firstAccount() ?: error("Não foi possível criar a conta inicial")
        }
    }
    fun addAccount(name: String, type: String) = viewModelScope.launch { if(name.isNotBlank()) dao.addAccount(Account(name=name, type=type)) }
    fun addTransaction(title:String, amount:Double, kind:String, category:String, accountId:Long, recurring:Boolean, note:String, toAccountId:Long? = null, budgetId:Long? = null, onComplete:(Boolean)->Unit = {}) = viewModelScope.launch {
        val saved = try {
            val source = dao.accountById(accountId) ?: ensureDefaultAccount()
            if(title.isBlank() || category.isBlank() || kind !in listOf("Despesa", "Receita", "Transferência") || !amount.isFinite() || amount < 0.01 || (kind == "Transferência" && (toAccountId == null || toAccountId == source.id || dao.accountById(toAccountId) == null)) || (budgetId != null && dao.budgetById(budgetId) == null)) false
            else {
                val roundedAmount = BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).toDouble()
                dao.addTransaction(FinanceTransaction(title=title, amount=roundedAmount, kind=kind, category=category, accountId=source.id, recurring=recurring, note=note, toAccountId=toAccountId, budgetId=budgetId))
                true
            }
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
        onComplete(saved)
    }
    fun addBudget(name:String, category:String, amount:Double) = viewModelScope.launch { if(name.isNotBlank() && category.isNotBlank() && amount.isFinite() && amount>=0.01) dao.addBudget(Budget(name=name,category=category,limitAmount=BigDecimal.valueOf(amount).setScale(2,RoundingMode.HALF_UP).toDouble())) }
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
    val accounts by model.accounts.collectAsState(); val transactions by model.transactions.collectAsState(); val budgets by model.budgets.collectAsState()
    var section by remember { mutableStateOf("Início") }; var dialog by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<FinanceTransaction?>(null) }
    var activeBudgetId by remember { mutableStateOf<Long?>(null) }
    var movementBudgetId by remember { mutableStateOf<Long?>(null) }
    val activeBudget = budgets.firstOrNull { it.id == activeBudgetId }
    val pageTitle = if (section == "DetalheOrçamento") activeBudget?.name ?: "Orçamento" else section
    val total = accounts.sumOf { accountBalance(it, transactions) }
    Scaffold(containerColor=Paper, topBar={
        Row(Modifier.fillMaxWidth().background(Paper).statusBarsPadding().padding(horizontal=22.dp, vertical=18.dp), verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("CARTEIRA", style=MaterialTheme.typography.labelMedium, color=Green, fontWeight=FontWeight.Bold); Text(pageTitle, style=MaterialTheme.typography.headlineSmall, color=Ink, fontWeight=FontWeight.Bold) }
            IconButton(onClick={movementBudgetId=if(section=="DetalheOrçamento")activeBudgetId else null;dialog="movimento"}) { Icon(Icons.Default.Add, "Novo movimento", tint=Green) }
        }
    }, bottomBar={ NavigationBar(containerColor=Color.White) {
        listOf("Início" to Icons.Default.Home, "Movimentos" to Icons.Default.ReceiptLong, "Orçamentos" to Icons.Default.PieChart, "Contas" to Icons.Default.AccountBalanceWallet, "Relatórios" to Icons.Default.BarChart).forEach { (label, icon) ->
            NavigationBarItem(selected=section==label || (section=="DetalheOrçamento" && label=="Orçamentos"), onClick={section=label}, icon={Icon(icon,label)}, label={Text(label, style=MaterialTheme.typography.labelSmall)}, colors=NavigationBarItemDefaults.colors(selectedIconColor=Green,selectedTextColor=Green,indicatorColor=Mint))
        }
    } }, floatingActionButton={ if(section in listOf("Movimentos","Orçamentos","Contas","DetalheOrçamento")) FloatingActionButton(onClick={when(section){"Orçamentos"->dialog="orçamento";"Contas"->dialog="conta";"DetalheOrçamento"->{movementBudgetId=activeBudgetId;dialog="movimento"};else->{movementBudgetId=null;dialog="movimento"}}},containerColor=Green,contentColor=Color.White){Icon(Icons.Default.Add,"Adicionar")} }) { pad ->
        when(section) {
            "Início" -> HomePage(Modifier.padding(pad), total, transactions, accounts, budgets, {section=it}, {pendingDelete=it}, {movementBudgetId=null;dialog="movimento"}, {budget->activeBudgetId=budget.id;section="DetalheOrçamento"})
            "Movimentos" -> TransactionsPage(Modifier.padding(pad), transactions, accounts, {pendingDelete=it}, {movementBudgetId=null;dialog="movimento"})
            "Contas" -> AccountsPage(Modifier.padding(pad), accounts, transactions, {dialog="conta"})
            "Orçamentos" -> BudgetsPage(Modifier.padding(pad), budgets, transactions, {dialog="orçamento"}, {budget->activeBudgetId=budget.id;section="DetalheOrçamento"})
            "DetalheOrçamento" -> BudgetDetailPage(Modifier.padding(pad), activeBudget, transactions, accounts, {movementBudgetId=activeBudget?.id;dialog="movimento"}, {pendingDelete=it})
            else -> ReportsPage(Modifier.padding(pad), transactions)
        }
    }
    if(dialog.isNotEmpty()) when(dialog) {
        "movimento" -> TransactionDialog(accounts, budgets.firstOrNull{it.id==movementBudgetId}, { t,a,k,c,acc,r,n,dest,budgetId,done -> model.addTransaction(t,a,k,c,acc,r,n,dest,budgetId,done) }, {dialog="";movementBudgetId=null})
        "conta" -> SimpleDialog("Nova conta", listOf("Nome","Tipo"), listOf("","Carteira"), listOf("Carteira","Conta bancária","Cartão de crédito","Poupança"), {v->model.addAccount(v[0],v.getOrElse(1){"Conta"});dialog=""}, {dialog=""})
        else -> SimpleDialog("Novo orçamento", listOf("Nome","Categoria","Limite (€)"), listOf("Casa","Habitação","500"), emptyList(), {v->model.addBudget(v[0],v[1],v[2].replace(',','.').toDoubleOrNull()?:0.0);dialog=""}, {dialog=""})
    }
    pendingDelete?.let { transaction ->
        AlertDialog(
            onDismissRequest={pendingDelete=null},
            title={Text("Eliminar movimento?",color=Ink,fontWeight=FontWeight.Bold)},
            text={Text("O movimento ‘${transaction.title}’ será removido da carteira.",color=Muted)},
            confirmButton={TextButton(onClick={model.deleteTransaction(transaction);pendingDelete=null}){Text("Eliminar",color=Coral)}},
            dismissButton={TextButton(onClick={pendingDelete=null}){Text("Cancelar",color=Green)}}
        )
    }
}

@Composable private fun HomePage(modifier:Modifier,total:Double,tx:List<FinanceTransaction>,accounts:List<Account>,budgets:List<Budget>,navigate:(String)->Unit,requestDelete:(FinanceTransaction)->Unit,addMovement:()->Unit,openBudget:(Budget)->Unit) {
    val cal=Calendar.getInstance(); val month=cal.get(Calendar.MONTH); val year=cal.get(Calendar.YEAR)
    val monthly=tx.filter { val c=Calendar.getInstance().apply{timeInMillis=it.date}; c.get(Calendar.MONTH)==month&&c.get(Calendar.YEAR)==year }
    val spent=moneySum(monthly.filter{it.kind=="Despesa"}.map{it.amount}); val income=moneySum(monthly.filter{it.kind=="Receita"}.map{it.amount})
    LazyColumn(modifier.fillMaxSize(), contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=24.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { BalanceCard(total, income, spent) }
        item { SectionTitle("As minhas contas", "Ver todas", {navigate("Contas")}) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) { accounts.take(3).forEach { AccountMini(it,tx) }; AddMini { navigate("Contas") } } }
        item { SectionTitle("Orçamento deste mês", "Ver orçamento", {navigate("Orçamentos")}) }
        if(budgets.isEmpty()) item { EmptyCard("Ainda não tens orçamentos", "Define limites por categoria para acompanhar as despesas.", "Criar orçamento", {navigate("Orçamentos")}) }
        else items(budgets.take(3)) { b -> BudgetRow(b, tx) { openBudget(b) } }
        item { SectionTitle("Movimentos recentes", "Ver todos", {navigate("Movimentos")}) }
        if(tx.isEmpty()) item { EmptyCard("Tudo começa aqui", "Regista a tua primeira receita ou despesa.", "Adicionar movimento", addMovement) }
        else items(tx.take(5)) { TransactionRow(it,accounts,requestDelete) }
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

@Composable private fun TransactionsPage(modifier:Modifier,tx:List<FinanceTransaction>,accounts:List<Account>,requestDelete:(FinanceTransaction)->Unit,addMovement:()->Unit){var filter by remember{mutableStateOf("Todos")};val filtered=tx.filter{filter=="Todos"||it.kind==filter};Column(modifier.fillMaxSize().padding(horizontal=20.dp)){Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.horizontalScroll(rememberScrollState()).padding(bottom=12.dp)){listOf("Todos","Despesa","Receita","Transferência").forEach{AssistChip(onClick={filter=it},label={Text(it)},colors=AssistChipDefaults.assistChipColors(containerColor=if(filter==it)Mint else Color.White))}};if(filtered.isEmpty())EmptyCard(if(tx.isEmpty())"Sem movimentos" else "Sem resultados",if(tx.isEmpty())"Adiciona receitas, despesas ou transferências." else "Não há movimentos deste tipo.","Novo movimento",addMovement)else LazyColumn(verticalArrangement=Arrangement.spacedBy(9.dp)){items(filtered){TransactionRow(it,accounts,requestDelete)}}} }
@Composable private fun AccountsPage(modifier:Modifier,accounts:List<Account>,tx:List<FinanceTransaction>,add:()->Unit){Column(modifier.fillMaxSize().padding(20.dp)){Row(verticalAlignment=Alignment.CenterVertically){Text("Contas e carteiras",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,color=Ink);TextButton(onClick=add){Text("+ Adicionar")}};Spacer(Modifier.height(8.dp));accounts.forEach{AccountDetail(it,tx);Spacer(Modifier.height(10.dp))}}}
@Composable private fun AccountDetail(a:Account,tx:List<FinanceTransaction>){val bal=accountBalance(a,tx);Row(Modifier.fillMaxWidth().background(Color.White,RoundedCornerShape(18.dp)).padding(18.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.AccountBalanceWallet,null,tint=Green,modifier=Modifier.size(40.dp));Column(Modifier.weight(1f).padding(start=14.dp)){Text(a.name,color=Ink,fontWeight=FontWeight.Bold);Text(a.type,color=Muted,style=MaterialTheme.typography.bodySmall)};Text(money(bal),color=Ink,fontWeight=FontWeight.Bold)} }

@Composable private fun BudgetsPage(modifier:Modifier,budgets:List<Budget>,tx:List<FinanceTransaction>,add:()->Unit,openBudget:(Budget)->Unit){
    LazyColumn(modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item { Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Os teus limites",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,color=Ink);Text("Toca num orçamento para ver os registos",color=Muted)};TextButton(onClick=add){Text("+ Criar")}} }
        if(budgets.isEmpty()) item { EmptyCard("Orçamento sob controlo","Cria um limite mensal para uma categoria.","Criar orçamento",add) }
        else items(budgets){budget->BudgetRow(budget,tx){openBudget(budget)}}
    }
}

@Composable private fun BudgetRow(b:Budget,tx:List<FinanceTransaction>,onClick:()->Unit){
    val spent=budgetSpent(b,tx); val p=(spent/b.limitAmount).toFloat().coerceIn(0f,1f)
    Column(Modifier.fillMaxWidth().clickable(onClick=onClick).background(Color.White,RoundedCornerShape(18.dp)).padding(16.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(b.name,color=Ink,fontWeight=FontWeight.Bold);Text(b.category,color=Muted,style=MaterialTheme.typography.bodySmall)};Text("${money(spent)} / ${money(b.limitAmount)}",color=Ink,fontWeight=FontWeight.SemiBold)}
        Spacer(Modifier.height(12.dp));LinearProgressIndicator(progress={p},modifier=Modifier.fillMaxWidth().height(7.dp),color=if(p>.9f)Coral else Green,trackColor=Mint)
        Text(if(spent>b.limitAmount)"Ultrapassaste ${money(spent-b.limitAmount)}" else "Disponível ${money(b.limitAmount-spent)}",color=if(p>.9f)Coral else Muted,style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(top=7.dp))
    }
}

@Composable private fun BudgetDetailPage(modifier:Modifier,budget:Budget?,tx:List<FinanceTransaction>,accounts:List<Account>,add:()->Unit,requestDelete:(FinanceTransaction)->Unit){
    if(budget==null){EmptyCard("Orçamento indisponível","Este orçamento já não existe.","Voltar aos orçamentos",{}) ; return}
    val records=budgetTransactions(budget,tx)
    Column(modifier.fillMaxSize().padding(horizontal=20.dp)){
        Spacer(Modifier.height(8.dp));BudgetRow(budget,tx){}
        Row(Modifier.fillMaxWidth().padding(top=18.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically){Text("Registos deste mês",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,color=Ink,fontWeight=FontWeight.Bold);Text(records.size.toString(),color=Muted)}
        if(records.isEmpty()) EmptyCard("Ainda sem registos","Os movimentos deste orçamento aparecem aqui.","Adicionar movimento",add)
        else LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){items(records){TransactionRow(it,accounts,requestDelete)}}
        if(records.isNotEmpty()) Button(onClick=add,modifier=Modifier.fillMaxWidth().padding(vertical=10.dp)){Icon(Icons.Default.Add,null);Spacer(Modifier.width(8.dp));Text("Adicionar movimento")}
    }
}

private fun budgetTransactions(budget:Budget,tx:List<FinanceTransaction>):List<FinanceTransaction> = tx.filter { t ->
    val cal=Calendar.getInstance().apply{timeInMillis=t.date}; val now=Calendar.getInstance()
    t.kind=="Despesa" && cal.get(Calendar.MONTH)==now.get(Calendar.MONTH) && cal.get(Calendar.YEAR)==now.get(Calendar.YEAR) &&
        (t.budgetId==budget.id || (t.budgetId==null && t.category.equals(budget.category,true)))
}
private fun budgetSpent(budget:Budget,tx:List<FinanceTransaction>):Double = moneySum(budgetTransactions(budget,tx).map{it.amount})

@Composable private fun ReportsPage(modifier:Modifier,tx:List<FinanceTransaction>){val exp=moneySum(tx.filter{it.kind=="Despesa"}.map{it.amount});val inc=moneySum(tx.filter{it.kind=="Receita"}.map{it.amount});val cats=tx.filter{it.kind=="Despesa"}.groupBy{it.category}.mapValues{e->moneySum(e.value.map{it.amount})}.toList().sortedByDescending{it.second};Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)){Text("Resumo geral",style=MaterialTheme.typography.titleLarge,color=Ink,fontWeight=FontWeight.Bold);Text("Com base nos movimentos registados",color=Muted);Spacer(Modifier.height(16.dp));Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){ReportTile("Receitas",inc,Green,Modifier.weight(1f));ReportTile("Despesas",exp,Coral,Modifier.weight(1f))};Spacer(Modifier.height(22.dp));Text("Despesas por categoria",style=MaterialTheme.typography.titleMedium,color=Ink,fontWeight=FontWeight.Bold);Spacer(Modifier.height(10.dp));if(cats.isEmpty())Text("Ainda não existem despesas para analisar.",color=Muted)else cats.forEach{(cat,value)->Row(Modifier.fillMaxWidth().padding(vertical=9.dp),verticalAlignment=Alignment.CenterVertically){Text(cat,Modifier.weight(1f),color=Ink);Text(money(value),color=Ink,fontWeight=FontWeight.SemiBold)}};Spacer(Modifier.height(14.dp));Text("Movimentos totais: ${tx.size}",color=Muted)} }
@Composable private fun ReportTile(label:String,value:Double,color:Color,modifier:Modifier){Column(modifier.background(Color.White,RoundedCornerShape(18.dp)).padding(16.dp)){Text(label,color=Muted);Text(money(value),color=color,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)} }

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun TransactionRow(t:FinanceTransaction,accounts:List<Account>,delete:(FinanceTransaction)->Unit){val expense=t.kind=="Despesa"||t.kind=="Transferência";val color=if(expense)Coral else Green;val account=accounts.firstOrNull{it.id==t.accountId}?.name?:"Conta";val dest=accounts.firstOrNull{it.id==t.toAccountId}?.name;Row(Modifier.fillMaxWidth().background(Color.White,RoundedCornerShape(17.dp)).combinedClickable(onClick={},onLongClick={delete(t)}).padding(14.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(42.dp).background(if(expense)Color(0xFFFFEFEC) else Mint,CircleShape),contentAlignment=Alignment.Center){Icon(if(t.kind=="Transferência")Icons.Default.SwapHoriz else if(expense)Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,null,tint=color,modifier=Modifier.size(20.dp))};Column(Modifier.weight(1f).padding(horizontal=12.dp)){Text(t.title,color=Ink,fontWeight=FontWeight.SemiBold);Text("${t.category} · $account${dest?.let{" → $it"}?:""}${if(t.recurring)" · Recorrente" else ""}",color=Muted,style=MaterialTheme.typography.labelSmall)};Column(horizontalAlignment=Alignment.End){Text("${if(expense)"−" else "+"}${money(t.amount)}",color=color,fontWeight=FontWeight.Bold);Text(SimpleDateFormat("dd MMM",Locale("pt","PT")).format(Date(t.date)),color=Muted,style=MaterialTheme.typography.labelSmall)}} }
@Composable private fun EmptyCard(title:String,body:String,action:String,onClick:()->Unit){Column(Modifier.fillMaxWidth().background(Color.White,RoundedCornerShape(20.dp)).padding(20.dp)){Text(title,color=Ink,fontWeight=FontWeight.Bold);Text(body,color=Muted,modifier=Modifier.padding(top=5.dp,bottom=12.dp));OutlinedButton(onClick=onClick){Text(action,color=Green)}}}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TransactionDialog(accounts:List<Account>,budget:Budget?,save:(String,Double,String,String,Long,Boolean,String,Long?,Long?,(Boolean)->Unit)->Unit,close:()->Unit){var title by remember{mutableStateOf("")};var amount by remember{mutableStateOf("")};var kind by remember{mutableStateOf("Despesa")};var category by remember(budget?.id){mutableStateOf(budget?.category?:"Alimentação")};var note by remember{mutableStateOf("")};var recurring by remember{mutableStateOf(false)};var account by remember{mutableLongStateOf(accounts.firstOrNull()?.id?:0L)};var destination by remember{mutableLongStateOf(accounts.getOrNull(1)?.id?:accounts.firstOrNull()?.id?:0L)};var expanded by remember{mutableStateOf(false)};var expandedDest by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")};var saving by remember{mutableStateOf(false)}
    LaunchedEffect(accounts) {
        if(accounts.none{it.id==account}) account=accounts.firstOrNull()?.id?:0L
        if(accounts.none{it.id==destination && it.id!=account}) destination=accounts.firstOrNull{it.id!=account}?.id?:account
    }
    AlertDialog(onDismissRequest={if(!saving)close()},title={Text("Novo movimento",color=Ink,fontWeight=FontWeight.Bold)},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(9.dp)){
        if(budget==null) Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("Despesa","Receita","Transferência").forEach{FilterChip(selected=kind==it,onClick={kind=it},label={Text(it,style=MaterialTheme.typography.labelSmall)})}}
        else Text("Associado ao orçamento: ${budget.name}",color=Green,style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.SemiBold)
        OutlinedTextField(title,{title=it},label={Text("Descrição")},singleLine=true)
        OutlinedTextField(amount,{amount=it},label={Text("Montante (€)")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
        OutlinedTextField(category,{category=it},label={Text("Categoria")},singleLine=true)
        ExposedDropdownMenuBox(expanded=expanded,onExpandedChange={expanded=!expanded}){OutlinedTextField(accounts.firstOrNull{it.id==account}?.name?:"Escolher conta",{},readOnly=true,label={Text("Conta")},modifier=Modifier.menuAnchor().fillMaxWidth());ExposedDropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){accounts.forEach{DropdownMenuItem(text={Text(it.name)},onClick={account=it.id;expanded=false})}}}
        if(kind=="Transferência") ExposedDropdownMenuBox(expanded=expandedDest,onExpandedChange={expandedDest=!expandedDest}){OutlinedTextField(accounts.firstOrNull{it.id==destination}?.name?:"Criar outra conta primeiro",{},readOnly=true,label={Text("Conta de destino")},modifier=Modifier.menuAnchor().fillMaxWidth());ExposedDropdownMenu(expanded=expandedDest,onDismissRequest={expandedDest=false}){accounts.filter{it.id!=account}.forEach{DropdownMenuItem(text={Text(it.name)},onClick={destination=it.id;expandedDest=false})}}}
        OutlinedTextField(note,{note=it},label={Text("Nota (opcional)")},singleLine=true)
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(recurring,{recurring=it});Text("Movimento recorrente",color=Ink)}
        if(error.isNotBlank()) Text(error,color=Coral,style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(enabled=!saving,onClick={
        val parsedAmount=amount.replace(',','.').toDoubleOrNull()
        when {
            title.isBlank() -> error="Indica uma descrição."
            parsedAmount==null || !parsedAmount.isFinite() || parsedAmount<0.01 -> error="Indica um montante válido, mínimo de 0,01 €."
            kind=="Transferência" && accounts.none{it.id==destination && it.id!=account} -> error="Cria ou escolhe uma conta de destino diferente."
            else -> { error=""; saving=true; save(title,parsedAmount,kind,category,account,recurring,note,if(kind=="Transferência")destination else null,budget?.id){success->saving=false;if(success)close() else error="Não foi possível guardar. Confirma as contas e tenta novamente."} }
        }
    }){Text(if(saving)"A guardar…" else "Guardar",color=Green,fontWeight=FontWeight.Bold)}},dismissButton={TextButton(onClick=close,enabled=!saving){Text("Cancelar",color=Muted) }})
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SimpleDialog(title:String,labels:List<String>,defaults:List<String>,options:List<String>,save:(List<String>)->Unit,close:()->Unit){val values=remember{mutableStateListOf<String>().also{l->labels.indices.forEach{i->l.add(defaults.getOrElse(i){""})}}};var expanded by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")};AlertDialog(onDismissRequest=close,title={Text(title,color=Ink,fontWeight=FontWeight.Bold)},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(9.dp)){labels.forEachIndexed{i,label->if(i==1&&options.isNotEmpty())ExposedDropdownMenuBox(expanded=expanded,onExpandedChange={expanded=!expanded}){OutlinedTextField(values[i],{},readOnly=true,label={Text(label)},modifier=Modifier.menuAnchor().fillMaxWidth());ExposedDropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){options.forEach{DropdownMenuItem(text={Text(it)},onClick={values[i]=it;expanded=false})}}}else OutlinedTextField(values[i],{values[i]=it},label={Text(label)},singleLine=true,keyboardOptions=if(label.contains("€"))KeyboardOptions(keyboardType=KeyboardType.Decimal)else KeyboardOptions.Default)};if(error.isNotBlank())Text(error,color=Coral,style=MaterialTheme.typography.bodySmall)}},confirmButton={TextButton(onClick={val amountIndex=labels.indexOfFirst{it.contains("€")};val parsed=if(amountIndex>=0)values[amountIndex].replace(',','.').toDoubleOrNull() else null;when{values.any{it.isBlank()}->error="Preenche todos os campos.";amountIndex>=0&&(parsed==null||!parsed.isFinite()||parsed<0.01)->error="Indica um montante válido, mínimo de 0,01 €.";else->{error="";save(values.toList())}}}){Text("Guardar",color=Green,fontWeight=FontWeight.Bold)}},dismissButton={TextButton(onClick=close){Text("Cancelar",color=Muted)}})}

private fun accountBalance(a:Account,tx:List<FinanceTransaction>):Double {
    val amounts = mutableListOf(a.openingBalance)
    tx.forEach { t -> when { t.kind=="Receita" && t.accountId==a.id -> amounts.add(t.amount); t.kind=="Despesa" && t.accountId==a.id -> amounts.add(-t.amount); t.kind=="Transferência" && t.accountId==a.id -> amounts.add(-t.amount); t.kind=="Transferência" && t.toAccountId==a.id -> amounts.add(t.amount); else -> Unit } }
    return moneySum(amounts)
}
private fun moneySum(values:Iterable<Double>):Double = values.filter{it.isFinite()}.fold(BigDecimal.ZERO){sum,value->sum.add(BigDecimal.valueOf(value))}.setScale(2,RoundingMode.HALF_UP).toDouble()
private fun money(value:Double):String = NumberFormat.getCurrencyInstance(Locale("pt","PT")).format(value)
