package org.miu.cookbook.ui

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import org.miu.cookbook.R
import org.miu.cookbook.data.db.ContentSection
import org.miu.cookbook.di.LocalAppContainer
import org.miu.cookbook.ui.backup.BackupMenu
import org.miu.cookbook.ui.history.MealHistoryScreen
import org.miu.cookbook.ui.recipes.ImageEditScreen
import org.miu.cookbook.ui.recipes.RecipeDetailScreen
import org.miu.cookbook.ui.recipes.RecipeListScreen
import org.miu.cookbook.ui.recipes.edit.ContentEditScreen
import org.miu.cookbook.ui.recipes.edit.IngredientEditScreen
import org.miu.cookbook.ui.theme.ThemeMode

/** 侧边栏里的两个主界面。 */
enum class MainDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    Recipes("recipes", R.string.dest_recipes, Icons.AutoMirrored.Filled.List),
    History("history", R.string.dest_history, Icons.Filled.DateRange),
}

/** 食谱详情路由。`edit=true` 时进去就是编辑态（长按菜单的「编辑食谱」用这个）。 */
private const val ROUTE_RECIPE_DETAIL = "recipe/{recipeId}?edit={edit}"
private const val ARG_RECIPE_ID = "recipeId"
private const val ARG_RECIPE_EDIT = "edit"

/** 原料编辑。 */
private const val ROUTE_INGREDIENT_EDIT = "recipe/{recipeId}/ingredients"

/** 图片编辑（黑底、可删/加/换序/设封面）。 */
private const val ROUTE_IMAGE_EDIT = "recipe/{recipeId}/images"

/** 做法 / 备注的文本编辑。 */
private const val ROUTE_CONTENT_EDIT = "recipe/{recipeId}/text/{section}"
private const val ARG_CONTENT_SECTION = "section"

private fun recipeDetailRoute(recipeId: Long, edit: Boolean = false) =
    "recipe/$recipeId?edit=$edit"

private fun ingredientEditRoute(recipeId: Long) = "recipe/$recipeId/ingredients"

private fun imageEditRoute(recipeId: Long) = "recipe/$recipeId/images"

private fun contentEditRoute(recipeId: Long, section: ContentSection) =
    "recipe/$recipeId/text/${section.name}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookbookRoot(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {},
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val container = LocalAppContainer.current
    val context = LocalContext.current

    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val mainDestination = MainDestination.entries.firstOrNull { it.route == route }

    // 右下角加号：非编辑态点它是「记录饮食」，已经在记录模式里再点一次才是「新增食谱」
    var newRecipeRequested by remember { mutableStateOf(false) }
    var recordRequested by remember { mutableStateOf(false) }
    var recordingActive by remember { mutableStateOf(false) }

    // 顶栏「⋮」里的「多选」：主界面用它进入多选，替掉了原来浮动栏上的编辑图标
    var selectionRequested by remember { mutableStateOf(false) }

    // 详情页把自己的菜名报上来，顶栏标题跟着变
    var detailTitle by remember { mutableStateOf("") }

    // 顶栏**常驻**：进详情页时不再整条消失（那样切换时闪一下很突兀），
    // 只是把标题换成当前页面、把汉堡换成返回箭头。
    val sectionName = backStackEntry?.arguments?.getString(ARG_CONTENT_SECTION)
    val topTitle = when {
        mainDestination != null -> stringResource(mainDestination.labelRes)
        route == ROUTE_RECIPE_DETAIL -> detailTitle.ifBlank { stringResource(R.string.dest_recipes) }
        route == ROUTE_INGREDIENT_EDIT -> stringResource(R.string.section_ingredients)
        route == ROUTE_IMAGE_EDIT -> stringResource(R.string.image_edit_title)
        route == ROUTE_CONTENT_EDIT -> when (sectionName) {
            ContentSection.NOTE.name -> stringResource(R.string.section_notes)
            else -> stringResource(R.string.section_directions)
        }

        else -> stringResource(R.string.app_name)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 28.dp, top = 28.dp, bottom = 16.dp),
                )
                HorizontalDivider(Modifier.padding(horizontal = 28.dp))
                Spacer(Modifier.height(12.dp))
                MainDestination.entries.forEach { destination ->
                    NavigationDrawerItem(
                        label = { Text(stringResource(destination.labelRes)) },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        selected = destination == mainDestination,
                        onClick = {
                            scope.launch { drawerState.close() }
                            if (destination.route != route) {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }
                Spacer(Modifier.weight(1f))
                HorizontalDivider(Modifier.padding(horizontal = 28.dp))
                Text(
                    text = stringResource(R.string.theme_appearance),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 28.dp, top = 16.dp, bottom = 4.dp),
                )
                ThemeMode.entries.forEach { mode ->
                    NavigationDrawerItem(
                        label = { Text(stringResource(mode.labelRes)) },
                        selected = mode == themeMode,
                        onClick = { onThemeModeChange(mode) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(topTitle) },
                    navigationIcon = {
                        if (mainDestination != null) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(
                                    imageVector = Icons.Filled.Menu,
                                    contentDescription = stringResource(R.string.drawer_open),
                                )
                            }
                        } else {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.action_back),
                                )
                            }
                        }
                    },
                    actions = {
                        // 备份、多选都只放在两个主界面上，详情页顶栏保持干净
                        if (mainDestination != null) {
                            BackupMenu(
                                repository = container.repository,
                                onMessage = { message ->
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                },
                                onStartSelection = { selectionRequested = true },
                            )
                        }
                    },
                )
            },
            floatingActionButton = {
                if (route == MainDestination.Recipes.route) {
                    FloatingActionButton(onClick = {
                        if (recordingActive) newRecipeRequested = true else recordRequested = true
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(
                                if (recordingActive) R.string.action_add_recipe
                                else R.string.meal_record_title,
                            ),
                        )
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = MainDestination.Recipes.route,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable(MainDestination.Recipes.route) {
                    RecipeListScreen(
                        onOpenRecipe = { navController.navigate(recipeDetailRoute(it)) },
                        onEditRecipe = { navController.navigate(recipeDetailRoute(it, edit = true)) },
                        newRecipeRequested = newRecipeRequested,
                        onNewRecipeHandled = { newRecipeRequested = false },
                        recordRequested = recordRequested,
                        onRecordRequestedHandled = { recordRequested = false },
                        onRecordingChanged = { recordingActive = it },
                        selectionRequested = selectionRequested,
                        onSelectionRequestedHandled = { selectionRequested = false },
                    )
                }
                composable(MainDestination.History.route) {
                    MealHistoryScreen(
                        onOpenRecipe = { navController.navigate(recipeDetailRoute(it)) },
                        selectionRequested = selectionRequested,
                        onSelectionRequestedHandled = { selectionRequested = false },
                    )
                }
                composable(
                    route = ROUTE_RECIPE_DETAIL,
                    arguments = listOf(
                        navArgument(ARG_RECIPE_ID) { type = NavType.LongType },
                        navArgument(ARG_RECIPE_EDIT) {
                            type = NavType.BoolType
                            defaultValue = false
                        },
                    ),
                ) { entry ->
                    val recipeId = entry.arguments?.getLong(ARG_RECIPE_ID) ?: return@composable
                    val startEditing = entry.arguments?.getBoolean(ARG_RECIPE_EDIT) ?: false
                    RecipeDetailScreen(
                        recipeId = recipeId,
                        onEditIngredients = { navController.navigate(ingredientEditRoute(recipeId)) },
                        onEditContent = { section -> navController.navigate(contentEditRoute(recipeId, section)) },
                        onEditImages = { navController.navigate(imageEditRoute(recipeId)) },
                        startEditing = startEditing,
                        onTitleChange = { detailTitle = it },
                    )
                }
                composable(
                    route = ROUTE_INGREDIENT_EDIT,
                    arguments = listOf(navArgument(ARG_RECIPE_ID) { type = NavType.LongType }),
                ) { entry ->
                    val recipeId = entry.arguments?.getLong(ARG_RECIPE_ID) ?: return@composable
                    IngredientEditScreen(
                        recipeId = recipeId,
                        onDone = { navController.popBackStack() },
                        onCancel = { navController.popBackStack() },
                    )
                }
                composable(
                    route = ROUTE_IMAGE_EDIT,
                    arguments = listOf(navArgument(ARG_RECIPE_ID) { type = NavType.LongType }),
                ) { entry ->
                    val recipeId = entry.arguments?.getLong(ARG_RECIPE_ID) ?: return@composable
                    ImageEditScreen(
                        recipeId = recipeId,
                        onDone = { navController.popBackStack() },
                    )
                }
                composable(
                    route = ROUTE_CONTENT_EDIT,
                    arguments = listOf(
                        navArgument(ARG_RECIPE_ID) { type = NavType.LongType },
                        navArgument(ARG_CONTENT_SECTION) { type = NavType.StringType },
                    ),
                ) { entry ->
                    val recipeId = entry.arguments?.getLong(ARG_RECIPE_ID) ?: return@composable
                    val name = entry.arguments?.getString(ARG_CONTENT_SECTION)
                    val section = ContentSection.entries.firstOrNull { it.name == name }
                        ?: return@composable
                    ContentEditScreen(
                        recipeId = recipeId,
                        section = section,
                        onDone = { navController.popBackStack() },
                        onCancel = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}
