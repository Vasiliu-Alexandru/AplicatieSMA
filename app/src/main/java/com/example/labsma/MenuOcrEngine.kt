package com.example.labsma

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

class MenuOcrEngine(private val dishDao: DishDao) {

    // Uses the offline bundled Chinese OCR model
    private val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

    suspend fun scanMenuBitmap(bitmap: Bitmap): List<MatchedMenuOverlay> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val visionText = recognizer.process(image).await()

        val allDishes = dishDao.getAllDishes()
        val allReviews = dishDao.getAllReviews()
        val results = mutableListOf<MatchedMenuOverlay>()

        // Calculate user's preferred spice level from past reviews rated >= 4 stars
        val likedReviews = allReviews.filter { it.overallRating >= 4 }
        val preferredSpice = if (likedReviews.isNotEmpty()) {
            likedReviews.map { it.perceivedSpiciness }.average()
        } else 3.0

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val rawText = line.text.replace("\\s+".toRegex(), "")
                val box = line.boundingBox ?: continue

                // Ignore prices or single characters
                if (rawText.length < 2) continue

                // Match if the menu line contains a known dish name (e.g., "招牌麻婆豆腐 28元" matches "麻婆豆腐")
                // Sort by longest chineseName first so "黔味宫保鸡丁" matches before "宫保鸡丁"
                val matchedDish = allDishes
                    .sortedByDescending { it.chineseName.length }
                    .firstOrNull { dish -> rawText.contains(dish.chineseName) }

                if (matchedDish != null) {
                    val score = calculateRecommendationScore(matchedDish, preferredSpice)
                    results.add(MatchedMenuOverlay(matchedDish, box, score))
                }
            }
        }
        return results
    }

    private fun calculateRecommendationScore(dish: DishEntity, preferredSpice: Double): Int {
        var score = 70 // Base score
        if (dish.isFavorite) score += 25
        // Reward dishes close to user's preferred spiciness
        val spiceDiff = abs(dish.baseSpiciness - preferredSpice)
        score += ((5.0 - spiceDiff) * 5).toInt()
        return score.coerceIn(10, 99)
    }

    companion object {
        // Saves user's replacement dish photo to internal offline storage
        fun saveReplacementPhoto(context: Context, dishId: String, bitmap: Bitmap): String {
            val file = File(context.filesDir, "user_dish_${dishId}_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            return file.absolutePath
        }
    }
}