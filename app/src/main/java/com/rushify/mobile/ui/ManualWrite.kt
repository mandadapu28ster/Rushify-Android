package com.rushify.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rushify.mobile.data.ApiError
import com.rushify.mobile.data.ManualHoldingOut
import com.rushify.mobile.data.RushifyRepository
import com.rushify.mobile.data.SymbolHit
import com.rushify.mobile.data.SymbolQuote
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

data class WriteUi(val busy: Boolean = false, val message: String? = null, val error: String? = null)

/**
 * Online-only writes for manual holdings and sector overrides. Nothing is queued: a failed write shows the server's message and
 * leaves everything as it was. After a success the caller refreshes Home, Holdings and the charts.
 */
class ManualViewModel(private val repo: RushifyRepository) : ViewModel() {
    val ui = MutableStateFlow(WriteUi())
    val details = MutableStateFlow<Map<Int, ManualHoldingOut>>(emptyMap())
    val hits = MutableStateFlow<List<SymbolHit>>(emptyList())
    val quote = MutableStateFlow<SymbolQuote?>(null)
    var today: () -> LocalDate = { LocalDate.now() }
    private var searchJob: Job? = null

    fun clear() { ui.value = WriteUi() }

    fun loadDetails() {
        viewModelScope.launch { runCatching { repo.manualHoldings() }.onSuccess { l -> details.value = l.associateBy { it.id } } }
    }

    /** Same as the web: wait 350 ms after typing, and only search from 2 characters. */
    fun search(q: String) {
        searchJob?.cancel()
        if (q.trim().length < 2) { hits.value = emptyList(); return }
        searchJob = viewModelScope.launch {
            delay(350)
            hits.value = runCatching { repo.call { repo.api.symbolSearch(q.trim()) } }.getOrDefault(emptyList())
        }
    }

    /** Picking a hit fills the symbol, then asks for a quote; a failed quote is ignored (the web does the same). */
    fun pick(hit: SymbolHit, onSymbol: (String) -> Unit) {
        hits.value = emptyList(); quote.value = null
        onSymbol(hit.symbol)
        viewModelScope.launch {
            val q = runCatching { repo.call { repo.api.symbolQuote(hit.symbol) } }.getOrNull()
            quote.value = q
            q?.normalizedSymbol?.takeIf { it.isNotBlank() }?.let(onSymbol)
        }
    }

    fun resetSearch() { hits.value = emptyList(); quote.value = null }

    private fun run(built: Built, ok: String, onDone: () -> Unit, call: suspend (JsonObject) -> Unit) {
        if (ui.value.busy) return
        when (built) {
            is Built.Err -> ui.value = WriteUi(error = built.message)
            is Built.Ok -> viewModelScope.launch {
                ui.value = WriteUi(busy = true)
                try { repo.call { call(built.body) }; ui.value = WriteUi(message = ok); onDone() }
                catch (e: ApiError) { ui.value = WriteUi(error = e.message) }
            }
        }
    }

    fun create(b: Built, onDone: () -> Unit) = run(b, "Saved. Home and Holdings will refresh shortly.", onDone) { repo.api.createManual(it) }
    fun editListed(id: Int, b: Built, onDone: () -> Unit) = run(b, "Lot updated.", onDone) { repo.api.patchManual(id, it) }
    fun editValues(id: Int, b: Built, onDone: () -> Unit) = run(b, "Values saved.", { loadDetails(); onDone() }) { repo.api.patchManual(id, it) }
    fun sell(id: Int, b: Built, onDone: () -> Unit) = run(b, "Sale recorded.", onDone) { repo.api.sellManual(id, it) }
    fun saveSector(b: Built, onDone: () -> Unit) = run(b, "Sector saved.", onDone) { repo.api.putSectorOverride(it) }
    fun remove(id: Int, onDone: () -> Unit) {
        if (ui.value.busy) return
        viewModelScope.launch {
            ui.value = WriteUi(busy = true)
            try { repo.call { repo.api.deleteManual(id) }; ui.value = WriteUi(message = "Position removed."); onDone() }
            catch (e: ApiError) { ui.value = WriteUi(error = e.message) }
        }
    }
}

sealed interface ManualForm {
    val target: DetailTarget
    data class EditLot(override val target: DetailTarget) : ManualForm
    data class Sell(override val target: DetailTarget) : ManualForm
    data class EditValues(override val target: DetailTarget) : ManualForm
    data class Sector(override val target: DetailTarget) : ManualForm
    data class Remove(override val target: DetailTarget) : ManualForm
}

/** Buttons shown under a holding's details; only the ones that apply to this row. */
@Composable
fun ManualActions(t: DetailTarget, onForm: (ManualForm) -> Unit) {
    val r = t.row
    val sold = HoldingsLogic.isManualListedSold(r)
    val listedManual = ManualRules.isListedManual(r)
    val nonListed = ManualRules.isNonListedManual(r)
    val canSector = HoldingsLogic.isListedAssetKind(r.assetKind) && !sold && t.primary && r.source != "monzo"
    if (!listedManual && !nonListed && !canSector) return
    Spacer(Modifier.height(8.dp))
    SectionTitle("Edit")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (canSector) ActionBtn("Change sector", "act_sector") { onForm(ManualForm.Sector(t)) }
        if (listedManual && !sold) { ActionBtn("Edit lot", "act_edit") { onForm(ManualForm.EditLot(t)) }; ActionBtn("Record sale", "act_sell") { onForm(ManualForm.Sell(t)) } }
        if (nonListed) ActionBtn("Edit values", "act_values") { onForm(ManualForm.EditValues(t)) }
        if (listedManual || nonListed) ActionBtn("Remove", "act_remove", danger = true) { onForm(ManualForm.Remove(t)) }
    }
}

@Composable
private fun ActionBtn(text: String, tag: String, danger: Boolean = false, onClick: () -> Unit) {
    Button(onClick, modifier = Modifier.testTag(tag), colors = if (danger) ButtonDefaults.buttonColors(containerColor = RColors.Variant, contentColor = RColors.Loss) else ButtonDefaults.buttonColors(containerColor = RColors.Variant, contentColor = RColors.Text)) { Text(text) }
}

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, tag: String, number: Boolean = false, enabled: Boolean = true, hint: String? = null) {
    OutlinedTextField(
        value, onChange, singleLine = true, enabled = enabled, label = { Text(label) }, supportingText = hint?.let { { Text(it) } },
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
private fun FormFrame(title: String, vm: ManualViewModel, content: @Composable () -> Unit) {
    val ui by vm.ui.collectAsState()
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp).testTag("manual_form"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = RColors.Text, fontSize = 20.sp)
        ui.error?.let { Banner(it, RColors.Loss, Modifier.testTag("form_error")) }
        content()
    }
}

@Composable
private fun SaveRow(vm: ManualViewModel, label: String, onSave: () -> Unit, onCancel: () -> Unit) {
    val ui by vm.ui.collectAsState()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onSave, enabled = !ui.busy, modifier = Modifier.testTag("form_save")) { Text(if (ui.busy) "Saving…" else label) }
        TextButton(onCancel, enabled = !ui.busy, modifier = Modifier.testTag("form_cancel")) { Text("Cancel") }
    }
}

@Composable
private fun ChipRow(options: List<Pair<String, String>>, selected: String, tagPrefix: String, onPick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, l) -> Chip(l, selected == v, tag = "$tagPrefix$v") { onPick(v) } }
    }
}

private fun plain(v: java.math.BigDecimal?) = v?.stripTrailingZeros()?.toPlainString().orEmpty()

/** Shown in place of the holding details when one of the edit actions is chosen. */
@Composable
fun ManualFormHost(form: ManualForm, vm: ManualViewModel, onDone: () -> Unit, onBack: () -> Unit, onChanged: () -> Unit) {
    val done = { onChanged(); vm.clear(); onDone() }
    val cancel = { vm.clear(); onBack() }
    val r = form.target.row
    val id = r.manualHoldingId
    when (form) {
        is ManualForm.EditLot -> EditLotForm(form.target, vm, id ?: return, done, cancel)
        is ManualForm.Sell -> SellForm(form.target, vm, id ?: return, done, cancel)
        is ManualForm.EditValues -> EditValuesForm(vm, id ?: return, done, cancel)
        is ManualForm.Sector -> SectorForm(form.target, vm, done, cancel)
        is ManualForm.Remove -> FormFrame("Remove this position?", vm) {
            Text("Remove this manual position from the portfolio?\n\nIf you sold the shares, consider using “Record sale” first (sell date and price) so the listed equity chart can track quantity over time. Deleting here removes the line without recording that sale.", color = RColors.Text, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val ui by vm.ui.collectAsState()
                Button({ vm.remove(id ?: return@Button, done) }, enabled = !ui.busy, colors = ButtonDefaults.buttonColors(containerColor = RColors.Loss), modifier = Modifier.testTag("remove_ok")) { Text(if (ui.busy) "Removing…" else "Remove") }
                TextButton(cancel, enabled = !ui.busy, modifier = Modifier.testTag("form_cancel")) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun EditLotForm(t: DetailTarget, vm: ManualViewModel, id: Int, done: () -> Unit, cancel: () -> Unit) {
    val r = t.row
    var label by remember { mutableStateOf(r.instrumentName.orEmpty()) }
    var qty by remember { mutableStateOf(plain(r.quantity)) }
    var broker by remember { mutableStateOf(r.manualBrokerName ?: r.brokerLabel.orEmpty()) }
    var kind by remember { mutableStateOf(r.accountKind?.takeIf { k -> ManualRules.LISTED_EDIT_KINDS.any { it.first == k } } ?: "unspecified") }
    var date by remember { mutableStateOf(r.purchaseDate.orEmpty()) }
    var cost by remember { mutableStateOf(plain(r.costBaseGbp)) }
    FormFrame("Edit lot · ${HoldingsLogic.displayTicker(r, t.rollup)}", vm) {
        Field("Label (optional)", label, { label = it }, "f_label")
        Field("Quantity", qty, { qty = it }, "f_qty", number = true)
        Field("Broker", broker, { broker = it }, "f_broker")
        Muted("Account type")
        ChipRow(ManualRules.LISTED_EDIT_KINDS, kind, "kind_") { kind = it }
        Field("Purchase date", date, { date = it }, "f_date", hint = "YYYY-MM-DD")
        Field("Total cost (GBP)", cost, { cost = it }, "f_cost", number = true)
        SaveRow(vm, "Save lot", { vm.editListed(id, ManualRules.listedEdit(label, qty, broker, kind, date, cost, vm.today()), done) }, cancel)
    }
}

@Composable
private fun SellForm(t: DetailTarget, vm: ManualViewModel, id: Int, done: () -> Unit, cancel: () -> Unit) {
    val r = t.row
    var qty by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(vm.today().toString()) }
    var price by remember { mutableStateOf("") }
    FormFrame("Record sale · ${HoldingsLogic.displayTicker(r, t.rollup)}", vm) {
        Muted("Held: ${Fmt.quantity(r.quantity)}. All fields are required; use the GBP price per share from your contract note.")
        Field("Quantity sold", qty, { qty = it }, "f_qty", number = true)
        TextButton({ qty = plain(r.quantity) }, modifier = Modifier.testTag("sell_all")) { Text("Sell entire position") }
        Field("Sell date", date, { date = it }, "f_date", hint = "YYYY-MM-DD")
        Field("Price per share (GBP)", price, { price = it }, "f_price", number = true)
        SaveRow(vm, "Record sale", { vm.sell(id, ManualRules.sell(r.quantity, r.purchaseDate, qty, date, price, vm.today()), done) }, cancel)
    }
}

@Composable
private fun SectorForm(t: DetailTarget, vm: ManualViewModel, done: () -> Unit, cancel: () -> Unit) {
    val r = t.row
    val ticker = ManualRules.sectorTicker(r, t.rollup)
    val current = r.sector?.takeIf { it.isNotBlank() && !it.equals("unknown", true) }
    val options = if (current != null && ManualRules.SECTORS.none { it.equals(current, true) }) listOf(current) + ManualRules.SECTORS else ManualRules.SECTORS
    var pick by remember { mutableStateOf(current.orEmpty()) }
    FormFrame("Change sector · $ticker", vm) {
        Muted("Your choice is stored on this account only and applies to every lot of $ticker.")
        Column(Modifier.testTag("sector_options")) {
            options.forEach { s ->
                Row(Modifier.fillMaxWidth().clickable { pick = s }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(pick == s, { pick = s }, modifier = Modifier.testTag("sector_$s"))
                    Text(if (s == current && ManualRules.SECTORS.none { it.equals(s, true) }) "$s (from data)" else s, color = RColors.Text)
                }
            }
        }
        val ui by vm.ui.collectAsState()
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button({ vm.saveSector(ManualRules.sectorBody(ticker, pick), done) }, enabled = pick.isNotBlank() && !ui.busy, modifier = Modifier.testTag("form_save")) { Text(if (ui.busy) "Saving…" else "Save sector") }
            TextButton(cancel, enabled = !ui.busy, modifier = Modifier.testTag("form_cancel")) { Text("Cancel") }
        }
    }
}

@Composable
private fun EditValuesForm(vm: ManualViewModel, id: Int, done: () -> Unit, cancel: () -> Unit) {
    val details by vm.details.collectAsState()
    LaunchedEffect(Unit) { vm.loadDetails() }
    val m = details[id]
    if (m == null) { FormFrame("Edit values", vm) { Muted("Loading…"); TextButton(cancel, modifier = Modifier.testTag("form_cancel")) { Text("Cancel") } }; return }
    val exited = m.exitDate != null
    var f by remember(m.id) { mutableStateOf(ManualRules.formFrom(m)) }
    var mode by remember(m.id) { mutableStateOf("update") }
    var updateDate by remember(m.id) { mutableStateOf("") }
    var chart by remember(m.id) { mutableStateOf(m.showOnWealthChart ?: true) }
    FormFrame(if (exited) "Wealth chart settings" else "Edit values", vm) {
        if (exited) Banner("This line is closed (${m.exitDate}). Only notes, currency and the wealth chart setting can change.", RColors.Warn)
        if (!exited) {
            KindFields(f, { f = it }, "f_")
            Field("Opened date", f.openedDate, { f = f.copy(openedDate = it) }, "f_opened", hint = "YYYY-MM-DD")
            Field("Opening amount (GBP)", f.openingValue, { f = f.copy(openingValue = it) }, "f_opening", number = true)
            Muted("Save as")
            ChipRow(listOf("update" to "New value", "exit" to "Close line"), mode, "mode_") { mode = it }
            if (mode == "update") Field("Value as of (optional)", updateDate, { updateDate = it }, "f_update_date", hint = "YYYY-MM-DD, defaults to today")
            else {
                Muted("Closing records the exit and the wealth chart steps to zero after it.")
                Field("Exit date", f.exitDate, { f = f.copy(exitDate = it) }, "f_exit_date", hint = "YYYY-MM-DD")
                Field("Exit amount (GBP)", f.exitValue, { f = f.copy(exitValue = it) }, "f_exit_value", number = true)
            }
        }
        Field("Currency", f.currency, { f = f.copy(currency = it) }, "f_currency")
        Field("Notes (optional)", f.notes, { f = f.copy(notes = it) }, "f_notes")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(chart, { chart = it }, modifier = Modifier.testTag("f_chart"))
            Spacer(Modifier.padding(start = 8.dp)); Muted("Show on Home “Wealth over time”")
        }
        if (m.chartEligible == false) Muted("This line does not qualify for the chart yet (needs an opening date over a year ago and at least 5 saved values).")
        SaveRow(vm, "Save", { vm.editValues(id, ManualRules.nonListedEdit(ManualRules.NonListedEdit(f, exited, chart, mode, updateDate), vm.today()), done) }, cancel)
    }
}

/** Fields that depend on the asset kind (property, cash, other). Shared by the add screen and the edit form. */
@Composable
fun KindFields(f: ManualRules.NonListed, onChange: (ManualRules.NonListed) -> Unit, p: String) {
    when (f.kind) {
        ManualKind.Property -> {
            Field("Name", f.label, { onChange(f.copy(label = it)) }, "${p}label")
            Field("Home value", f.homeValue, { onChange(f.copy(homeValue = it)) }, "${p}home", number = true)
            Field("Mortgage balance", f.mortgage, { onChange(f.copy(mortgage = it)) }, "${p}mortgage", number = true)
        }
        ManualKind.Cash -> {
            Field("Bank or building society", f.broker, { onChange(f.copy(broker = it)) }, "${p}bank")
            Muted("Account type")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ManualRules.CASH_TYPES.forEach { t -> Chip(t, f.cashType == t, tag = "${p}type_$t") { onChange(f.copy(cashType = t)) } }
            }
            if (f.cashType == ManualRules.CASH_OTHER) Field("Describe the account", f.cashTypeOther, { onChange(f.copy(cashTypeOther = it)) }, "${p}type_other")
            Field("Balance", f.cashBalance, { onChange(f.copy(cashBalance = it)) }, "${p}balance", number = true)
        }
        else -> {
            Field("Name", f.label, { onChange(f.copy(label = it)) }, "${p}label")
            Field("Value", f.otherValue, { onChange(f.copy(otherValue = it)) }, "${p}other", number = true)
        }
    }
}

/** Add a stock/ETF lot, property, cash savings or another asset (More → Add holding). */
@Composable
fun AddManualScreen(vm: ManualViewModel, onBack: () -> Unit, onChanged: () -> Unit) {
    val ui by vm.ui.collectAsState()
    var kind by remember { mutableStateOf(ManualKind.Listed) }
    var formKey by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { vm.clear(); vm.resetSearch() }
    SubScreen("Add holding", { vm.clear(); vm.resetSearch(); onBack() }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("add_manual"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Muted("Saved to your account on the server. Cost and gain are only as accurate as what you enter.")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ManualKind.entries.forEach { k -> Chip(k.label, kind == k, tag = "add_kind_${k.api}") { kind = k; vm.clear(); vm.resetSearch() } }
            }
            ui.message?.let { Banner(it, RColors.Accent, Modifier.testTag("add_message")) }
            ui.error?.let { Banner(it, RColors.Loss, Modifier.testTag("form_error")) }
            androidx.compose.runtime.key(kind, formKey) {
                if (kind == ManualKind.Listed) AddListed(vm) { formKey++; onChanged() } else AddNonListed(kind, vm) { formKey++; onChanged() }
            }
        }
    }
}

@Composable
private fun AddListed(vm: ManualViewModel, onSaved: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val hits by vm.hits.collectAsState()
    val quote by vm.quote.collectAsState()
    var search by remember { mutableStateOf("") }
    var symbol by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("") }
    var broker by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("sipp") }
    var date by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    Field("Search company or ticker", search, { search = it; vm.search(it) }, "f_search")
    hits.take(8).forEach { h ->
        Row(Modifier.fillMaxWidth().clickable { vm.pick(h) { s -> symbol = s }; label = h.name.orEmpty(); search = "" }.padding(vertical = 6.dp).testTag("hit_${h.symbol}"), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) { Num(h.symbol, bold = true, size = 13); Muted(h.name.orEmpty()) }
            Muted(listOfNotNull(h.exchange, h.currency).joinToString(" · "))
        }
    }
    Field("Symbol", symbol, { symbol = it }, "f_symbol")
    quote?.lastPrice?.let { Muted("Last quote: ${Fmt.number(it)} ${quote?.currency.orEmpty()} (for information; not saved)", Modifier.testTag("quote_info")) }
    Field("Label (optional)", label, { label = it }, "f_label")
    Field("Quantity", qty, { qty = it }, "f_qty", number = true)
    Field("Broker", broker, { broker = it }, "f_broker")
    Muted("Account type")
    ChipRow(ManualRules.LISTED_CREATE_KINDS, kind, "kind_") { kind = it }
    Field("Purchase date", date, { date = it }, "f_date", hint = "YYYY-MM-DD")
    Field("Total cost (GBP)", cost, { cost = it }, "f_cost", number = true)
    Button({ vm.create(ManualRules.listedCreate(symbol, label, qty, broker, kind, date, cost, vm.today()), onSaved) }, enabled = !ui.busy, modifier = Modifier.testTag("form_save")) { Text(if (ui.busy) "Saving…" else "Add holding") }
}

@Composable
private fun AddNonListed(k: ManualKind, vm: ManualViewModel, onSaved: () -> Unit) {
    val ui by vm.ui.collectAsState()
    var f by remember { mutableStateOf(ManualRules.NonListed(kind = k)) }
    KindFields(f, { f = it }, "f_")
    Field("Currency", f.currency, { f = f.copy(currency = it) }, "f_currency")
    Field("Opened date", f.openedDate, { f = f.copy(openedDate = it) }, "f_opened", hint = "YYYY-MM-DD")
    Field("Opening amount (GBP)", f.openingValue, { f = f.copy(openingValue = it) }, "f_opening", number = true)
    Muted("Already closed? Add both an exit date and amount, or leave both blank.")
    Field("Exit date (optional)", f.exitDate, { f = f.copy(exitDate = it) }, "f_exit_date", hint = "YYYY-MM-DD")
    Field("Exit amount (optional)", f.exitValue, { f = f.copy(exitValue = it) }, "f_exit_value", number = true)
    Field("Notes (optional)", f.notes, { f = f.copy(notes = it) }, "f_notes")
    Button({ vm.create(ManualRules.nonListedCreate(f, vm.today()), onSaved) }, enabled = !ui.busy, modifier = Modifier.testTag("form_save")) { Text(if (ui.busy) "Saving…" else "Add ${k.label.lowercase()}") }
}
