package pt.carteira.app

import android.content.Context
import androidx.room.*
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
    val toAccountId: Long? = null
)

@Entity(tableName = "budgets")
data class Budget(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val category: String, val limitAmount: Double)

@Dao interface FinanceDao {
    @Query("SELECT * FROM accounts ORDER BY id") fun accounts(): Flow<List<Account>>
    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1") suspend fun accountById(id: Long): Account?
    @Query("SELECT * FROM accounts ORDER BY id LIMIT 1") suspend fun firstAccount(): Account?
    @Query("SELECT * FROM transactions ORDER BY date DESC, id DESC") fun transactions(): Flow<List<FinanceTransaction>>
    @Query("SELECT * FROM budgets ORDER BY name") fun budgets(): Flow<List<Budget>>
    @Insert suspend fun addAccount(value: Account): Long
    @Insert suspend fun addTransaction(value: FinanceTransaction)
    @Insert suspend fun addBudget(value: Budget)
    @Delete suspend fun deleteTransaction(value: FinanceTransaction)
    @Delete suspend fun deleteBudget(value: Budget)
    @Query("SELECT COUNT(*) FROM accounts") suspend fun accountCount(): Int
}

@Database(entities = [Account::class, FinanceTransaction::class, Budget::class], version = 1, exportSchema = false)
abstract class FinanceDb : RoomDatabase() {
    abstract fun dao(): FinanceDao
    companion object {
        @Volatile private var instance: FinanceDb? = null
        fun get(context: Context): FinanceDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, FinanceDb::class.java, "carteira.db").build().also { instance = it }
        }
    }
}
