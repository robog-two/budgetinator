package net.robog.budgetinator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.robog.budgetinator.model.BudgetStore
import net.robog.budgetinator.validation.Severity
import net.robog.budgetinator.validation.Step
import java.awt.Frame

private val SgcBlue = Color(0xFF1167B8)
private val Scheme = lightColorScheme(
    primary = SgcBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E8FA),
    secondary = Color(0xFF4DA3F0),
    surface = Color.White,
    background = Color(0xFFF5F8FC),
)

@Composable
fun BudgetinatorApp(window: Frame, store: BudgetStore) {
    // Narrow windows (down to the 900pt minimum) get a slimmer sidebar and gutters so item rows still fit.
    val compact = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } < 1100.dp
    val gutter = if (compact) 24.dp else 40.dp
    MaterialTheme(colorScheme = Scheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxSize()) {
                Sidebar(store, if (compact) 210.dp else 260.dp)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        Column(Modifier.widthIn(max = 920.dp).padding(horizontal = gutter, vertical = 32.dp).align(Alignment.TopCenter)) {
                            when (store.step) {
                                Step.BASICS -> BasicsPage(store)
                                Step.DETAILS -> DetailsPage(store)
                                Step.ITEMS -> ItemsPage(store)
                                Step.FUNDING -> FundingPage(store)
                                Step.DOCS -> DocsPage(store, window)
                                Step.REVIEW -> ReviewPage(store, window)
                            }
                        }
                    }
                    NavBar(store, gutter)
                }
            }
        }
    }
}

@Composable
private fun Sidebar(store: BudgetStore, width: Dp) {
    Column(Modifier.width(width).fillMaxHeight().background(MaterialTheme.colorScheme.surface).padding(20.dp)) {
        Text("Budgetinator", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = SgcBlue)
        Text("SGC budget request", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        Step.entries.forEachIndexed { i, s ->
            val selected = store.step == s
            val errs = store.issues.count { it.step == s && it.severity == Severity.ERROR }
            val show = store.showErrorsFor(s) && s != Step.REVIEW
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable { store.goTo(s) }.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(26.dp).clip(RoundedCornerShape(13.dp))
                        .background(if (show && errs > 0) MaterialTheme.colorScheme.error else if (selected) SgcBlue else MaterialTheme.colorScheme.outlineVariant),
                    contentAlignment = Alignment.Center,
                ) { Text("${i + 1}", color = Color.White, style = MaterialTheme.typography.labelMedium) }
                Text(s.title, Modifier.weight(1f), fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                if (show && errs > 0) Text("$errs", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.weight(1f))
        Text("Running total", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(money(store.request.grandTotal), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun NavBar(store: BudgetStore, gutter: Dp) {
    val steps = Step.entries
    val i = steps.indexOf(store.step)
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = gutter, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton({ store.goTo(steps[i - 1]) }, enabled = i > 0) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Spacer(Modifier.width(6.dp)); Text("Back")
            }
            Box(Modifier.weight(1f).padding(start = 16.dp), contentAlignment = Alignment.CenterEnd) {
                if (store.warningCount + store.errorCount > 0)
                    Text("${store.errorCount} to fix · ${store.warningCount} to review", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(16.dp))
            if (i < steps.lastIndex) Button({ store.goTo(steps[i + 1]) }) {
                Text("Next"); Spacer(Modifier.width(6.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
            }
        }
    }
}
