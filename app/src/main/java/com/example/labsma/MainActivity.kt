package com.example.labsma

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.navigation.compose.*
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.getInstance(this)
        val dao = db.dishDao()
        val ocrEngine = MenuOcrEngine(dao)

        setContent {
            MaterialTheme {
                AppNavigation(dao, ocrEngine)
            }
        }
    }
}

@Composable
fun AppNavigation(dao: DishDao, ocrEngine: MenuOcrEngine) {
    val navController = rememberNavController()
    // Holds captured menu photo and detected dishes across navigation
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var matchedOverlays by remember { mutableStateOf<List<MatchedMenuOverlay>>(emptyList()) }

    NavHost(navController = navController, startDestination = "scanner") {
        composable("scanner") {
            MenuScannerScreen(
                capturedBitmap = capturedBitmap,
                matchedOverlays = matchedOverlays,
                onMenuCaptured = { bmp, matches ->
                    capturedBitmap = bmp
                    matchedOverlays = matches
                },
                onResetScan = {
                    capturedBitmap = null
                    matchedOverlays = emptyList()
                },
                onDishClick = { dishId -> navController.navigate("detail/$dishId") },
                onFavoritesClick = { navController.navigate("favorites") },
                ocrEngine = ocrEngine
            )
        }
        composable("detail/{dishId}") { backStackEntry ->
            val dishId = backStackEntry.arguments?.getString("dishId") ?: return@composable
            DishDetailScreen(
                dishId = dishId,
                dao = dao,
                navController = navController
            )
        }
        composable("favorites") {
            FavoritesScreen(dao = dao, navController = navController)
        }
    }
}

// --- SCREEN 1: CAMERA & INTERACTIVE PHOTO OVERLAY ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuScannerScreen(
    capturedBitmap: Bitmap?,
    matchedOverlays: List<MatchedMenuOverlay>,
    debugText: String = "",
    onMenuCaptured: (Bitmap, List<MatchedMenuOverlay>) -> Unit,
    onResetScan: () -> Unit,
    onDishClick: (String) -> Unit,
    onFavoritesClick: () -> Unit,
    ocrEngine: MenuOcrEngine
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasCamPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCamPermission = it
    }
    LaunchedEffect(Unit) {
        if (!hasCamPermission) permLauncher.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("China Menu Scanner (Offline)") },
                actions = {
                    IconButton(onClick = onFavoritesClick) {
                        Icon(Icons.Default.Favorite, contentDescription = "Favorites", tint = Color.Red)
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!hasCamPermission) {
                Text("Camera permission is required to scan menus.", modifier = Modifier.align(Alignment.Center))
            } else if (capturedBitmap == null) {
                val lifecycleOwner = LocalLifecycleOwner.current
                val cameraController = remember {
                    LifecycleCameraController(context).apply {
                        setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
                    }
                }
                var isProcessing by remember { mutableStateOf(false) }
                var errorMessage by remember { mutableStateOf<String?>(null) }

                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            controller = cameraController
                            cameraController.bindToLifecycle(lifecycleOwner)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                errorMessage?.let { msg ->
                    Surface(
                        color = Color.Red.copy(alpha = 0.85f),
                        modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(msg, color = Color.White, modifier = Modifier.padding(12.dp))
                    }
                }

                // SNAP BUTTON: Uses takePicture with proper file output or safe bitmap extraction
                ExtendedFloatingActionButton(
                    onClick = {
                        if (isProcessing) return@ExtendedFloatingActionButton
                        isProcessing = true
                        errorMessage = null

                        val photoFile = java.io.File.createTempFile("menu_snapshot", ".jpg", context.cacheDir)
                        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                        cameraController.takePicture(
                            outputOptions,
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    val bmp = android.graphics.BitmapFactory.decodeFile(photoFile.absolutePath)
                                    // Fix orientation matrix if needed
                                    scope.launch {
                                        try {
                                            val result = ocrEngine.scanMenuBitmap(bmp)
                                            isProcessing = false
                                            onMenuCaptured(bmp, result)
                                        } catch (e: Exception) {
                                            isProcessing = false
                                            errorMessage = "OCR Error: ${e.localizedMessage}"
                                        }
                                    }
                                }

                                override fun onError(exc: ImageCaptureException) {
                                    isProcessing = false
                                    errorMessage = "Capture failed: ${exc.message}"
                                }
                            }
                        )
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                    icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                    text = { Text(if (isProcessing) "Processing OCR..." else "Snap Menu") }
                )
            } else {
                var containerSize by remember { mutableStateOf(IntSize.Zero) }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .onSizeChanged { containerSize = it }
                ) {
                    Image(
                        bitmap = capturedBitmap.asImageBitmap(),
                        contentDescription = "Captured Menu",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize()
                    )

                    // TOP STATUS BANNER
                    Surface(
                        color = Color.Black.copy(alpha = 0.8f),
                        modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                    ) {
                        Text(
                            text = debugText.ifBlank { "No dishes matched. Try scanning closer!" },
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }

                    if (containerSize.width > 0 && containerSize.height > 0) {
                        val scaleX = containerSize.width.toFloat() / capturedBitmap.width.toFloat()
                        val scaleY = containerSize.height.toFloat() / capturedBitmap.height.toFloat()

                        matchedOverlays.forEach { item ->
                            val xDp = with(LocalDensity.current) { (item.boundingBox.left * scaleX).toDp() }
                            val yDp = with(LocalDensity.current) { (item.boundingBox.top * scaleY).toDp() }
                            val imgPath = item.dish.userCustomImagePath ?: item.dish.defaultAssetPath
                            val isTopPick = item.matchScore >= 85

                            Column(
                                modifier = Modifier
                                    .offset(x = xDp, y = yDp)
                                    .clickable { onDishClick(item.dish.id) },
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFFB71C1C))
                                        .border(
                                            width = if (isTopPick) 3.dp else 2.dp,
                                            color = if (isTopPick) Color(0xFFFFD700) else Color.White,
                                            shape = RoundedCornerShape(12.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Default.Restaurant, contentDescription = null, tint = Color.White)
                                        Text(item.dish.chineseName, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    AsyncImage(
                                        model = imgPath,
                                        contentDescription = item.dish.englishName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Surface(
                                    color = Color.Black.copy(alpha = 0.85f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "${item.dish.englishName.take(14)} (${item.matchScore}%)",
                                        color = if (isTopPick) Color(0xFFFFD700) else Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    FloatingActionButton(
                        onClick = onResetScan,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Scan Another Menu")
                    }
                }
            }
        }
    }
}

// --- SCREEN 2: DISH OVERVIEW, VARIATIONS, PHOTO REPLACEMENT & REVIEW ---
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DishDetailScreen(dishId: String, dao: DishDao, navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dish by dao.getDishByIdFlow(dishId).collectAsState(initial = null)
    val variations by dao.getVariationsForDish(dishId).collectAsState(initial = emptyList())
    val reviews by dao.getReviewsForDish(dishId).collectAsState(initial = emptyList())

    var showReviewSheet by remember { mutableStateOf(false) }

    // Launcher to snap a replacement photo of what actually arrived at the table
    val replacePhotoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null && dish != null) {
            scope.launch {
                val savedPath = MenuOcrEngine.saveReplacementPhoto(context, dish!!.id, bitmap)
                dao.updateCustomPhoto(dish!!.id, savedPath)
                showReviewSheet = true // Automatically prompt review after replacing photo!
            }
        }
    }

    val currentDish = dish ?: return

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${currentDish.chineseName} (${currentDish.englishName})") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch { dao.updateFavorite(currentDish.id, !currentDish.isFavorite) }
                    }) {
                        Icon(
                            imageVector = if (currentDish.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (currentDish.isFavorite) Color.Red else Color.Gray
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Dish Hero Image (Shows user replacement photo if available)
            item {
                val imagePath = currentDish.userCustomImagePath ?: currentDish.defaultAssetPath
                Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
                    AsyncImage(
                        model = imagePath,
                        contentDescription = currentDish.englishName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Color.LightGray)
                    )
                    if (currentDish.userCustomImagePath != null) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                        ) {
                            Text("Your Custom Photo", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(6.dp))
                        }
                    }
                }
            }

            // 2. Pinyin, Origin & Short Overview
            item {
                Text("${currentDish.chineseName} • ${currentDish.pinyin}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Origin Province: ${currentDish.originProvince}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(currentDish.overview, style = MaterialTheme.typography.bodyLarge)
            }

            // 3. Allergens Warning
            if (currentDish.allergens.isNotEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))) {
                        Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                            Text("⚠️ Allergens", fontWeight = FontWeight.Bold, color = Color(0xFFC62828))
                            Text(currentDish.allergens.joinToString(", "), color = Color(0xFFB71C1C))
                        }
                    }
                }
            }

            // 4. Main Flavors & Ingredients
            item {
                Text("Main Flavors", fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    currentDish.flavors.forEach { flavor ->
                        AssistChip(onClick = {}, label = { Text(flavor) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Main Ingredients", fontWeight = FontWeight.Bold)
                Text(currentDish.mainIngredients.joinToString(" • "))
            }

            // 5. History of the Dish
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("History & Cultural Background", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(currentDish.history)
                    }
                }
            }

            // 6. Regional Variations across Provinces (Clickable -> Opens Same Screen for Variation)
            if (variations.isNotEmpty()) {
                item {
                    Text("Regional Variations in Other Provinces", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    variations.forEach { varItem ->
                        OutlinedCard(
                            onClick = { navController.navigate("detail/${varItem.dish.id}") },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "${varItem.dish.originProvince}: ${varItem.dish.chineseName} (${varItem.dish.englishName})",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(varItem.variationRef.regionNote, style = MaterialTheme.typography.bodyMedium)
                                Text("Tap to view this variation →", fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }

            // 7. End of Dish: Replace Photo & Rate Dish
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Did your food look different?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { replacePhotoLauncher.launch() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Replace Photo")
                    }
                    OutlinedButton(
                        onClick = { showReviewSheet = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Star, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Rate Dish")
                    }
                }
            }

            // 8. Past Reviews
            if (reviews.isNotEmpty()) {
                item {
                    Text("Your Past Notes on This Dish", fontWeight = FontWeight.Bold)
                }
                items(reviews) { rev ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Overall: ${"★".repeat(rev.overallRating)} | Spiciness: ${rev.perceivedSpiciness}/5 | Flavor: ${rev.flavorRating}/5")
                            if (rev.keyPointTags.isNotEmpty()) {
                                Text("Key Points: ${rev.keyPointTags.joinToString(", ")}", fontSize = 13.sp, color = Color.DarkGray)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showReviewSheet) {
        ReviewBottomSheet(
            onDismiss = { showReviewSheet = false },
            onSubmit = { overall, spice, flavor, tags ->
                scope.launch {
                    dao.insertReview(
                        UserReviewEntity(
                            dishId = currentDish.id,
                            overallRating = overall,
                            perceivedSpiciness = spice,
                            flavorRating = flavor,
                            keyPointTags = tags
                        )
                    )
                    showReviewSheet = false
                }
            }
        )
    }
}

// --- REVIEW DIALOG ---
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReviewBottomSheet(
    onDismiss: () -> Unit,
    onSubmit: (Int, Int, Int, List<String>) -> Unit
) {
    var overallRating by remember { mutableIntStateOf(4) }
    var spiciness by remember { mutableIntStateOf(3) }
    var flavorRating by remember { mutableIntStateOf(4) }
    val availableTags = listOf("Authentic", "Too Oily", "Great Wok Hei", "Very Numbing", "Too Salty", "Huge Portion")
    val selectedTags = remember { mutableStateListOf<String>() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Rate & Train Your Recommendations", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            Text("Overall Rating: $overallRating / 5")
            Slider(value = overallRating.toFloat(), onValueChange = { overallRating = it.toInt() }, valueRange = 1f..5f, steps = 3)

            Text("Actual Spiciness: $spiciness / 5")
            Slider(value = spiciness.toFloat(), onValueChange = { spiciness = it.toInt() }, valueRange = 1f..5f, steps = 3)

            Text("Flavor Depth: $flavorRating / 5")
            Slider(value = flavorRating.toFloat(), onValueChange = { flavorRating = it.toInt() }, valueRange = 1f..5f, steps = 3)

            Text("Key Points:")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                availableTags.forEach { tag ->
                    val isSelected = selectedTags.contains(tag)
                    FilterChip(
                        selected = isSelected,
                        onClick = { if (isSelected) selectedTags.remove(tag) else selectedTags.add(tag) },
                        label = { Text(tag) }
                    )
                }
            }

            Button(
                onClick = { onSubmit(overallRating, spiciness, flavorRating, selectedTags.toList()) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Review")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// --- SCREEN 3: FAVORITES ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(dao: DishDao, navController: NavController) {
    val favorites by dao.getFavoriteDishes().collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved Favorite Dishes") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (favorites.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No favorite dishes saved yet.")
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(favorites) { dish ->
                    OutlinedCard(
                        onClick = { navController.navigate("detail/${dish.id}") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = dish.userCustomImagePath ?: dish.defaultAssetPath,
                                contentDescription = dish.englishName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(Color.LightGray)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("${dish.chineseName} • ${dish.englishName}", fontWeight = FontWeight.Bold)
                                Text(dish.originProvince, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

// Helper to fix CameraX rotation when converting ImageProxy to Bitmap
fun ImageProxy.toRotatedBitmap(): Bitmap {
    val bmp = this.toBitmap()
    val matrix = Matrix().apply { postRotate(imageInfo.rotationDegrees.toFloat()) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
}