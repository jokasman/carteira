package pt.carteira.app

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "accounts")
data class Account(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val type: String = "Conta", val openingBalance: Double = 0.0, val currency: String = "EUR")

@Entity(tableName = "transactions", indices = [Index(value = ["importKey"], unique = true), Index(value = ["settlementRevenueId"])])
data class FinanceTransaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val amount: Double,
    val kind: String,
    val category: String,
    val accountId: Long,
    val date: Long = System.currentTimeMillis(),
    val note: String = "",
    val recurring: Boolean = false,
    val toAccountId: Long? = null,
    val budgetId: Long? = null,
    val importKey: String? = null,
    @ColumnInfo(defaultValue = "1") val active: Boolean = true,
    @ColumnInfo(defaultValue = "0") val importedFromCsv: Boolean = false,
    val settlementRevenueId: Long? = null
)

@Entity(tableName = "budgets")
data class Budget(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val limitAmount: Double, val archivedFromMonth: Long? = null)

@Entity(
    tableName = "budget_rules",
    primaryKeys = ["merchantKey"],
    foreignKeys = [ForeignKey(entity = Budget::class, parentColumns = ["id"], childColumns = ["budgetId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["budgetId"])]
)
data class BudgetRule(val merchantKey: String, val merchantLabel: String, val budgetId: Long)

@Entity(tableName = "categories", indices = [Index(value = ["name", "kind"], unique = true)])
data class Category(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val kind: String)

@Entity(
    tableName = "budget_periods",
    primaryKeys = ["budgetId", "monthStart"],
    foreignKeys = [ForeignKey(entity = Budget::class, parentColumns = ["id"], childColumns = ["budgetId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["budgetId"])]
)
data class BudgetPeriod(val budgetId: Long, val monthStart: Long, val limitAmount: Double)

@Dao interface FinanceDao {
    @Query("SELECT * FROM accounts ORDER BY id") fun accounts(): Flow<List<Account>>
    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1") suspend fun accountById(id: Long): Account?
    @Query("SELECT * FROM accounts ORDER BY id LIMIT 1") suspend fun firstAccount(): Account?
    @Query("SELECT * FROM transactions ORDER BY date DESC, id DESC") fun transactions(): Flow<List<FinanceTransaction>>
    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1") suspend fun transactionById(id: Long): FinanceTransaction?
    @Query("SELECT * FROM transactions WHERE id IN (:ids)") suspend fun transactionsByIds(ids: List<Long>): List<FinanceTransaction>
    @Query("SELECT * FROM transactions WHERE settlementRevenueId = :incomeId ORDER BY date, id") suspend fun settlementExpenses(incomeId: Long): List<FinanceTransaction>
    @Query("SELECT * FROM budgets ORDER BY name") fun budgets(): Flow<List<Budget>>
    @Query("SELECT * FROM budgets ORDER BY id") suspend fun allBudgets(): List<Budget>
    @Query("SELECT * FROM budgets WHERE id = :id LIMIT 1") suspend fun budgetById(id: Long): Budget?
    @Query("SELECT * FROM budget_periods") fun budgetPeriods(): Flow<List<BudgetPeriod>>
    @Query("SELECT * FROM budget_rules") fun budgetRules(): Flow<List<BudgetRule>>
    @Query("SELECT * FROM budget_rules") suspend fun allBudgetRules(): List<BudgetRule>
    @Query("SELECT * FROM budget_rules WHERE merchantKey = :merchantKey LIMIT 1") suspend fun budgetRule(merchantKey: String): BudgetRule?
    @Query("SELECT * FROM budget_periods WHERE budgetId = :budgetId ORDER BY monthStart") suspend fun periodsForBudget(budgetId: Long): List<BudgetPeriod>
    @Query("SELECT * FROM categories ORDER BY name") fun categories(): Flow<List<Category>>
    @Query("SELECT EXISTS(SELECT 1 FROM categories WHERE name = :name AND kind = :kind)") suspend fun categoryExists(name: String, kind: String): Boolean
    @Insert suspend fun addAccount(value: Account): Long
    @Update suspend fun updateAccount(value: Account)
    @Insert suspend fun addTransaction(value: FinanceTransaction)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun importTransactions(values: List<FinanceTransaction>): List<Long>
    @Update suspend fun updateTransaction(value: FinanceTransaction)
    @Transaction suspend fun saveTransactionAndRule(value: FinanceTransaction, rule: BudgetRule?) {
        if (value.id == 0L) addTransaction(value) else updateTransaction(value)
        if (rule != null) saveBudgetRule(rule)
    }
    @Transaction suspend fun applyClassificationChanges(
        budgetUpdates: List<FinanceTransaction>,
        incomeUpdates: List<FinanceTransaction>,
        settlementExpenseUpdates: List<FinanceTransaction>,
        expensePartUpdates: List<FinanceTransaction>
    ) {
        budgetUpdates.forEach { updateTransaction(it) }
        incomeUpdates.forEach { updateTransaction(it) }
        settlementExpenseUpdates.forEach { updateTransaction(it) }
        expensePartUpdates.forEach { if (it.id == 0L) addTransaction(it) else updateTransaction(it) }
    }
    @Transaction suspend fun splitTransaction(first: FinanceTransaction, second: FinanceTransaction) {
        updateTransaction(first)
        addTransaction(second)
    }
    @Transaction suspend fun updateIncomeSettlement(income: FinanceTransaction, previousExpenses: List<FinanceTransaction>, expenses: List<FinanceTransaction>) {
        val selectedIds = expenses.map { it.id }.toSet()
        previousExpenses.filter { it.id !in selectedIds }.forEach { expense ->
            updateTransaction(expense.copy(active = true, settlementRevenueId = null))
        }
        updateTransaction(income.copy(active = false))
        expenses.forEach { expense -> updateTransaction(expense.copy(active = false, settlementRevenueId = income.id)) }
    }
    @Transaction suspend fun undoIncomeSettlement(income: FinanceTransaction, expenses: List<FinanceTransaction>) {
        updateTransaction(income.copy(active = true))
        expenses.forEach { expense -> updateTransaction(expense.copy(active = true, settlementRevenueId = null)) }
    }
    @Insert suspend fun addBudget(value: Budget): Long
    @Update suspend fun updateBudget(value: Budget)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveBudgetRule(value: BudgetRule)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveBudgetPeriod(value: BudgetPeriod)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addCategories(values: List<Category>)
    @Delete suspend fun deleteTransaction(value: FinanceTransaction)
    @Delete suspend fun deleteTransactions(values: List<FinanceTransaction>)
    @Query("UPDATE transactions SET settlementRevenueId = NULL WHERE settlementRevenueId IN (:incomeIds)") suspend fun clearSettlementLinks(incomeIds: List<Long>)
    @Transaction suspend fun deleteTransactionAndClearSettlement(value: FinanceTransaction) {
        if (value.kind == "Receita") clearSettlementLinks(listOf(value.id))
        deleteTransaction(value)
    }
    @Transaction suspend fun deleteTransactionsAndClearSettlement(values: List<FinanceTransaction>) {
        val incomeIds = values.filter { it.kind == "Receita" }.map { it.id }
        if (incomeIds.isNotEmpty()) clearSettlementLinks(incomeIds)
        deleteTransactions(values)
    }
    @Delete suspend fun deleteBudget(value: Budget)
    @Query("SELECT COUNT(*) FROM accounts") suspend fun accountCount(): Int
}

@Database(entities = [Account::class, FinanceTransaction::class, Budget::class, Category::class, BudgetPeriod::class, BudgetRule::class], version = 10, exportSchema = false)
abstract class FinanceDb : RoomDatabase() {
    abstract fun dao(): FinanceDao
    companion object {
        @Volatile private var instance: FinanceDb? = null
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN budgetId INTEGER")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS categories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_categories_name_kind ON categories(name, kind)")
                DefaultCategoryOptions.forEach { (name, kind) ->
                    db.execSQL("INSERT OR IGNORE INTO categories(name, kind) VALUES (?, ?)", arrayOf(name, kind))
                }
                db.execSQL("INSERT OR IGNORE INTO categories(name, kind) SELECT DISTINCT category, kind FROM transactions WHERE trim(category) <> ''")
                db.execSQL("INSERT OR IGNORE INTO categories(name, kind) SELECT DISTINCT category, 'Despesa' FROM budgets WHERE trim(category) <> ''")
                // Preserve existing expense history by associating it with a same-category budget when one exists.
                db.execSQL("UPDATE transactions SET budgetId = (SELECT id FROM budgets WHERE lower(trim(budgets.category)) = lower(trim(transactions.category)) ORDER BY id LIMIT 1) WHERE kind = 'Despesa' AND budgetId IS NULL AND EXISTS (SELECT 1 FROM budgets WHERE lower(trim(budgets.category)) = lower(trim(transactions.category)))")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS budget_periods (budgetId INTEGER NOT NULL, monthStart INTEGER NOT NULL, limitAmount REAL NOT NULL, PRIMARY KEY(budgetId, monthStart), FOREIGN KEY(budgetId) REFERENCES budgets(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_budget_periods_budgetId ON budget_periods(budgetId)")
                val calendar = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.DAY_OF_MONTH, 1)
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                db.execSQL("INSERT OR IGNORE INTO budget_periods(budgetId, monthStart, limitAmount) SELECT id, ?, limitAmount FROM budgets", arrayOf(calendar.timeInMillis))
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE budgets ADD COLUMN archivedFromMonth INTEGER")
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Preserve monthly history while rebuilding budgets without its former category column.
                db.execSQL("CREATE TABLE budget_periods_backup (budgetId INTEGER NOT NULL, monthStart INTEGER NOT NULL, limitAmount REAL NOT NULL, PRIMARY KEY(budgetId, monthStart))")
                db.execSQL("INSERT INTO budget_periods_backup(budgetId, monthStart, limitAmount) SELECT budgetId, monthStart, limitAmount FROM budget_periods")
                db.execSQL("DROP TABLE budget_periods")
                db.execSQL("CREATE TABLE budgets_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, limitAmount REAL NOT NULL, archivedFromMonth INTEGER)")
                db.execSQL("INSERT INTO budgets_new(id, name, limitAmount, archivedFromMonth) SELECT id, name, limitAmount, archivedFromMonth FROM budgets")
                db.execSQL("DROP TABLE budgets")
                db.execSQL("ALTER TABLE budgets_new RENAME TO budgets")
                db.execSQL("CREATE TABLE budget_periods (budgetId INTEGER NOT NULL, monthStart INTEGER NOT NULL, limitAmount REAL NOT NULL, PRIMARY KEY(budgetId, monthStart), FOREIGN KEY(budgetId) REFERENCES budgets(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX index_budget_periods_budgetId ON budget_periods(budgetId)")
                db.execSQL("INSERT INTO budget_periods(budgetId, monthStart, limitAmount) SELECT budgetId, monthStart, limitAmount FROM budget_periods_backup")
                db.execSQL("DROP TABLE budget_periods_backup")
            }
        }
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN importKey TEXT")
                db.execSQL("CREATE UNIQUE INDEX index_transactions_importKey ON transactions(importKey)")
                db.execSQL("CREATE TABLE budget_rules (merchantKey TEXT NOT NULL, merchantLabel TEXT NOT NULL, budgetId INTEGER NOT NULL, PRIMARY KEY(merchantKey), FOREIGN KEY(budgetId) REFERENCES budgets(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX index_budget_rules_budgetId ON budget_rules(budgetId)")
            }
        }
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN active INTEGER NOT NULL DEFAULT 1")
            }
        }
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN importedFromCsv INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE transactions SET importedFromCsv = 1 WHERE importKey IS NOT NULL")
            }
        }
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN settlementRevenueId INTEGER")
                db.execSQL("CREATE INDEX index_transactions_settlementRevenueId ON transactions(settlementRevenueId)")
            }
        }
        fun get(context: Context): FinanceDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, FinanceDb::class.java, "carteira.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                .build().also { instance = it }
        }
    }
}

val DefaultCategoryOptions = listOf(
    "Alimentação" to "Despesa",
    "Casa" to "Despesa",
    "Transportes" to "Despesa",
    "Saúde" to "Despesa",
    "Lazer" to "Despesa",
    "Compras" to "Despesa",
    "Educação" to "Despesa",
    "Serviços" to "Despesa",
    "Outros" to "Despesa",
    "Salário" to "Receita",
    "Trabalho" to "Receita",
    "Investimentos" to "Receita",
    "Reembolsos" to "Receita",
    "Outros rendimentos" to "Receita",
    "Transferência" to "Transferência",
    "Acerto de saldo" to "Ajuste"
)
