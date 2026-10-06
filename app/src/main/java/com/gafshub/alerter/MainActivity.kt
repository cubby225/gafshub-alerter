@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.gafshub.alerter

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.time.Instant

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifier.createChannels(this)
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.canNotify(this)) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Scheduler.apply(this)
        setContent { AppTheme { AlerterScreen(vm) } }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh() // pick up matches found in the background
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun AlerterScreen(vm: MainViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GAFSHub Alerter") },
                actions = {
                    Text(if (vm.enabled) "On" else "Off", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = vm.enabled, onCheckedChange = vm::updateEnabled)
                    Spacer(Modifier.width(12.dp))
                },
            )
        },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { LoginCard(vm) }
            item { TermsCard(vm) }
            item { SectionsCard(vm) }
            item { SettingsCard(vm) }
            item { SectionTitle("Recent matches") }
            if (vm.matches.isEmpty()) {
                item { Text("None yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(vm.matches, key = { it.topicId }) { MatchRow(it) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CardBox(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(title)
            content()
        }
    }
}

@Composable
private fun LoginCard(vm: MainViewModel) {
    CardBox("gafshub.com sign-in") {
        val who = vm.loggedInAs
        if (who != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Signed in as ")
                Text(who, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = vm::logout) { Text("Sign out") }
            }
        } else {
            var user by rememberSaveable { mutableStateOf(vm.savedUsername) }
            var pass by remember { mutableStateOf("") }
            OutlinedTextField(
                value = user, onValueChange = { user = it }, label = { Text("Username or email") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = pass, onValueChange = { pass = it }, label = { Text("Password") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.login(user, pass) }),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.login(user, pass) }, enabled = !vm.loginBusy) { Text("Sign in") }
                Spacer(Modifier.width(12.dp))
                if (vm.loginBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            vm.loginMsg?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Hint("Your login is stored encrypted on this phone and only sent to gafshub.com.")
        }
    }
}

@Composable
private fun TermsCard(vm: MainViewModel) {
    CardBox("Search terms") {
        var input by rememberSaveable { mutableStateOf("") }
        val add = { vm.addTerm(input); input = "" }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text("e.g. glock 19") }, singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = add) { Text("Add") }
        }
        if (vm.terms.isEmpty()) {
            Hint("No terms yet.")
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.terms.forEach { t ->
                    InputChip(
                        selected = false,
                        onClick = { vm.removeTerm(t) },
                        label = { Text(t) },
                        trailingIcon = { Text("✕") },
                    )
                }
            }
        }
        Hint("All words must appear, in any order. \"Quotes\" = exact phrase. -word = skip posts with that word. Tap a term to remove it.")
    }
}

@Composable
private fun SectionsCard(vm: MainViewModel) {
    CardBox("Buying section") {
        when {
            vm.catsLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Loading sections…")
            }
            vm.catsError != null -> {
                Text(vm.catsError ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = vm::loadCategories) { Text("Try again") }
            }
            else -> Column {
                val selectedIds = vm.selected.map { it.id }.toSet()
                vm.catRows.forEach { row ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.toggleCategory(row.cat) }
                            .padding(start = (row.depth * 24).dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = row.cat.id in selectedIds, onCheckedChange = { vm.toggleCategory(row.cat) })
                        Text(row.cat.name)
                    }
                }
            }
        }
        Hint("Sections with \"buy\", \"WTB\" or \"wanted\" in the name are picked automatically the first time.")
    }
}

@Composable
private fun SettingsCard(vm: MainViewModel) {
    val ctx = LocalContext.current
    CardBox("Checking") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Fast mode")
                Hint(if (vm.fastMode) "Checks every few minutes. Shows a small ongoing notification." else "Off: Android allows checks every 15 minutes at most.")
            }
            Switch(checked = vm.fastMode, onCheckedChange = vm::updateFastMode)
        }
        Text("Check every", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val options = if (vm.fastMode) listOf(1, 2, 5, 10) else listOf(15, 30, 60)
            options.forEach { m ->
                FilterChip(selected = vm.intervalMin == m, onClick = { vm.updateInterval(m) }, label = { Text("$m min") })
            }
        }
        if (vm.fastMode) {
            val pm = ctx.getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                OutlinedButton(onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                    )
                }) { Text("Allow checks while the phone is asleep") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = vm.checkBody, onCheckedChange = vm::updateCheckBody)
            Text("Also search the post text, not just titles")
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::checkNow, enabled = !vm.checking) { Text("Check now") }
            OutlinedButton(onClick = vm::testNotification) { Text("Test alert") }
            if (vm.checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        Text(
            vm.status,
            style = MaterialTheme.typography.bodySmall,
            color = if (vm.statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MatchRow(m: Match) {
    val ctx = LocalContext.current
    val ago = try {
        DateUtils.getRelativeTimeSpanString(Instant.parse(m.createdAt).toEpochMilli()).toString()
    } catch (e: Exception) { "" }
    Card(
        Modifier
            .fillMaxWidth()
            .clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(m.url))) },
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(m.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Hint("\"${m.term}\" · ${m.category}" + if (ago.isNotEmpty()) " · $ago" else "")
        }
    }
}
