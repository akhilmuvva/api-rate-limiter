package com.cutm.nt14.ui.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cutm.nt14.data.repository.GatewayRepository
import com.cutm.nt14.ui.abuse.IncidentScreen
import com.cutm.nt14.ui.biometric.BiometricLockScreen
import com.cutm.nt14.ui.components.GlassBackgroundDark
import com.cutm.nt14.ui.components.GlobalConnectionBanner
import com.cutm.nt14.ui.dashboard.DashboardScreen
import com.cutm.nt14.ui.endpoints.EndpointScreen
import com.cutm.nt14.ui.login.LoginScreen
import com.cutm.nt14.ui.login.LoginViewModel
import com.cutm.nt14.ui.login.SplashScreen
import com.cutm.nt14.ui.login.SplashViewModel
import com.cutm.nt14.ui.logs.LogScreen
import com.cutm.nt14.ui.ratelimits.RateLimitScreen
import com.cutm.nt14.ui.reports.ReportScreen

sealed class Screen(val route: String, val title: String = "", val icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    object Splash : Screen("splash")
    object Login : Screen("login")
    object BiometricLock : Screen("biometric_lock")
    object Dashboard : Screen("dashboard", "Dashboard", Icons.Default.Home)
    object Endpoints : Screen("endpoints", "Endpoints", Icons.AutoMirrored.Filled.List)
    object Logs : Screen("logs", "Logs", Icons.Default.Info)
    object RateLimits : Screen("ratelimits", "Limits", Icons.Default.Settings)
    object Incidents : Screen("incidents", "Security", Icons.Default.Warning)
    object Reports : Screen("reports", "Reports", Icons.Default.Share)
}

@Composable
fun NT14NavHost(repository: GatewayRepository) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val bans by repository.bans.collectAsState()
    val incidents by repository.incidents.collectAsState()
    val securityBadgeCount = bans.size + incidents.size

    val bottomNavScreens = listOf(Screen.Dashboard, Screen.Endpoints, Screen.Logs, Screen.RateLimits, Screen.Incidents, Screen.Reports)

    Scaffold(
        containerColor = GlassBackgroundDark,
        bottomBar = {
            if (currentRoute != Screen.Splash.route && 
                currentRoute != Screen.Login.route && 
                currentRoute != Screen.BiometricLock.route) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(4.dp, RoundedCornerShape(30.dp), ambientColor = Color(0x14000000))
                            .clip(RoundedCornerShape(30.dp))
                            .background(Color.White.copy(alpha = 0.94f))
                            .border(
                                BorderStroke(
                                    1.dp,
                                    Color(0xFFE2E8F0)
                                ),
                                RoundedCornerShape(30.dp)
                            )
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        bottomNavScreens.forEach { screen ->
                            val selected = currentRoute == screen.route
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(
                                        if (selected) com.cutm.nt14.ui.components.PolyPrimaryLight
                                        else Color.Transparent
                                    )
                                    .clickable {
                                        navController.navigate(screen.route) {
                                            popUpTo(Screen.Dashboard.route) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    BadgedBox(
                                        badge = {
                                            if (screen == Screen.Incidents && securityBadgeCount > 0) {
                                                Badge(
                                                    containerColor = com.cutm.nt14.ui.components.PolyDanger,
                                                    contentColor = Color.White
                                                ) {
                                                    Text(
                                                        text = securityBadgeCount.toString(),
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = screen.icon!!,
                                            contentDescription = screen.title,
                                            tint = if (selected) com.cutm.nt14.ui.components.PolyPrimary else com.cutm.nt14.ui.components.PolyTextSecondary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Text(
                                        text = screen.title,
                                        fontSize = 10.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selected) com.cutm.nt14.ui.components.PolyPrimaryDark else com.cutm.nt14.ui.components.PolyTextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (currentRoute != Screen.Splash.route && 
                currentRoute != Screen.Login.route && 
                currentRoute != Screen.BiometricLock.route) {
                GlobalConnectionBanner(repository)
            }

            Box(modifier = Modifier.weight(1f)) {
                NavHost(
                    navController = navController, 
                    startDestination = Screen.Splash.route,
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable(Screen.Splash.route) {
                        val viewModel: SplashViewModel = hiltViewModel()
                        SplashScreen(
                            viewModel = viewModel,
                            onNavigateToLogin = {
                                navController.navigate(Screen.Login.route) {
                                    popUpTo(Screen.Splash.route) { inclusive = true }
                                }
                            },
                            onNavigateToBiometricLock = {
                                navController.navigate(Screen.BiometricLock.route) {
                                    popUpTo(Screen.Splash.route) { inclusive = true }
                                }
                            },
                            onNavigateToDashboard = {
                                navController.navigate(Screen.Dashboard.route) {
                                    popUpTo(Screen.Splash.route) { inclusive = true }
                                }
                            }
                        )
                    }

                    composable(Screen.BiometricLock.route) {
                        BiometricLockScreen(
                            onUnlockSuccess = {
                                navController.navigate(Screen.Dashboard.route) {
                                    popUpTo(Screen.BiometricLock.route) { inclusive = true }
                                }
                            },
                            onSignOut = {
                                navController.navigate(Screen.Login.route) {
                                    popUpTo(Screen.BiometricLock.route) { inclusive = true }
                                }
                            }
                        )
                    }

                    composable(Screen.Login.route) {
                        val viewModel: LoginViewModel = hiltViewModel()
                        LoginScreen(
                            viewModel = viewModel,
                            onLoginSuccess = {
                                navController.navigate(Screen.Dashboard.route) {
                                    popUpTo(Screen.Login.route) { inclusive = true }
                                }
                            }
                        )
                    }

                    composable(Screen.Dashboard.route) {
                        DashboardScreen(
                            onLogout = {
                                navController.navigate(Screen.Login.route) {
                                    popUpTo(Screen.Dashboard.route) { inclusive = true }
                                }
                            }
                        )
                    }
                    composable(Screen.Endpoints.route) { EndpointScreen() }
                    composable(Screen.Logs.route) { LogScreen() }
                    composable(Screen.RateLimits.route) { RateLimitScreen() }
                    composable(Screen.Incidents.route) { IncidentScreen() }
                    composable(Screen.Reports.route) { ReportScreen() }
                }
            }
        }
    }
}
