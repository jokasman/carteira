package pt.carteira.app

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "accounts")
data class Account(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val type: String = "Conta", val openingBalance: Double = 0.0, val currency: String = "EUR")

@Entity(tableName = "transactions")
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
    val budgetId: Long? = null
)

@Entity(tableName = "budgets")
data class Budget(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val category: String, val limitAmount: Double)

@Dao interface FinanceDao {
    @Query("SELECT * FROM accounts ORDER BY id") fun accounts(): Flow<List<Account>>
    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1") suspend fun accountById(id: Long): Account?
    @Query("SELECT * FROM accounts ORDER BY id LIMIT 1") suspend fun firstAccount(): Account?
    @Query("SELECT * FROM transactions ORDER BY date DESC, id DESC") fun transactions(): Flow<List<FinanceTransaction>>
    @Query("SELECT * FROM budgets ORDER BY name") fun budgets(): Flow<List<Budget>>
    @Query("SELECT * FROM budgets WHERE id = :id LIMIT 1") suspend fun budgetById(id: Long): Budget?
    @Insert suspend fun addAccount(value: Account): Long
    @Insert suspend fun addTransaction(value: FinanceTransaction)
    @Insert suspend fun addBudget(value: Budget)
    @Delete suspend fun deleteTransaction(value: FinanceTransaction)
    @Delete suspend fun deleteBudget(value: Budget)
    @Query("SELECT COUNT(*) FROM accounts") suspend fun accountCount(): Int
}

@Database(entities = [Account::class, FinanceTransaction::class, Budget::class], version = 2, exportSchema = false)
abstract class FinanceDb : RoomDatabase() {
    abstract fun dao(): FinanceDao
    companion object {
        @Volatile private var instance: FinanceDb? = null
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN budgetId INTEGER")
            }
        }
        fun get(context: Context): FinanceDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, FinanceDb::class.java, "carteira.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}
