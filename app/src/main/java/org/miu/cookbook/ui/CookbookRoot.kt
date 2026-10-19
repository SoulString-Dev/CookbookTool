package org.miu.cookbook.ui

import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.navigation.NavBackStackEntry
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
import org.miu.cookbook.ui.components.LocalMessenger
import org.miu.cookbook.ui.components.rememberMessenger
import org.miu.cookbook.ui.history.MealHistoryScreen
import org.miu.cookbook.ui.recipes.ImageEditScreen
import org.miu.cookbook.ui.recipes.RecipeDetailScreen
import org.miu.cookbook.ui.recipes.RecipeDetailState
import org.miu.cookbook.ui.recipes.RecipeListScreen
import org.miu.cookbook.ui.recipes.edit.ContentEditScreen
import org.miu.cookbook.ui.recipes.edit.IngredientEditScreen
import org.miu.cookbook.ui.settings.SyncSettingsScreen
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

/** 设置（现在只有家庭共享这一节）。它不是主界面，所以不进 [MainDestination]。 */
private const val ROUTE_SETTINGS = "settings"

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

    // 提示走应用自己的 Snackbar，颜色跟着主题（系统 Toast 切不动日夜模式）
    val snackbarHostState = remember { SnackbarHostState() }
    val messenger = rememberMessenger(snackbarHostState)

    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val mainDestination = MainDestination.entries.firstOrNull { it.route == route }

    // 右下角加号：非编辑态点它是「记录饮食」，已经在记录模式里再点一次才是「新增食谱」
    var newRecipeRequested by remember { mutableStateOf(false) }
    var recordRequested by remember { mutableStateOf(false) }
    var recordingActive by remember { mutableStateOf(false) }

    // 顶栏「⋮」里的「多选」：主界面用它进入多选，替掉了原来浮动栏上的编辑图标
    var selectionRequested by remember { mutableStateOf(false) }

    // 开机后自动同步一次（配对过才跑）。失败不打扰：错误留在设置页里，用户想看再去看
    LaunchedEffect(Unit) {
        if (container.syncEngine.paired) container.syncEngine.syncNow()
    }

    // 「改完就同步」：编辑是即时落盘的，但落盘只在本地。
    // 从编辑态退出来（按返回、点「完成」、从子编辑器回到详情页）或从详情页回到列表时触发一次，
    // 引擎里再把一秒半以内的多次触发合并成一轮。
    val editingNow = backStackEntry?.arguments?.getBoolean(ARG_RECIPE_EDIT) == true ||
        route == ROUTE_INGREDIENT_EDIT ||
        route == ROUTE_IMAGE_EDIT ||
        route == ROUTE_CONTENT_EDIT
    var wasEditing by remember { mutableStateOf(false) }
    var lastRoute by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(editingNow, route) {
        val leftEditor = wasEditing && !editingNow
        val backHome = mainDestination != null && lastRoute?.startsWith("recipe/") == true
        if (leftEditor || backHome) container.syncEngine.requestSync()
        wasEditing = editingNow
        lastRoute = route
    }

    // 详情页的编辑态与 Markdown 浮窗：顶栏上的按钮要直接改它们，所以状态放在这里，
    // 按 recipeId 记住（在「详情页 → 原料编辑器 → 返回」的来回里一直活着）
    val routeRecipeId = backStackEntry?.arguments?.getLong(ARG_RECIPE_ID) ?: 0L
    val detailState = remember(routeRecipeId) { RecipeDetailState() }

    // 顶栏**常驻**：进详情页时不再整条消失（那样切换时闪一下很突兀），
    // 只是把标题换成当前页面、把汉堡换成返回箭头。
    val sectionName = backStackEntry?.arguments?.getString(ARG_CONTENT_SECTION)
    val topTitle = when {
        mainDestination != null -> stringResource(mainDestination.labelRes)
        route == ROUTE_RECIPE_DETAIL -> stringResource(
            if (detailState.editing) R.string.recipe_edit_title else R.string.recipe_detail_title,
        )

        route == ROUTE_INGREDIENT_EDIT -> stringResource(R.string.section_ingredients)
        route == ROUTE_IMAGE_EDIT -> stringResource(R.string.image_edit_title)
        route == ROUTE_SETTINGS -> stringResource(R.string.settings_title)
        route == ROUTE_CONTENT_EDIT -> when (sectionName) {
            ContentSection.NOTE.name -> stringResource(R.string.section_notes)
            else -> stringResource(R.string.section_directions)
        }

        else -> stringResource(R.string.app_name)
    }

    CompositionLocalProvider(LocalMessenger provides messenger) {
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
                // 设置放在最底下：它管的是整个应用的事（外观、家庭共享），跟上面两页不是一类
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.settings_title)) },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    selected = route == ROUTE_SETTINGS,
                    onClick = {
                        scope.launch { drawerState.close() }
                        if (route != ROUTE_SETTINGS) navController.navigate(ROUTE_SETTINGS)
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
                Spacer(Modifier.height(12.dp))
            }
        },
    ) {
        Scaffold(
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState) { data ->
                    // 默认的 Snackbar 用 inverseSurface，日间是黑底、夜间是白底，跟应用反着来；
                    // 这里改成跟界面同一套底色，四角也收圆一点
                    Snackbar(
                        snackbarData = data,
                        shape = RoundedCornerShape(16.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        actionColor = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            topBar = {
                TopAppBar(
                    title = { Crossfade(targetState = topTitle, label = "top-title") { Text(it) } },
                    navigationIcon = {
                        if (mainDestination != null) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(
                                    imageVector = Icons.Filled.Menu,
                                    contentDescription = stringResource(R.string.drawer_open),
                                )
                            }
                        } else {
                            IconButton(onClick = {
                                // 从只读详情点进编辑态时，返回箭头先退出编辑态；
                                // 本来就是从列表的「编辑食谱」进来的，返回就直接回列表
                                if (route == ROUTE_RECIPE_DETAIL &&
                                    detailState.editing &&
                                    !detailState.enteredAsEditor
                                ) {
                                    detailState.editing = false
                                } else {
                                    navController.popBackStack()
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.action_back),
                                )
                            }
                        }
                    },
                    actions = {
                        when {
                            mainDestination != null -> BackupMenu(
                                repository = container.repository,
                                onMessage = { message -> messenger.show(message) },
                                onStartSelection = { selectionRequested = true },
                            )

                            // 只读时才有 Markdown 和铅笔；编辑态是即时落盘的，顶栏不留按钮
                            route == ROUTE_RECIPE_DETAIL -> if (!detailState.editing &&
                                detailState.available
                            ) {
                                TextButton(onClick = { detailState.markdownVisible = true }) {
                                    Text(stringResource(R.string.recipe_view_markdown))
                                }
                                IconButton(onClick = { detailState.editing = true }) {
                                    Icon(
                                        imageVector = Icons.Filled.Edit,
                                        contentDescription = stringResource(R.string.recipe_edit_title),
                                    )
                                }
                            }

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
                composable(ROUTE_SETTINGS) {
                    SyncSettingsScreen(
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
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
                    val recipeId = entry.recipeIdOrNull() ?: return@composable
                    val startEditing = entry.arguments?.getBoolean(ARG_RECIPE_EDIT) ?: false
                    RecipeDetailScreen(
                        recipeId = recipeId,
                        state = detailState,
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
                    val recipeId = entry.recipeIdOrNull() ?: return@composable
                    IngredientEditScreen(recipeId = recipeId)
                }
                composable(
                    route = ROUTE_IMAGE_EDIT,
                    arguments = listOf(navArgument(ARG_RECIPE_ID) { type = NavType.LongType }),
                ) { entry ->
                    val recipeId = entry.recipeIdOrNull() ?: return@composable
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
                    val recipeId = entry.recipeIdOrNull() ?: return@composable
                    val name = entry.arguments?.getString(ARG_CONTENT_SECTION)
                    val section = ContentSection.entries.firstOrNull { it.name == name }
                        ?: return@composable
                    ContentEditScreen(recipeId = recipeId, section = section)
                }
            }
        }
    }
    }
}

/** 详情、原料、图片、正文这几个路由都带同一个 `recipeId` 参数，取法统一放这里。 */
private fun NavBackStackEntry.recipeIdOrNull(): Long? = arguments?.getLong(ARG_RECIPE_ID)
