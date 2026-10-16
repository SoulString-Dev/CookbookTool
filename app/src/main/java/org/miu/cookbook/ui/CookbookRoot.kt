package org.miu.cookbook.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
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
import org.miu.cookbook.ui.history.MealHistoryScreen
import org.miu.cookbook.ui.recipes.ImageEditScreen
import org.miu.cookbook.ui.recipes.RecipeDetailScreen
import org.miu.cookbook.ui.recipes.RecipeListScreen
import org.miu.cookbook.ui.recipes.edit.ContentEditScreen
import org.miu.cookbook.ui.recipes.edit.IngredientEditScreen

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
fun CookbookRoot() {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val mainDestination = MainDestination.entries.firstOrNull { it.route == route }

    // 右下角加号：点一下让食谱页弹「新建食谱」
    var newRecipeRequested by remember { mutableStateOf(false) }

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
            }
        },
    ) {
        Scaffold(
            topBar = {
                // 详情页有自己的返回栏，这里不显示
                if (mainDestination != null) {
                    TopAppBar(
                        title = { Text(stringResource(mainDestination.labelRes)) },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(
                                    imageVector = Icons.Filled.Menu,
                                    contentDescription = stringResource(R.string.drawer_open),
                                )
                            }
                        },
                    )
                }
            },
            floatingActionButton = {
                if (route == MainDestination.Recipes.route) {
                    FloatingActionButton(onClick = { newRecipeRequested = true }) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.action_add_recipe),
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
                    )
                }
                composable(MainDestination.History.route) {
                    MealHistoryScreen()
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
                        onBack = { navController.popBackStack() },
                        onEditIngredients = { navController.navigate(ingredientEditRoute(recipeId)) },
                        onEditContent = { section -> navController.navigate(contentEditRoute(recipeId, section)) },
                        onEditImages = { navController.navigate(imageEditRoute(recipeId)) },
                        startEditing = startEditing,
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
                    val sectionName = entry.arguments?.getString(ARG_CONTENT_SECTION)
                    val section = ContentSection.entries.firstOrNull { it.name == sectionName }
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
