package com.example.labsma

import android.content.Context
import android.graphics.Rect
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

// --- 1. TYPE CONVERTERS ---
class Converters {
    private val gson = Gson()
    @TypeConverter
    fun fromStringList(value: List<String>): String = gson.toJson(value)

    @TypeConverter
    fun toStringList(value: String): List<String> {
        val listType = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(value, listType) ?: emptyList()
    }
}

// --- 2. ENTITIES ---
@Entity(tableName = "dishes")
data class DishEntity(
    @PrimaryKey val id: String,
    val chineseName: String,
    val pinyin: String,
    val englishName: String,
    val overview: String,
    val originProvince: String,
    val history: String,
    val mainIngredients: List<String>,
    val flavors: List<String>,
    val allergens: List<String>,
    val baseSpiciness: Int, // 1-5
    val baseSavory: Int,    // 1-5
    val defaultAssetPath: String,
    val userCustomImagePath: String? = null,
    val isFavorite: Boolean = false
)

@Entity(tableName = "dish_variations", primaryKeys = ["parentDishId", "variationDishId"])
data class DishVariationCrossRef(
    val parentDishId: String,
    val variationDishId: String,
    val regionNote: String
)

@Entity(tableName = "user_reviews")
data class UserReviewEntity(
    @PrimaryKey(autoGenerate = true) val reviewId: Int = 0,
    val dishId: String,
    val overallRating: Int,      // 1-5
    val perceivedSpiciness: Int, // 1-5
    val flavorRating: Int,       // 1-5
    val keyPointTags: List<String>,
    val timestamp: Long = System.currentTimeMillis()
)

data class VariationWithDish(
    @Embedded val variationRef: DishVariationCrossRef,
    @Relation(parentColumn = "variationDishId", entityColumn = "id")
    val dish: DishEntity
)

// Holds a matched dish + where it was found on the menu photo + recommendation score
data class MatchedMenuOverlay(
    val dish: DishEntity,
    val boundingBox: Rect,
    val matchScore: Int // Used to highlight recommended dishes on the menu!
)

// --- 3. DAO ---
@Dao
interface DishDao {
    @Query("SELECT * FROM dishes")
    suspend fun getAllDishes(): List<DishEntity>

    @Query("SELECT * FROM dishes WHERE id = :id")
    fun getDishByIdFlow(id: String): Flow<DishEntity?>

    @Query("SELECT * FROM dishes WHERE isFavorite = 1")
    fun getFavoriteDishes(): Flow<List<DishEntity>>

    @Transaction
    @Query("SELECT * FROM dish_variations WHERE parentDishId = :dishId")
    fun getVariationsForDish(dishId: String): Flow<List<VariationWithDish>>

    @Query("UPDATE dishes SET isFavorite = :isFav WHERE id = :dishId")
    suspend fun updateFavorite(dishId: String, isFav: Boolean)

    @Query("UPDATE dishes SET userCustomImagePath = :path WHERE id = :dishId")
    suspend fun updateCustomPhoto(dishId: String, path: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReview(review: UserReviewEntity)

    @Query("SELECT * FROM user_reviews WHERE dishId = :dishId ORDER BY timestamp DESC")
    fun getReviewsForDish(dishId: String): Flow<List<UserReviewEntity>>

    @Query("SELECT * FROM user_reviews")
    suspend fun getAllReviews(): List<UserReviewEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDishes(dishes: List<DishEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVariations(variations: List<DishVariationCrossRef>)
}

// --- 4. DATABASE & SEED DATA ---
@Database(
    entities = [DishEntity::class, DishVariationCrossRef::class, UserReviewEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dishDao(): DishDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "offline_china_menu.db"
                ).addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        CoroutineScope(Dispatchers.IO).launch {
                            seedDatabase(getInstance(context).dishDao())
                        }
                    }
                }).build().also { INSTANCE = it }
            }
        }

        private suspend fun seedDatabase(dao: DishDao) {
            // Note: Put matching WebP images inside app/src/main/assets/dishes/
            val sampleDishes = listOf(
                DishEntity(
                    id = "gong_bao_sichuan",
                    chineseName = "宫保鸡丁",
                    pinyin = "Gōngbǎo Jīdīng",
                    englishName = "Sichuan Kung Pao Chicken",
                    overview = "Diced chicken stir-fried with dried chilies, Sichuan peppercorns, and crunchy fried peanuts in a glossy sweet-sour-savory glaze.",
                    originProvince = "Sichuan",
                    history = "Named after Ding Baozhen, a late-Qing Dynasty official and governor of Sichuan whose title was Gongbao (Palace Guardian).",
                    mainIngredients = listOf("Chicken thigh", "Roasted peanuts", "Dried facing-heaven chilies", "Sichuan peppercorns", "Scallions"),
                    flavors = listOf("Mala (Numbing & Spicy)", "Lychee Sweet-Sour", "Savory", "Nutty"),
                    allergens = listOf("Peanuts", "Soy", "Gluten (Soy Sauce)"),
                    baseSpiciness = 4,
                    baseSavory = 5,
                    defaultAssetPath = "file:///android_asset/dishes/gongbao.webp"
                ),
                DishEntity(
                    id = "gong_bao_guizhou",
                    chineseName = "黔味宫保鸡丁",
                    pinyin = "Qiánwèi Gōngbǎo Jīdīng",
                    englishName = "Guizhou-Style Kung Pao Chicken",
                    overview = "A tangier, fermented chili version of Kung Pao Chicken using Guizhou Ciba chilies instead of heavy soy glaze.",
                    originProvince = "Guizhou",
                    history = "Ding Baozhen was actually born in Guizhou; locals argue the original dish used Guizhou's prized fermented Ciba chilies.",
                    mainIngredients = listOf("Chicken", "Ciba pounded chilies", "Walnuts or Peanuts", "Garlic stems"),
                    flavors = listOf("Sour-Spicy", "Fermented", "Savory"),
                    allergens = listOf("Peanuts", "Tree Nuts", "Soy"),
                    baseSpiciness = 5,
                    baseSavory = 4,
                    defaultAssetPath = "file:///android_asset/dishes/gongbao_gz.webp"
                ),
                DishEntity(
                    id = "mapo_tofu",
                    chineseName = "麻婆豆腐",
                    pinyin = "Mápó Dòufu",
                    englishName = "Mapo Tofu",
                    overview = "Silken tofu braised in a fiery, bright-red sauce made from fermented broad bean paste (Doubanjiang), fermented black beans, and minced beef or pork.",
                    originProvince = "Sichuan",
                    history = "Created in 1862 by Mrs. Chen (nicknamed Chen Mapo) at her small eatery near Wanfu Bridge in northern Chengdu.",
                    mainIngredients = listOf("Soft Tofu", "Minced Beef/Pork", "Pixian Doubanjiang", "Sichuan Peppercorn Powder", "Garlic Chives"),
                    flavors = listOf("Numbing (Ma)", "Spicy (La)", "Umami", "Fermented"),
                    allergens = listOf("Soy", "Gluten"),
                    baseSpiciness = 5,
                    baseSavory = 5,
                    defaultAssetPath = "file:///android_asset/dishes/mapo_tofu.webp"
                ),
                DishEntity(
                    id = "yu_xiang_rou_si",
                    chineseName = "鱼香肉丝",
                    pinyin = "Yúxiāng Ròusī",
                    englishName = "Yu-Shiang Shredded Pork",
                    overview = "Finely julienned pork stir-fried with wood ear mushrooms and bamboo shoots in a 'fish-fragrant' garlic-chili-vinegar sauce. Contains no seafood!",
                    originProvince = "Sichuan",
                    history = "Developed by Sichuan home cooks who used traditional fish-braising seasonings (pickled chilies, ginger, garlic, sugar, vinegar) on pork.",
                    mainIngredients = listOf("Pork loin", "Wood ear mushrooms", "Bamboo shoots", "Pickled Erjingtiao chilies", "Ginger & Garlic"),
                    flavors = listOf("Sweet-Sour", "Garlicky", "Mildly Spicy"),
                    allergens = listOf("Soy", "Gluten"),
                    baseSpiciness = 2,
                    baseSavory = 4,
                    defaultAssetPath = "file:///android_asset/dishes/yuxiang.webp"
                )
            )
            dao.insertDishes(sampleDishes)

            // Link Sichuan Kung Pao and Guizhou Kung Pao to each other
            dao.insertVariations(
                listOf(
                    DishVariationCrossRef(
                        parentDishId = "gong_bao_sichuan",
                        variationDishId = "gong_bao_guizhou",
                        regionNote = "Guizhou (Ding Baozhen's birthplace) uses pounded Ciba chilies for a sour-spicy kick."
                    ),
                    DishVariationCrossRef(
                        parentDishId = "gong_bao_guizhou",
                        variationDishId = "gong_bao_sichuan",
                        regionNote = "Sichuan's classic version uses dried chilies and a 'huli' (scorched chili) sweet-sour glaze."
                    )
                )
            )
        }
    }
}